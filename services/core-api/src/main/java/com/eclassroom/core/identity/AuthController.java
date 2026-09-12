package com.eclassroom.core.identity;

import com.eclassroom.core.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    @PostMapping("/auth/login") public AuthService.TokenResponse login(@Valid @RequestBody LoginRequest r) { return auth.login(r.email(), r.password()); }
    @PostMapping("/auth/refresh") public AuthService.TokenResponse refresh(@Valid @RequestBody RefreshRequest r) { return auth.refresh(r.refreshToken()); }
    @GetMapping("/me") public Map<String,Object> me(Authentication a) { return auth.me(CurrentUser.id(a)); }
    public record LoginRequest(@Email @NotBlank String email,@NotBlank String password) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
}
