package com.eclassroom.core.audit;

import com.eclassroom.core.identity.AccessService;import com.eclassroom.core.shared.security.CurrentUser;import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/v1/schools/{schoolId}/audit") public class AuditController{private final AuditService audit;private final AccessService access;public AuditController(AuditService audit,AccessService access){this.audit=audit;this.access=access;}@GetMapping("/{type}/{id}")public List<Map<String,Object>> entity(@PathVariable UUID schoolId,@PathVariable String type,@PathVariable UUID id,Authentication a){access.requireAnyRole(schoolId,CurrentUser.id(a),"SCHOOL_ADMIN");return audit.entity(schoolId,type,id);}}
