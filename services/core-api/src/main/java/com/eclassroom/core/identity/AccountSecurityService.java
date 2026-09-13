package com.eclassroom.core.identity;

import com.eclassroom.core.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AccountSecurityService {
    private static final Set<String> MANAGED_ROLES = Set.of("TEACHER", "PARENT", "STUDENT");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AccessService access;
    private final AuthService auth;
    private final SecurityEventService events;
    private final SecureRandom random = new SecureRandom();

    public AccountSecurityService(JdbcTemplate jdbc,
                                  PasswordEncoder passwords,
                                  AccessService access,
                                  AuthService auth,
                                  SecurityEventService events) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.access = access;
        this.auth = auth;
        this.events = events;
    }

    @Transactional
    public TemporaryPasswordResult temporaryPassword(UUID schoolId, UUID actor, UUID targetUserId) {
        access.requireAnyRole(schoolId, actor, "SCHOOL_ADMIN");
        ManagedAccount target = requireManagedAccount(schoolId, targetUserId);
        String temporaryPassword = generateTemporaryPassword();
        jdbc.update(
                "UPDATE identity.users SET password_hash=?,must_change_password=TRUE,password_changed_at=NOW()," +
                        "token_version=token_version+1,updated_at=NOW() WHERE id=?",
                passwords.encode(temporaryPassword), targetUserId);
        auth.revokeAll(targetUserId, "ADMIN_PASSWORD_RESET");
        events.record(actor, schoolId, "ADMIN_PASSWORD_RESET", "SUCCESS", null,
                Map.of("targetUserId", targetUserId.toString(), "targetRole", target.role(), "accountStatus", target.status()));
        return new TemporaryPasswordResult(targetUserId, temporaryPassword, true, target.status());
    }

    @Transactional
    public AccountStatusResult status(UUID schoolId, UUID actor, UUID targetUserId, String requestedStatus) {
        access.requireAnyRole(schoolId, actor, "SCHOOL_ADMIN");
        if (actor.equals(targetUserId)) {
            throw ApiException.badRequest("SELF_STATUS_CHANGE", "You cannot disable your own account");
        }
        ManagedAccount target = requireManagedAccount(schoolId, targetUserId);
        String status = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase();
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) {
            throw ApiException.badRequest("INVALID_ACCOUNT_STATUS", "Account status must be ACTIVE or DISABLED");
        }
        jdbc.update(
                "UPDATE identity.users SET status=?,token_version=token_version+1,updated_at=NOW() WHERE id=?",
                status, targetUserId);
        auth.revokeAll(targetUserId, "ACCOUNT_STATUS_CHANGED");
        events.record(actor, schoolId, "ACCOUNT_STATUS_CHANGE", "SUCCESS", null,
                Map.of("targetUserId", targetUserId.toString(), "targetRole", target.role(), "status", status));
        return new AccountStatusResult(targetUserId, status);
    }

    private ManagedAccount requireManagedAccount(UUID schoolId, UUID targetUserId) {
        List<ManagedAccount> rows = jdbc.query(
                "SELECT u.id,u.platform_role,u.status,m.role,(SELECT COUNT(*) FROM identity.school_memberships x WHERE x.user_id=u.id) membership_count " +
                        "FROM identity.users u JOIN identity.school_memberships m ON m.user_id=u.id " +
                        "WHERE u.id=? AND m.school_id=?",
                (rs, i) -> new ManagedAccount(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("role"),
                        rs.getString("platform_role"),
                        rs.getString("status"),
                        rs.getInt("membership_count")),
                targetUserId, schoolId);
        if (rows.isEmpty()) throw ApiException.notFound("Managed school account was not found");
        ManagedAccount target = rows.getFirst();
        if (!MANAGED_ROLES.contains(target.role()) || (target.platformRole() != null && !target.platformRole().isBlank()) || target.membershipCount() != 1) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_MANAGED", "This account cannot be managed by the school administrator");
        }
        return target;
    }

    private String generateTemporaryPassword() {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return "Tmp9-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record ManagedAccount(UUID id, String role, String platformRole, String status, int membershipCount) {}

    public record TemporaryPasswordResult(UUID userId, String temporaryPassword, boolean mustChangePassword, String accountStatus) {}
    public record AccountStatusResult(UUID userId, String status) {}
}
