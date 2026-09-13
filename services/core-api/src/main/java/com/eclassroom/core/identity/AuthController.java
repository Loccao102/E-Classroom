package com.eclassroom.core.identity;

import com.eclassroom.core.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/auth/login")
    public AuthService.TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return auth.login(request.email(), request.password(), client(servletRequest));
    }

    @PostMapping("/auth/refresh")
    public AuthService.TokenResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest servletRequest) {
        return auth.refresh(request.refreshToken(), client(servletRequest));
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody(required = false) LogoutRequest request) {
        if (request != null) auth.logout(request.refreshToken());
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        return auth.me(CurrentUser.id(authentication));
    }

    private AuthService.ClientContext client(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String remoteAddress = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",", 2)[0].trim();
        return auth.clientContext(request.getHeader("User-Agent"), remoteAddress);
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record LogoutRequest(String refreshToken) {}
}
