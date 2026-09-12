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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String issuer;
    private final long accessMinutes;
    private final long refreshDays;
    private final SecureRandom random = new SecureRandom();

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwords, JwtEncoder encoder,
                       @Value("${app.jwt.issuer}") String issuer,
                       @Value("${app.jwt.access-minutes}") long accessMinutes,
                       @Value("${app.jwt.refresh-days}") long refreshDays) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.encoder = encoder;
        this.issuer = issuer;
        this.accessMinutes = accessMinutes;
        this.refreshDays = refreshDays;
    }

    @Transactional
    public TokenResponse login(String email, String password) {
        List<UserRow> users = jdbc.query(
                "SELECT id,email,password_hash,full_name,platform_role FROM identity.users WHERE lower(email)=lower(?) AND status='ACTIVE'",
                (rs, i) -> new UserRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("full_name"),
                        rs.getString("platform_role")),
                email);
        if (users.isEmpty() || !passwords.matches(password, users.getFirst().passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }
        return issue(users.getFirst());
    }

    @Transactional
    public TokenResponse refresh(String rawToken) {
        String hash = sha256(rawToken);
        List<UserRow> users = jdbc.query(
                "SELECT u.id,u.email,u.password_hash,u.full_name,u.platform_role FROM identity.refresh_tokens r JOIN identity.users u ON u.id=r.user_id WHERE r.token_hash=? AND r.revoked_at IS NULL AND r.expires_at>NOW() AND u.status='ACTIVE' FOR UPDATE",
                (rs, i) -> new UserRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("full_name"),
                        rs.getString("platform_role")),
                hash);
        if (users.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
        }
        jdbc.update("UPDATE identity.refresh_tokens SET revoked_at=NOW() WHERE token_hash=?", hash);
        return issue(users.getFirst());
    }

    public Map<String, Object> me(UUID userId) {
        Map<String, Object> user = jdbc.queryForMap(
                "SELECT id,email,full_name,COALESCE(platform_role,'') platform_role,status FROM identity.users WHERE id=?",
                userId);
        List<Map<String, Object>> memberships = jdbc.queryForList(
                "SELECT m.school_id,s.code school_code,s.name school_name,m.role FROM identity.school_memberships m JOIN school.schools s ON s.id=m.school_id WHERE m.user_id=? AND m.status='ACTIVE' ORDER BY s.name,m.role",
                userId);
        return Map.of("user", user, "memberships", memberships);
    }

    private TokenResponse issue(UserRow user) {
        Instant now = Instant.now();
        Instant exp = now.plus(accessMinutes, ChronoUnit.MINUTES);
        List<Map<String, Object>> rawMemberships = jdbc.queryForList(
                "SELECT school_id,role FROM identity.school_memberships WHERE user_id=? AND status='ACTIVE'",
                user.id());
        List<Map<String, String>> memberships = rawMemberships.stream().map(row -> {
            Map<String, String> claim = new LinkedHashMap<>();
            claim.put("schoolId", String.valueOf(row.get("school_id")));
            claim.put("role", String.valueOf(row.get("role")));
            return claim;
        }).toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(exp)
                .subject(user.id().toString())
                .claim("email", user.email())
                .claim("name", user.fullName())
                .claim("platformRole", user.platformRole() == null ? "" : user.platformRole())
                .claim("memberships", memberships)
                .build();
        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String access = encoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();

        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime refreshExpiresAt = OffsetDateTime.ofInstant(
                now.plus(refreshDays, ChronoUnit.DAYS), ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO identity.refresh_tokens(id,user_id,token_hash,expires_at) VALUES (?,?,?,?)",
                UUID.randomUUID(),
                user.id(),
                sha256(refresh),
                refreshExpiresAt);
        return new TokenResponse(access, refresh, exp, "Bearer");
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record UserRow(UUID id, String email, String passwordHash, String fullName, String platformRole) {}

    public record TokenResponse(String accessToken, String refreshToken, Instant expiresAt, String tokenType) {}
}
