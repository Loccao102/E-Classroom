package com.eclassroom.core.shared.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

public final class CurrentUser {
    private CurrentUser() {}

    public static UUID id(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    public static UUID sessionId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String sid = jwt.getToken().getClaimAsString("sid");
            if (sid != null && !sid.isBlank()) return UUID.fromString(sid);
        }
        return null;
    }

    public static boolean mustChangePassword(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            Boolean value = jwt.getToken().getClaim("mustChangePassword");
            return Boolean.TRUE.equals(value);
        }
        return false;
    }
}
