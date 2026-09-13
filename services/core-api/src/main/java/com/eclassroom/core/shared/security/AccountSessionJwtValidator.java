package com.eclassroom.core.shared.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AccountSessionJwtValidator implements OAuth2TokenValidator<Jwt> {
    private final JdbcTemplate jdbc;

    public AccountSessionJwtValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            UUID userId = UUID.fromString(jwt.getSubject());
            String sid = jwt.getClaimAsString("sid");
            Number version = jwt.getClaim("tokenVersion");
            if (sid == null || version == null) return failure();
            Boolean valid = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM identity.users u WHERE u.id=? AND u.status='ACTIVE' AND u.token_version=? " +
                            "AND EXISTS (SELECT 1 FROM identity.refresh_tokens r WHERE r.user_id=u.id AND r.session_id=? " +
                            "AND r.revoked_at IS NULL AND r.expires_at>NOW()))",
                    Boolean.class,
                    userId, version.longValue(), UUID.fromString(sid));
            return Boolean.TRUE.equals(valid) ? OAuth2TokenValidatorResult.success() : failure();
        } catch (Exception ignored) {
            return failure();
        }
    }

    private OAuth2TokenValidatorResult failure() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Inactive account or session", null));
    }
}
