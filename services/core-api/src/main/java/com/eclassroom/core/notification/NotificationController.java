package com.eclassroom.core.notification;

import com.eclassroom.core.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** Backward-compatible endpoint used by the current web client. */
    @GetMapping
    public List<Map<String, Object>> feed(Authentication authentication,
                                          @RequestParam(defaultValue = "50") int limit) {
        return service.feed(CurrentUser.id(authentication), limit);
    }

    @GetMapping("/page")
    public NotificationService.NotificationPage page(
            Authentication authentication,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(required = false) OffsetDateTime beforeCreatedAt,
            @RequestParam(required = false) UUID beforeId) {
        return service.page(
                CurrentUser.id(authentication), schoolId, category, unreadOnly, limit, beforeCreatedAt, beforeId);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(Authentication authentication,
                                          @RequestParam(required = false) UUID schoolId) {
        return Map.of("count", service.unreadCount(CurrentUser.id(authentication), schoolId));
    }

    @PostMapping("/{id}/read")
    public void read(Authentication authentication, @PathVariable UUID id) {
        service.markRead(CurrentUser.id(authentication), id);
    }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll(Authentication authentication,
                                         @RequestParam(required = false) UUID schoolId) {
        return Map.of("updated", service.markAllRead(CurrentUser.id(authentication), schoolId));
    }

    @GetMapping("/preferences")
    public List<NotificationService.PreferenceView> preferences(
            Authentication authentication,
            @RequestParam UUID schoolId) {
        return service.preferences(CurrentUser.id(authentication), schoolId);
    }

    @PutMapping("/preferences/{category}")
    public NotificationService.PreferenceView preference(
            Authentication authentication,
            @RequestParam UUID schoolId,
            @PathVariable String category,
            @RequestBody PreferenceRequest request) {
        return service.updatePreference(
                CurrentUser.id(authentication),
                schoolId,
                category,
                request.inAppEnabled(),
                request.realtimeEnabled());
    }

    public record PreferenceRequest(boolean inAppEnabled, boolean realtimeEnabled) {}
}
