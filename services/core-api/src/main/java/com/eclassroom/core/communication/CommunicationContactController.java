package com.eclassroom.core.communication;

import com.eclassroom.core.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class CommunicationContactController {
    private final CommunicationContactService service;

    public CommunicationContactController(CommunicationContactService service) {
        this.service = service;
    }

    @GetMapping("/schools/{schoolId}/communication/contacts")
    public List<Map<String, Object>> contacts(@PathVariable UUID schoolId, Authentication authentication) {
        return service.contacts(schoolId, CurrentUser.id(authentication));
    }
}
