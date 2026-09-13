package com.eclassroom.core.identity;

import com.eclassroom.core.shared.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {
    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final SecurityEventService events;
    private final String issuer;
    private final long accessMinutes;
    private final long refreshDays;
    private final int maxFailedAttempts;
    private final long attemptWindowMinutes;
    private final long lockMinutes;
    private final SecureRandom random = new SecureRandom();

    public AuthService(JdbcTemplate jdbc,
                       PasswordEncoder passwords,
                       JwtEncoder encoder,
                       SecurityEventService events,
                       @Value("${app.jwt.issuer}") String issuer,
                       @Value("${app.jwt.access-minutes}") long accessMinutes,
                       @Value("${app.jwt.refresh-days}") long refreshDays,
                       @Value("${app.auth.max-failed-attempts:5}") int maxFailedAttempts,
                       @Value("${app.auth.attempt-window-minutes:10}") long attemptWindowMinutes,
                       @Value("${app.auth.lock-minutes:15}") long lockMinutes) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.encoder = encoder;
        this.events = events;
        this.issuer = issuer;
        this.accessMinutes = accessMinutes;
        this.refreshDays = refreshDays;
        this.maxFailedAttempts = Math.max(3, maxFailedAttempts);
        this.attemptWindowMinutes = Math.max(1, attemptWindowMinutes);
        this.lockMinutes = Math.max(1, lockMinutes);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(String email, String password, ClientContext client) {
        String normalizedEmail = normalizeEmail(email);
        String identifierHash = sha256(normalizedEmail);
        if (isLoginBlocked(identifierHash)) {
            events.record(null, null, "LOGIN", "THROTTLED", null,
                    Map.of("ipHash", client.ipHash(), "device", deviceLabel(client.userAgent())));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", INVALID_CREDENTIALS);
        }

        List<UserRow> users = jdbc.query(
                "SELECT id,email,password_hash,full_name,platform_role,status,must_change_password,token_version " +
                        "FROM identity.users WHERE lower(email)=lower(?)",
                (rs, i) -> new UserRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("full_name"),
                        rs.getString("platform_role"),
                        rs.getString("status"),
                        rs.getBoolean("must_change_password"),
                        rs.getLong("token_version")),
                normalizedEmail);

        UserRow user = users.isEmpty() ? null : users.getFirst();
        boolean valid = user != null && "ACTIVE".equals(user.status()) && passwords.matches(password, user.passwordHash());
        if (!valid) {
            recordFailedAttempt(identifierHash);
            events.record(user == null ? null : user.id(), null, "LOGIN", "FAILURE", null,
                    Map.of("ipHash", client.ipHash(), "device", deviceLabel(client.userAgent())));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", INVALID_CREDENTIALS);
        }

        jdbc.update("DELETE FROM identity.login_attempts WHERE identifier_hash=?", identifierHash);
        UUID sessionId = UUID.randomUUID();
        TokenResponse response = issue(user, sessionId, client);
        events.record(user.id(), null, "LOGIN", "SUCCESS", sessionId,
                Map.of("ipHash", client.ipHash(), "device", deviceLabel(client.userAgent())));
        return response;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String rawToken, ClientContext client) {
        String hash = sha256(rawToken);
        List<RefreshRow> rows = jdbc.query(
                "SELECT r.id token_id,r.user_id,r.session_id,r.revoked_at,r.revoked_reason,r.expires_at," +
                        "u.email,u.password_hash,u.full_name,u.platform_role,u.status,u.must_change_password,u.token_version " +
                        "FROM identity.refresh_tokens r JOIN identity.users u ON u.id=r.user_id " +
                        "WHERE r.token_hash=? FOR UPDATE",
                (rs, i) -> new RefreshRow(
                        UUID.fromString(rs.getString("token_id")),
                        UUID.fromString(rs.getString("user_id")),
                        UUID.fromString(rs.getString("session_id")),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                        rs.getString("revoked_reason"),
                        rs.getTimestamp("expires_at").toInstant(),
                        new UserRow(
                                UUID.fromString(rs.getString("user_id")),
                                rs.getString("email"),
                                rs.getString("password_hash"),
                                rs.getString("full_name"),
                                rs.getString("platform_role"),
                                rs.getString("status"),
                                rs.getBoolean("must_change_password"),
                                rs.getLong("token_version"))),
                hash);

        if (rows.isEmpty()) throw invalidRefresh();

        RefreshRow row = rows.getFirst();
        Instant now = Instant.now();
        if (row.revokedAt() != null) {
            if ("ROTATED".equals(row.revokedReason())) {
                revokeAllInternal(row.userId(), "REFRESH_REPLAY");
                jdbc.update("UPDATE identity.users SET token_version=token_version+1 WHERE id=?", row.userId());
                events.record(row.userId(), null, "REFRESH_REPLAY", "BLOCKED", row.sessionId(),
                        Map.of("device", deviceLabel(client.userAgent())));
            }
            throw invalidRefresh();
        }
        if (!row.expiresAt().isAfter(now) || !"ACTIVE".equals(row.user().status())) {
            jdbc.update("UPDATE identity.refresh_tokens SET revoked_at=COALESCE(revoked_at,NOW()),revoked_reason=COALESCE(revoked_reason,'INVALIDATED') WHERE id=?", row.tokenId());
            throw invalidRefresh();
        }

        jdbc.update("UPDATE identity.refresh_tokens SET revoked_at=NOW(),revoked_reason='ROTATED',last_seen_at=NOW() WHERE id=?", row.tokenId());
        TokenResponse response = issue(row.user(), row.sessionId(), client);
        events.record(row.userId(), null, "REFRESH", "SUCCESS", row.sessionId(),
                Map.of("device", deviceLabel(client.userAgent())));
        return response;
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        String hash = sha256(rawToken);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id,session_id FROM identity.refresh_tokens WHERE token_hash=? AND revoked_at IS NULL FOR UPDATE",
                hash);
        if (rows.isEmpty()) return;
        UUID userId = UUID.fromString(String.valueOf(rows.getFirst().get("user_id")));
        UUID sessionId = UUID.fromString(String.valueOf(rows.getFirst().get("session_id")));
        jdbc.update("UPDATE identity.refresh_tokens SET revoked_at=NOW(),revoked_reason='USER_LOGOUT' WHERE user_id=? AND session_id=? AND revoked_at IS NULL",
                userId, sessionId);
        events.record(userId, null, "LOGOUT", "SUCCESS", sessionId, Map.of());
    }

    public Map<String, Object> me(UUID userId) {
        Map<String, Object> user = jdbc.queryForMap(
                "SELECT id,email,full_name,COALESCE(platform_role,'') platform_role,status,must_change_password,password_changed_at " +
                        "FROM identity.users WHERE id=?",
                userId);
        List<Map<String, Object>> memberships = jdbc.queryForList(
                "SELECT m.school_id,s.code school_code,s.name school_name,m.role FROM identity.school_memberships m " +
                        "JOIN school.schools s ON s.id=m.school_id WHERE m.user_id=? AND m.status='ACTIVE' ORDER BY s.name,m.role",
                userId);
        return Map.of("user", user, "memberships", memberships);
    }

    public List<SessionView> sessions(UUID userId, UUID currentSessionId) {
        return jdbc.query(
                "WITH ranked AS (" +
                        "SELECT r.*,MIN(created_at) OVER(PARTITION BY session_id) session_created_at," +
                        "ROW_NUMBER() OVER(PARTITION BY session_id ORDER BY created_at DESC,id DESC) rn " +
                        "FROM identity.refresh_tokens r WHERE user_id=? AND created_at>NOW()-INTERVAL '90 days') " +
                        "SELECT session_id,session_created_at,last_seen_at,expires_at,revoked_at,user_agent " +
                        "FROM ranked WHERE rn=1 ORDER BY last_seen_at DESC LIMIT 20",
                (rs, i) -> {
                    UUID sessionId = UUID.fromString(rs.getString("session_id"));
                    Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
                    Instant revokedAt = rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant();
                    boolean active = revokedAt == null && expiresAt.isAfter(Instant.now());
                    return new SessionView(
                            sessionId,
                            rs.getTimestamp("session_created_at").toInstant(),
                            rs.getTimestamp("last_seen_at").toInstant(),
                            expiresAt,
                            active,
                            sessionId.equals(currentSessionId),
                            deviceLabel(rs.getString("user_agent")));
                },
                userId);
    }

    @Transactional
    public int revokeSession(UUID userId, UUID sessionId, String reason) {
        int updated = jdbc.update(
                "UPDATE identity.refresh_tokens SET revoked_at=NOW(),revoked_reason=? WHERE user_id=? AND session_id=? AND revoked_at IS NULL",
                reason, userId, sessionId);
        if (updated > 0) events.record(userId, null, "SESSION_REVOKE", "SUCCESS", sessionId, Map.of("reason", reason));
        return updated;
    }

    @Transactional
    public int revokeAll(UUID userId, String reason) {
        int updated = revokeAllInternal(userId, reason);
        events.record(userId, null, "SESSION_REVOKE_ALL", "SUCCESS", null, Map.of("reason", reason, "count", updated));
        return updated;
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        validateNewPassword(newPassword);
        List<UserRow> users = jdbc.query(
                "SELECT id,email,password_hash,full_name,platform_role,status,must_change_password,token_version FROM identity.users WHERE id=? FOR UPDATE",
                (rs, i) -> new UserRow(
                        UUID.fromString(rs.getString("id")), rs.getString("email"), rs.getString("password_hash"),
                        rs.getString("full_name"), rs.getString("platform_role"), rs.getString("status"),
                        rs.getBoolean("must_change_password"), rs.getLong("token_version")),
                userId);
        if (users.isEmpty() || !"ACTIVE".equals(users.getFirst().status()) || !passwords.matches(currentPassword, users.getFirst().passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }
        if (passwords.matches(newPassword, users.getFirst().passwordHash())) {
            throw ApiException.badRequest("PASSWORD_REUSED", "New password must be different from the current password");
        }
        jdbc.update(
                "UPDATE identity.users SET password_hash=?,must_change_password=FALSE,password_changed_at=NOW(),token_version=token_version+1,updated_at=NOW() WHERE id=?",
                passwords.encode(newPassword), userId);
        revokeAllInternal(userId, "PASSWORD_CHANGED");
        events.record(userId, null, "PASSWORD_CHANGE", "SUCCESS", null, Map.of());
    }

    public void validateNewPassword(String password) {
        if (password == null || password.length() < 10 || password.length() > 128 ||
                password.chars().noneMatch(Character::isUpperCase) ||
                password.chars().noneMatch(Character::isLowerCase) ||
                password.chars().noneMatch(Character::isDigit)) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Password must be 10-128 characters and include upper-case, lower-case and a number");
        }
    }

    private TokenResponse issue(UserRow user, UUID sessionId, ClientContext client) {
        Instant now = Instant.now();
        Instant exp = now.plus(accessMinutes, ChronoUnit.MINUTES);
        List<Map<String, Object>> rawMemberships = jdbc.queryForList(
                "SELECT school_id,role FROM identity.school_memberships WHERE user_id=? AND status='ACTIVE'", user.id());
        List<Map<String, String>> memberships = rawMemberships.stream().map(row -> {
            Map<String, String> claim = new LinkedHashMap<>();
            claim.put("schoolId", String.valueOf(row.get("school_id")));
            claim.put("role", String.valueOf(row.get("role")));
            return claim;
        }).toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer).issuedAt(now).expiresAt(exp).subject(user.id().toString())
                .claim("email", user.email())
                .claim("name", user.fullName())
                .claim("platformRole", user.platformRole() == null ? "" : user.platformRole())
                .claim("memberships", memberships)
                .claim("sid", sessionId.toString())
                .claim("tokenVersion", user.tokenVersion())
                .claim("mustChangePassword", user.mustChangePassword())
                .build();
        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String access = encoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();

        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime refreshExpiresAt = OffsetDateTime.ofInstant(now.plus(refreshDays, ChronoUnit.DAYS), ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO identity.refresh_tokens(id,user_id,token_hash,expires_at,session_id,last_seen_at,user_agent,ip_hash) VALUES (?,?,?,?,?,NOW(),?,?)",
                UUID.randomUUID(), user.id(), sha256(refresh), refreshExpiresAt, sessionId,
                truncate(client.userAgent(), 255), client.ipHash());
        return new TokenResponse(access, refresh, exp, "Bearer", user.mustChangePassword());
    }

    private boolean isLoginBlocked(String identifierHash) {
        List<AttemptRow> rows = jdbc.query(
                "SELECT failed_count,window_started_at,locked_until FROM identity.login_attempts WHERE identifier_hash=? FOR UPDATE",
                (rs, i) -> new AttemptRow(rs.getInt("failed_count"), rs.getTimestamp("window_started_at").toInstant(),
                        rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant()), identifierHash);
        if (rows.isEmpty()) return false;
        Instant lockedUntil = rows.getFirst().lockedUntil();
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    private void recordFailedAttempt(String identifierHash) {
        Instant now = Instant.now();
        List<AttemptRow> rows = jdbc.query(
                "SELECT failed_count,window_started_at,locked_until FROM identity.login_attempts WHERE identifier_hash=? FOR UPDATE",
                (rs, i) -> new AttemptRow(rs.getInt("failed_count"), rs.getTimestamp("window_started_at").toInstant(),
                        rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant()), identifierHash);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO identity.login_attempts(identifier_hash,failed_count,window_started_at,updated_at) VALUES (?,1,NOW(),NOW())", identifierHash);
            return;
        }
        AttemptRow row = rows.getFirst();
        boolean resetWindow = row.windowStartedAt().isBefore(now.minus(attemptWindowMinutes, ChronoUnit.MINUTES));
        int count = resetWindow ? 1 : row.failedCount() + 1;
        Instant windowStart = resetWindow ? now : row.windowStartedAt();
        Instant lockedUntil = count >= maxFailedAttempts ? now.plus(lockMinutes, ChronoUnit.MINUTES) : null;
        jdbc.update("UPDATE identity.login_attempts SET failed_count=?,window_started_at=?,locked_until=?,updated_at=NOW() WHERE identifier_hash=?",
                count, Timestamp.from(windowStart), lockedUntil == null ? null : Timestamp.from(lockedUntil), identifierHash);
    }

    private int revokeAllInternal(UUID userId, String reason) {
        return jdbc.update("UPDATE identity.refresh_tokens SET revoked_at=NOW(),revoked_reason=? WHERE user_id=? AND revoked_at IS NULL", reason, userId);
    }

    private ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public ClientContext clientContext(String userAgent, String remoteAddress) {
        return new ClientContext(truncate(userAgent == null ? "" : userAgent, 255), sha256(issuer + ":" + (remoteAddress == null ? "" : remoteAddress)));
    }

    static String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Thiết bị không xác định";
        String ua = userAgent.toLowerCase(Locale.ROOT);
        String browser = ua.contains("edg/") ? "Edge" : ua.contains("chrome/") ? "Chrome" : ua.contains("firefox/") ? "Firefox" : ua.contains("safari/") ? "Safari" : "Trình duyệt";
        String os = ua.contains("windows") ? "Windows" : ua.contains("android") ? "Android" : (ua.contains("iphone") || ua.contains("ipad")) ? "iOS" : ua.contains("mac os") ? "macOS" : ua.contains("linux") ? "Linux" : "thiết bị";
        return browser + " · " + os;
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record UserRow(UUID id, String email, String passwordHash, String fullName, String platformRole,
                           String status, boolean mustChangePassword, long tokenVersion) {}
    private record RefreshRow(UUID tokenId, UUID userId, UUID sessionId, Instant revokedAt, String revokedReason,
                              Instant expiresAt, UserRow user) {}
    private record AttemptRow(int failedCount, Instant windowStartedAt, Instant lockedUntil) {}

    public record ClientContext(String userAgent, String ipHash) {}
    public record TokenResponse(String accessToken, String refreshToken, Instant expiresAt, String tokenType,
                                boolean mustChangePassword) {}
    public record SessionView(UUID sessionId, Instant createdAt, Instant lastSeenAt, Instant expiresAt,
                              boolean active, boolean current, String deviceLabel) {}
}
