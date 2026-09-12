package com.eclassroom.core.communication;

import com.eclassroom.core.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;import java.util.Map;import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class CommunicationController {
    private final CommunicationService service;public CommunicationController(CommunicationService service){this.service=service;}
    @PostMapping("/schools/{schoolId}/announcements") public Map<String,UUID> announce(@PathVariable UUID schoolId,@RequestBody Announcement r,Authentication a){return Map.of("id",service.announce(schoolId,r.title(),r.body(),r.targetType(),r.targetId(),CurrentUser.id(a)));}
    @GetMapping("/schools/{schoolId}/announcements") public List<Map<String,Object>> announcements(@PathVariable UUID schoolId,Authentication a){return service.announcements(schoolId,CurrentUser.id(a));}
    @PostMapping("/schools/{schoolId}/students/{studentId}/comments") public Map<String,UUID> comment(@PathVariable UUID schoolId,@PathVariable UUID studentId,@RequestBody Comment r,Authentication a){return Map.of("id",service.comment(schoolId,studentId,r.body(),r.visibility()==null?"GUARDIAN_AND_STUDENT":r.visibility(),CurrentUser.id(a)));}
    @GetMapping("/schools/{schoolId}/students/{studentId}/comments") public List<Map<String,Object>> comments(@PathVariable UUID schoolId,@PathVariable UUID studentId,Authentication a){return service.comments(schoolId,studentId,CurrentUser.id(a));}
    @PostMapping("/schools/{schoolId}/conversations") public Map<String,UUID> conversation(@PathVariable UUID schoolId,@RequestBody Conversation r,Authentication a){return Map.of("id",service.conversation(schoolId,r.subject(),r.participantIds(),CurrentUser.id(a)));}
    @GetMapping("/schools/{schoolId}/conversations") public List<Map<String,Object>> conversations(@PathVariable UUID schoolId,Authentication a){return service.conversations(schoolId,CurrentUser.id(a));}
    @GetMapping("/conversations/{id}/messages") public List<Map<String,Object>> messages(@PathVariable UUID id,Authentication a){return service.messages(id,CurrentUser.id(a));}
    @PostMapping("/conversations/{id}/messages") public Map<String,UUID> send(@PathVariable UUID id,@RequestBody Message r,Authentication a){return Map.of("id",service.send(id,r.body(),CurrentUser.id(a)));}
    public record Announcement(String title,String body,String targetType,UUID targetId){} public record Comment(String body,String visibility){} public record Conversation(String subject,List<UUID> participantIds){} public record Message(String body){}
}
