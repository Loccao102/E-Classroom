package com.eclassroom.core.academic;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/schools/{schoolId}")
public class AcademicController {
    private static final Set<String> ADMIN_ONLY_COLLECTIONS = Set.of("teachers", "students", "guardians", "teaching-assignments");
    private final AcademicService service;
    private final AccessService access;

    public AcademicController(AcademicService service, AccessService access) { this.service = service; this.access = access; }
    private void admin(UUID schoolId, Authentication a) { access.requireAnyRole(schoolId, CurrentUser.id(a), "SCHOOL_ADMIN"); }

    @GetMapping("/{resource:academic-years|semesters|subjects|teachers|students|guardians|classrooms|teaching-assignments|timetable}")
    public List<Map<String,Object>> list(@PathVariable UUID schoolId,@PathVariable String resource,Authentication a){
        UUID actor = CurrentUser.id(a);
        if (ADMIN_ONLY_COLLECTIONS.contains(resource)) access.requireAnyRole(schoolId, actor, "SCHOOL_ADMIN");
        else access.requireMembership(schoolId, actor);
        return service.list(schoolId,resource);
    }

    @PostMapping("/academic-years") public Map<String,UUID> year(@PathVariable UUID schoolId,@RequestBody Year r,Authentication a){admin(schoolId,a);return Map.of("id",service.academicYear(schoolId,r.name(),r.startDate(),r.endDate()));}
    @PostMapping("/semesters") public Map<String,UUID> semester(@PathVariable UUID schoolId,@RequestBody Semester r,Authentication a){admin(schoolId,a);return Map.of("id",service.semester(schoolId,r.academicYearId(),r.name(),r.startDate(),r.endDate()));}
    @PostMapping("/grade-levels") public Map<String,UUID> grade(@PathVariable UUID schoolId,@RequestBody Grade r,Authentication a){admin(schoolId,a);return Map.of("id",service.gradeLevel(schoolId,r.name(),r.sortOrder()));}
    @PostMapping("/subjects") public Map<String,UUID> subject(@PathVariable UUID schoolId,@RequestBody Subject r,Authentication a){admin(schoolId,a);return Map.of("id",service.subject(schoolId,r.code(),r.name()));}
    @PostMapping("/teachers") public Map<String,UUID> teacher(@PathVariable UUID schoolId,@Valid @RequestBody Teacher r,Authentication a){admin(schoolId,a);return Map.of("id",service.teacher(schoolId,r.code(),r.fullName(),r.email(),r.phone(),r.password()));}
    @PostMapping("/students") public Map<String,UUID> student(@PathVariable UUID schoolId,@Valid @RequestBody Student r,Authentication a){admin(schoolId,a);return Map.of("id",service.student(schoolId,r.code(),r.fullName(),r.dateOfBirth(),r.gender(),r.email(),r.password()));}
    @PostMapping("/guardians") public Map<String,UUID> guardian(@PathVariable UUID schoolId,@Valid @RequestBody Guardian r,Authentication a){admin(schoolId,a);return Map.of("id",service.guardian(schoolId,r.fullName(),r.email(),r.phone(),r.password()));}
    @PostMapping("/student-guardians") public void link(@PathVariable UUID schoolId,@RequestBody LinkGuardian r,Authentication a){admin(schoolId,a);service.linkGuardian(schoolId,r.studentId(),r.guardianId(),r.relationship(),r.primaryContact());}
    @PostMapping("/classrooms") public Map<String,UUID> classroom(@PathVariable UUID schoolId,@RequestBody Classroom r,Authentication a){admin(schoolId,a);return Map.of("id",service.classroom(schoolId,r.academicYearId(),r.gradeLevelId(),r.code(),r.name(),r.homeroomTeacherId()));}
    @PostMapping("/enrollments") public Map<String,UUID> enroll(@PathVariable UUID schoolId,@RequestBody Enrollment r,Authentication a){admin(schoolId,a);return Map.of("id",service.enroll(schoolId,r.classroomId(),r.studentId(),r.startDate()));}
    @PostMapping("/teaching-assignments") public Map<String,UUID> assignment(@PathVariable UUID schoolId,@RequestBody Assignment r,Authentication a){admin(schoolId,a);return Map.of("id",service.teachingAssignment(schoolId,r.teacherId(),r.classroomId(),r.subjectId(),r.semesterId()));}
    @PostMapping("/timetable") public Map<String,UUID> timetable(@PathVariable UUID schoolId,@RequestBody Timetable r,Authentication a){admin(schoolId,a);return Map.of("id",service.timetable(schoolId,r.teachingAssignmentId(),r.weekday(),r.period(),r.room(),r.validFrom(),r.validTo()));}
    @GetMapping("/me/children") public List<Map<String,Object>> children(@PathVariable UUID schoolId,Authentication a){access.requireAnyRole(schoolId,CurrentUser.id(a),"PARENT");return service.myChildren(schoolId,CurrentUser.id(a));}
    @GetMapping("/me/teaching-assignments") public List<Map<String,Object>> classes(@PathVariable UUID schoolId,Authentication a){access.requireAnyRole(schoolId,CurrentUser.id(a),"TEACHER","SCHOOL_ADMIN");return service.myClasses(schoolId,CurrentUser.id(a));}
    @GetMapping("/classrooms/{classroomId}/roster") public List<Map<String,Object>> roster(@PathVariable UUID schoolId,@PathVariable UUID classroomId,Authentication a){access.requireClassTeacherOrAdmin(schoolId,CurrentUser.id(a),classroomId);return service.roster(schoolId,classroomId);}

    public record Year(String name,LocalDate startDate,LocalDate endDate){}
    public record Semester(UUID academicYearId,String name,LocalDate startDate,LocalDate endDate){}
    public record Grade(String name,int sortOrder){}
    public record Subject(String code,String name){}
    public record Teacher(@NotBlank String code,@NotBlank String fullName,@Email String email,String phone,@StrongPassword String password){}
    public record Student(@NotBlank String code,@NotBlank String fullName,LocalDate dateOfBirth,String gender,@Email String email,@StrongPassword String password){}
    public record Guardian(@NotBlank String fullName,@Email String email,String phone,@StrongPassword String password){}
    public record LinkGuardian(@NotNull UUID studentId,@NotNull UUID guardianId,@NotBlank String relationship,boolean primaryContact){}
    public record Classroom(UUID academicYearId,UUID gradeLevelId,String code,String name,UUID homeroomTeacherId){}
    public record Enrollment(UUID classroomId,UUID studentId,LocalDate startDate){}
    public record Assignment(UUID teacherId,UUID classroomId,UUID subjectId,UUID semesterId){}
    public record Timetable(UUID teachingAssignmentId,int weekday,int period,String room,LocalDate validFrom,LocalDate validTo){}

    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{10,128}$", message = "Password must be 10-128 characters and include upper-case, lower-case and a number")
    private @interface StrongPassword {}
}
