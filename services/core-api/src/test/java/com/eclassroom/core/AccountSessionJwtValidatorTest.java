package com.eclassroom.core;

import com.eclassroom.core.shared.security.AccountSessionJwtValidator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountSessionJwtValidatorTest {
    @Test
    void acceptsOnlyAccountVersionWithAnActiveSession() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AccountSessionJwtValidator validator = new AccountSessionJwtValidator(jdbc);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Jwt jwt = jwt(userId, sessionId, 4L);

        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any())).thenReturn(true);
        assertFalse(validator.validate(jwt).hasErrors());

        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any())).thenReturn(false);
        assertTrue(validator.validate(jwt).hasErrors());
    }

    @Test
    void rejectsAccessTokensWithoutSessionClaims() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AccountSessionJwtValidator validator = new AccountSessionJwtValidator(jdbc);
        Instant now = Instant.now();
        Jwt legacy = Jwt.withTokenValue("legacy")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .build();

        assertTrue(validator.validate(legacy).hasErrors());
    }

    private Jwt jwt(UUID userId, UUID sessionId, long version) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("access")
                .header("alg", "HS256")
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .claim("tokenVersion", version)
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .build();
    }
}
