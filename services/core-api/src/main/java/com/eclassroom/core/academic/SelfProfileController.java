package com.eclassroom.core.academic;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.shared.api.ApiException;
import com.eclassroom.core.shared.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/schools/{schoolId}")
public class SelfProfileController {
    private final JdbcTemplate jdbc; private final AccessService access;
    public SelfProfileController(JdbcTemplate jdbc,AccessService access){this.jdbc=jdbc;this.access=access;}
    @GetMapping("/me/student") public Map<String,Object> student(@PathVariable UUID schoolId,Authentication a){UUID userId=CurrentUser.id(a);access.requireAnyRole(schoolId,userId,"STUDENT");List<Map<String,Object>> rows=jdbc.queryForList("SELECT s.id,s.student_code,s.full_name,s.date_of_birth,s.gender,s.status,c.id classroom_id,c.name classroom_name FROM academic.students s LEFT JOIN academic.class_enrollments e ON e.student_id=s.id AND e.status='ACTIVE' LEFT JOIN academic.classrooms c ON c.id=e.classroom_id WHERE s.school_id=? AND s.user_id=?",schoolId,userId);if(rows.isEmpty())throw ApiException.notFound("Student profile not linked to this account");return rows.getFirst();}
    @GetMapping("/grade-levels") public List<Map<String,Object>> gradeLevels(@PathVariable UUID schoolId,Authentication a){access.requireMembership(schoolId,CurrentUser.id(a));return jdbc.queryForList("SELECT id,name,sort_order FROM academic.grade_levels WHERE school_id=? ORDER BY sort_order,name",schoolId);}
}
