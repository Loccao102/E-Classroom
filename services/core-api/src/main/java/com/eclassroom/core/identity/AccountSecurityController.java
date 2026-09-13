package com.eclassroom.core.identity;

import com.eclassroom.core.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AccountSecurityController {
    private final AuthService auth;
    private final AccountSecurityService accounts;

    public AccountSecurityController(AuthService auth, AccountSecurityService accounts) {
        this.auth = auth;
        this.accounts = accounts;
    }

    @GetMapping("/account/sessions")
    public List<AuthService.SessionView> sessions(Authentication authentication) {
        return auth.sessions(CurrentUser.id(authentication), CurrentUser.sessionId(authentication));
    }

    @DeleteMapping("/account/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@PathVariable UUID sessionId, Authentication authentication) {
        auth.revokeSession(CurrentUser.id(authentication), sessionId, "USER_REVOKED");
    }

    @PostMapping("/account/sessions/revoke-all")
    public Map<String, Integer> revokeAll(Authentication authentication) {
        return Map.of("revoked", auth.revokeAll(CurrentUser.id(authentication), "USER_REVOKE_ALL"));
    }

    @PutMapping("/account/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        auth.changePassword(CurrentUser.id(authentication), request.currentPassword(), request.newPassword());
    }

    @PostMapping("/schools/{schoolId}/accounts/{userId}/temporary-password")
    public AccountSecurityService.TemporaryPasswordResult temporaryPassword(@PathVariable UUID schoolId,
                                                                              @PathVariable UUID userId,
                                                                              Authentication authentication) {
        return accounts.temporaryPassword(schoolId, CurrentUser.id(authentication), userId);
    }

    @PutMapping("/schools/{schoolId}/accounts/{userId}/status")
    public AccountSecurityService.AccountStatusResult accountStatus(@PathVariable UUID schoolId,
                                                                      @PathVariable UUID userId,
                                                                      @RequestBody AccountStatusRequest request,
                                                                      Authentication authentication) {
        return accounts.status(schoolId, CurrentUser.id(authentication), userId, request.status());
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
    public record AccountStatusRequest(String status) {}
}
