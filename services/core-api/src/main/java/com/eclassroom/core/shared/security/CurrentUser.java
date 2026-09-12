package com.eclassroom.core.shared.security;

import org.springframework.security.core.Authentication;
import java.util.UUID;

public final class CurrentUser {
    private CurrentUser() {}
    public static UUID id(Authentication authentication) { return UUID.fromString(authentication.getName()); }
}
