package com.eclassroom.core.communication;

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
@RequestMapping("/api/v1")
public class CommunicationController {
    private final CommunicationService service;

    public CommunicationController(CommunicationService service) {
        this.service = service;
    }

    @PostMapping("/schools/{schoolId}/announcements")
    public Map<String, UUID> announce(@PathVariable UUID schoolId,
                                      @RequestBody Announcement request,
                                      Authentication authentication) {
        return Map.of("id", service.announce(
                schoolId,
                request.title(),
                request.body(),
                request.targetType(),
                request.targetId(),
                request.expiresAt(),
                Boolean.TRUE.equals(request.pinned()),
                CurrentUser.id(authentication)));
    }

    @GetMapping("/schools/{schoolId}/announcements")
    public List<Map<String, Object>> announcements(@PathVariable UUID schoolId, Authentication authentication) {
        return service.announcements(schoolId, CurrentUser.id(authentication));
    }

    @PostMapping("/schools/{schoolId}/students/{studentId}/comments")
    public Map<String, UUID> comment(@PathVariable UUID schoolId,
                                     @PathVariable UUID studentId,
                                     @RequestBody Comment request,
                                     Authentication authentication) {
        return Map.of("id", service.comment(
                schoolId,
                studentId,
                request.body(),
                request.visibility(),
                CurrentUser.id(authentication)));
    }

    @GetMapping("/schools/{schoolId}/students/{studentId}/comments")
    public List<Map<String, Object>> comments(@PathVariable UUID schoolId,
                                               @PathVariable UUID studentId,
                                               Authentication authentication) {
        return service.comments(schoolId, studentId, CurrentUser.id(authentication));
    }

    @PostMapping("/schools/{schoolId}/conversations")
    public Map<String, UUID> conversation(@PathVariable UUID schoolId,
                                          @RequestBody Conversation request,
                                          Authentication authentication) {
        return Map.of("id", service.conversation(
                schoolId, request.subject(), request.participantIds(), CurrentUser.id(authentication)));
    }

    @GetMapping("/schools/{schoolId}/conversations")
    public List<Map<String, Object>> conversations(@PathVariable UUID schoolId, Authentication authentication) {
        return service.conversations(schoolId, CurrentUser.id(authentication));
    }

    /** Backward-compatible chronological message list, bounded to the newest 100 messages. */
    @GetMapping("/conversations/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable UUID id, Authentication authentication) {
        return service.messages(id, CurrentUser.id(authentication));
    }

    @GetMapping("/conversations/{id}/messages/page")
    public CommunicationService.MessagePage messagePage(
            @PathVariable UUID id,
            Authentication authentication,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) OffsetDateTime beforeCreatedAt,
            @RequestParam(required = false) UUID beforeId) {
        return service.messagePage(id, CurrentUser.id(authentication), limit, beforeCreatedAt, beforeId);
    }

    @PostMapping("/conversations/{id}/messages")
    public Map<String, UUID> send(@PathVariable UUID id,
                                  @RequestBody Message request,
                                  Authentication authentication) {
        return Map.of("id", service.send(id, request.body(), CurrentUser.id(authentication)));
    }

    @PostMapping("/conversations/{id}/read")
    public void read(@PathVariable UUID id, Authentication authentication) {
        service.markConversationRead(id, CurrentUser.id(authentication));
    }

    @PutMapping("/conversations/{id}/mute")
    public void mute(@PathVariable UUID id,
                     @RequestParam boolean muted,
                     Authentication authentication) {
        service.setMuted(id, CurrentUser.id(authentication), muted);
    }

    public record Announcement(
            String title,
            String body,
            String targetType,
            UUID targetId,
            OffsetDateTime expiresAt,
            Boolean pinned) {}

    public record Comment(String body, String visibility) {}
    public record Conversation(String subject, List<UUID> participantIds) {}
    public record Message(String body) {}
}
