package com.eclassroom.core.attendance;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.notification.NotificationService;
import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
public class AttendanceService {
    private static final Set<String> STATUSES=Set.of("PRESENT","ABSENT","EXCUSED","LATE","EARLY_LEAVE");
    private final JdbcTemplate jdbc; private final AccessService access; private final NotificationService notifications;
    public AttendanceService(JdbcTemplate jdbc,AccessService access,NotificationService notifications){this.jdbc=jdbc;this.access=access;this.notifications=notifications;}

    @Transactional public UUID create(UUID schoolId,UUID assignmentId,LocalDate date,int period,UUID actor){access.requireTeacherAssignment(schoolId,actor,assignmentId);Map<String,Object> assignment=jdbc.queryForMap("SELECT classroom_id FROM academic.teaching_assignments WHERE id=? AND school_id=? AND status='ACTIVE'",assignmentId,schoolId);UUID classroomId=(UUID)assignment.get("classroom_id");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO attendance.sessions(id,school_id,classroom_id,teaching_assignment_id,attendance_date,period,created_by) VALUES (?,?,?,?,?,?,?)",id,schoolId,classroomId,assignmentId,date,period,actor);return id;}
    @Transactional public long save(UUID sessionId,long expectedVersion,List<RecordInput> records,UUID actor){
        Session s=session(sessionId);access.requireTeacherAssignment(s.schoolId(),actor,s.assignmentId());if("LOCKED".equals(s.status()))throw ApiException.conflict("ATTENDANCE_SESSION_LOCKED","Attendance session is locked");if(s.version()!=expectedVersion)throw ApiException.conflict("VERSION_CONFLICT","Attendance session changed; reload before saving");
        for(RecordInput r:records){if(!STATUSES.contains(r.status()))throw ApiException.badRequest("INVALID_ATTENDANCE_STATUS","Unsupported attendance status");Integer enrolled=jdbc.queryForObject("SELECT COUNT(*) FROM academic.class_enrollments WHERE school_id=? AND classroom_id=? AND student_id=? AND status='ACTIVE' AND start_date<=? AND (end_date IS NULL OR end_date>=?)",Integer.class,s.schoolId(),s.classroomId(),r.studentId(),s.date(),s.date());if(enrolled==null||enrolled==0)throw ApiException.badRequest("STUDENT_NOT_ENROLLED","Student is not actively enrolled in this class");
            List<String> old=jdbc.query("SELECT status FROM attendance.records WHERE attendance_session_id=? AND student_id=?",(rs,i)->rs.getString(1),sessionId,r.studentId());
            jdbc.update("INSERT INTO attendance.records(id,school_id,attendance_session_id,student_id,status,note,marked_by) VALUES (?,?,?,?,?,?,?) ON CONFLICT(attendance_session_id,student_id) DO UPDATE SET status=EXCLUDED.status,note=EXCLUDED.note,marked_by=EXCLUDED.marked_by,marked_at=NOW(),version=attendance.records.version+1",UUID.randomUUID(),s.schoolId(),sessionId,r.studentId(),r.status(),r.note(),actor);
            if(old.isEmpty()||!old.getFirst().equals(r.status())){String studentName=jdbc.queryForObject("SELECT full_name FROM academic.students WHERE id=?",String.class,r.studentId());notifications.notifyGuardians(s.schoolId(),r.studentId(),"student.attendance.changed",studentName+" - attendance update",studentName+" was marked "+r.status()+" on "+s.date()+" period "+s.period(),"ATTENDANCE_SESSION",sessionId);}
        }
        int updated=jdbc.update("UPDATE attendance.sessions SET version=version+1,updated_at=NOW() WHERE id=? AND version=?",sessionId,expectedVersion);if(updated==0)throw ApiException.conflict("VERSION_CONFLICT","Attendance session changed; reload before saving");return expectedVersion+1;
    }
    @Transactional public void submit(UUID id,UUID actor){Session s=session(id);access.requireTeacherAssignment(s.schoolId(),actor,s.assignmentId());if("LOCKED".equals(s.status()))throw ApiException.conflict("ATTENDANCE_SESSION_LOCKED","Session already locked");jdbc.update("UPDATE attendance.sessions SET status='SUBMITTED',version=version+1,updated_at=NOW() WHERE id=?",id);}
    @Transactional public void lock(UUID id,UUID actor){Session s=session(id);access.requireAnyRole(s.schoolId(),actor,"SCHOOL_ADMIN");jdbc.update("UPDATE attendance.sessions SET status='LOCKED',version=version+1,updated_at=NOW() WHERE id=?",id);}
    public Map<String,Object> detail(UUID id,UUID actor){Session s=session(id);access.requireMembership(s.schoolId(),actor);Map<String,Object> out=new LinkedHashMap<>();out.put("session",jdbc.queryForMap("SELECT s.*,c.name classroom_name,sub.name subject_name FROM attendance.sessions s JOIN academic.classrooms c ON c.id=s.classroom_id JOIN academic.teaching_assignments ta ON ta.id=s.teaching_assignment_id JOIN academic.subjects sub ON sub.id=ta.subject_id WHERE s.id=?",id));out.put("records",jdbc.queryForList("SELECT r.*,st.student_code,st.full_name FROM attendance.records r JOIN academic.students st ON st.id=r.student_id WHERE r.attendance_session_id=? ORDER BY st.full_name",id));return out;}
    public List<Map<String,Object>> student(UUID schoolId,UUID studentId,UUID actor){access.requireStudentView(schoolId,actor,studentId);return jdbc.queryForList("SELECT r.status,r.note,r.marked_at,s.attendance_date,s.period,sub.name subject_name,c.name classroom_name FROM attendance.records r JOIN attendance.sessions s ON s.id=r.attendance_session_id JOIN academic.teaching_assignments ta ON ta.id=s.teaching_assignment_id JOIN academic.subjects sub ON sub.id=ta.subject_id JOIN academic.classrooms c ON c.id=s.classroom_id WHERE r.school_id=? AND r.student_id=? ORDER BY s.attendance_date DESC,s.period DESC",schoolId,studentId);}
    public List<Map<String,Object>> sessions(UUID schoolId,UUID classroomId,LocalDate date,UUID actor){access.requireMembership(schoolId,actor);return jdbc.queryForList("SELECT s.*,sub.name subject_name,t.full_name teacher_name FROM attendance.sessions s JOIN academic.teaching_assignments ta ON ta.id=s.teaching_assignment_id JOIN academic.subjects sub ON sub.id=ta.subject_id JOIN academic.teachers t ON t.id=ta.teacher_id WHERE s.school_id=? AND s.classroom_id=? AND s.attendance_date=? ORDER BY s.period",schoolId,classroomId,date);}
    private Session session(UUID id){List<Session> l=jdbc.query("SELECT school_id,classroom_id,teaching_assignment_id,attendance_date,period,status,version FROM attendance.sessions WHERE id=?",(rs,i)->new Session(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),UUID.fromString(rs.getString(3)),rs.getDate(4).toLocalDate(),rs.getInt(5),rs.getString(6),rs.getLong(7)),id);if(l.isEmpty())throw ApiException.notFound("Attendance session not found");return l.getFirst();}
    public record RecordInput(UUID studentId,String status,String note){} private record Session(UUID schoolId,UUID classroomId,UUID assignmentId,LocalDate date,int period,String status,long version){}
}
