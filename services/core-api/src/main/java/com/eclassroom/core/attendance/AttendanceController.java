package com.eclassroom.core.attendance;

import com.eclassroom.core.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class AttendanceController {
    private final AttendanceService attendance;private final LeaveRequestService leave;
    public AttendanceController(AttendanceService attendance,LeaveRequestService leave){this.attendance=attendance;this.leave=leave;}
    @PostMapping("/schools/{schoolId}/attendance/sessions") public Map<String,UUID> create(@PathVariable UUID schoolId,@RequestBody CreateSession r,Authentication a){return Map.of("id",attendance.create(schoolId,r.teachingAssignmentId(),r.date(),r.period(),CurrentUser.id(a)));}
    @PutMapping("/attendance/sessions/{id}/records") public Map<String,Long> save(@PathVariable UUID id,@RequestBody SaveRecords r,Authentication a){return Map.of("version",attendance.save(id,r.version(),r.records(),CurrentUser.id(a)));}
    @PostMapping("/attendance/sessions/{id}/submit") public void submit(@PathVariable UUID id,Authentication a){attendance.submit(id,CurrentUser.id(a));}
    @PostMapping("/attendance/sessions/{id}/lock") public void lock(@PathVariable UUID id,Authentication a){attendance.lock(id,CurrentUser.id(a));}
    @GetMapping("/attendance/sessions/{id}") public Map<String,Object> detail(@PathVariable UUID id,Authentication a){return attendance.detail(id,CurrentUser.id(a));}
    @GetMapping("/schools/{schoolId}/classrooms/{classroomId}/attendance") public List<Map<String,Object>> sessions(@PathVariable UUID schoolId,@PathVariable UUID classroomId,@RequestParam LocalDate date,Authentication a){return attendance.sessions(schoolId,classroomId,date,CurrentUser.id(a));}
    @GetMapping("/schools/{schoolId}/students/{studentId}/attendance") public List<Map<String,Object>> student(@PathVariable UUID schoolId,@PathVariable UUID studentId,Authentication a){return attendance.student(schoolId,studentId,CurrentUser.id(a));}
    @PostMapping("/schools/{schoolId}/students/{studentId}/leave-requests") public Map<String,UUID> leave(@PathVariable UUID schoolId,@PathVariable UUID studentId,@RequestBody LeaveRequest r,Authentication a){return Map.of("id",leave.create(schoolId,studentId,CurrentUser.id(a),r.startDate(),r.endDate(),r.reason()));}
    @GetMapping("/schools/{schoolId}/students/{studentId}/leave-requests") public List<Map<String,Object>> studentLeave(@PathVariable UUID schoolId,@PathVariable UUID studentId,Authentication a){return leave.forStudent(schoolId,studentId,CurrentUser.id(a));}
    @GetMapping("/schools/{schoolId}/leave-requests/pending") public List<Map<String,Object>> pending(@PathVariable UUID schoolId,Authentication a){return leave.pending(schoolId,CurrentUser.id(a));}
    @PostMapping("/leave-requests/{id}/approve") public void approve(@PathVariable UUID id,Authentication a){leave.review(id,true,CurrentUser.id(a));}
    @PostMapping("/leave-requests/{id}/reject") public void reject(@PathVariable UUID id,Authentication a){leave.review(id,false,CurrentUser.id(a));}
    public record CreateSession(UUID teachingAssignmentId,LocalDate date,int period){} public record SaveRecords(long version,List<AttendanceService.RecordInput> records){} public record LeaveRequest(LocalDate startDate,LocalDate endDate,String reason){}
}
