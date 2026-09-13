package com.eclassroom.core.communication;

import com.eclassroom.core.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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
public class ParentMeetingController {
    private final ParentMeetingService service;

    public ParentMeetingController(ParentMeetingService service) {
        this.service = service;
    }

    @PostMapping("/schools/{schoolId}/meetings")
    public Map<String, UUID> create(@PathVariable UUID schoolId,
                                    @RequestBody CreateMeeting request,
                                    Authentication authentication) {
        List<ParentMeetingService.SlotCommand> slots = request.slots() == null
                ? List.of()
                : request.slots().stream()
                        .map(slot -> new ParentMeetingService.SlotCommand(slot.startsAt(), slot.endsAt()))
                        .toList();
        UUID id = service.create(schoolId,
                new ParentMeetingService.CreateCommand(
                        request.scopeType(), request.scopeId(), request.title(), request.agenda(), request.note(),
                        request.location(), request.startsAt(), request.endsAt(),
                        Boolean.TRUE.equals(request.includeStudents()), slots),
                CurrentUser.id(authentication));
        return Map.of("id", id);
    }

    @GetMapping("/schools/{schoolId}/meetings")
    public List<ParentMeetingService.MeetingView> list(@PathVariable UUID schoolId,
                                                        Authentication authentication) {
        return service.list(schoolId, CurrentUser.id(authentication));
    }

    @GetMapping("/meetings/{meetingId}")
    public ParentMeetingService.MeetingDetails details(@PathVariable UUID meetingId,
                                                        Authentication authentication) {
        return service.details(meetingId, CurrentUser.id(authentication));
    }

    @PutMapping("/meetings/{meetingId}/response")
    public ParentMeetingService.InviteeView respond(@PathVariable UUID meetingId,
                                                     @RequestBody Respond request,
                                                     Authentication authentication) {
        return service.respond(meetingId, request.studentId(), request.response(), request.version(),
                CurrentUser.id(authentication));
    }

    @PutMapping("/meetings/{meetingId}/slots/{slotId}/booking")
    public ParentMeetingService.SlotView book(@PathVariable UUID meetingId,
                                               @PathVariable UUID slotId,
                                               @RequestBody SlotBooking request,
                                               Authentication authentication) {
        return service.bookSlot(meetingId, slotId, request.studentId(), request.version(),
                CurrentUser.id(authentication));
    }

    @DeleteMapping("/meetings/{meetingId}/slots/{slotId}/booking")
    public ParentMeetingService.SlotView cancelBooking(@PathVariable UUID meetingId,
                                                        @PathVariable UUID slotId,
                                                        @RequestParam UUID studentId,
                                                        @RequestParam long version,
                                                        Authentication authentication) {
        return service.cancelBooking(meetingId, slotId, studentId, version, CurrentUser.id(authentication));
    }

    @PutMapping("/meetings/{meetingId}/invitees/{inviteeId}/attendance")
    public ParentMeetingService.InviteeView attendance(@PathVariable UUID meetingId,
                                                        @PathVariable UUID inviteeId,
                                                        @RequestBody Attendance request,
                                                        Authentication authentication) {
        return service.recordAttendance(meetingId, inviteeId, request.attendance(), request.version(),
                CurrentUser.id(authentication));
    }

    @PostMapping("/meetings/{meetingId}/outcomes")
    public Map<String, UUID> outcome(@PathVariable UUID meetingId,
                                     @RequestBody Outcome request,
                                     Authentication authentication) {
        UUID id = service.addOutcome(meetingId, request.studentId(), request.body(), request.visibility(),
                CurrentUser.id(authentication));
        return Map.of("id", id);
    }

    @PostMapping("/meetings/{meetingId}/reminders")
    public Map<String, Integer> remind(@PathVariable UUID meetingId, Authentication authentication) {
        return Map.of("reminded", service.remind(meetingId, CurrentUser.id(authentication)));
    }

    @PostMapping("/meetings/{meetingId}/cancel")
    public ParentMeetingService.MeetingView cancel(@PathVariable UUID meetingId,
                                                    @RequestBody Version request,
                                                    Authentication authentication) {
        return service.cancel(meetingId, request.version(), CurrentUser.id(authentication));
    }

    @PostMapping("/meetings/{meetingId}/complete")
    public ParentMeetingService.MeetingView complete(@PathVariable UUID meetingId,
                                                      @RequestBody Version request,
                                                      Authentication authentication) {
        return service.complete(meetingId, request.version(), CurrentUser.id(authentication));
    }

    public record CreateMeeting(String scopeType, UUID scopeId, String title, String agenda, String note,
                                String location, OffsetDateTime startsAt, OffsetDateTime endsAt,
                                Boolean includeStudents, List<Slot> slots) {}
    public record Slot(OffsetDateTime startsAt, OffsetDateTime endsAt) {}
    public record Respond(UUID studentId, String response, long version) {}
    public record SlotBooking(UUID studentId, long version) {}
    public record Attendance(String attendance, long version) {}
    public record Outcome(UUID studentId, String body, String visibility) {}
    public record Version(long version) {}
}
