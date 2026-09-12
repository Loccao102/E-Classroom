package com.eclassroom.core.notification;

import com.eclassroom.core.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List; import java.util.Map; import java.util.UUID;

@RestController @RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service; public NotificationController(NotificationService service){this.service=service;}
    @GetMapping public List<Map<String,Object>> feed(Authentication a,@RequestParam(defaultValue="50") int limit){return service.feed(CurrentUser.id(a),limit);}
    @PostMapping("/{id}/read") public void read(Authentication a,@PathVariable UUID id){service.markRead(CurrentUser.id(a),id);}
}
