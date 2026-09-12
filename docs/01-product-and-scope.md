# 01. Product and Scope

## Product vision

E-Classroom is an electronic school communication and student-performance platform that gives schools one consistent source of truth for academic records while giving teachers, students and parents near-real-time visibility into attendance, grades, announcements and school workflows.

The goal is not to build a generic CRUD school-management demo. The system should demonstrate real production concerns: tenant isolation, resource-level authorization, auditability, asynchronous workflows, realtime delivery, idempotency, observability and horizontal scaling.

## Primary actors

### School administrator
- Manage school configuration, academic years, semesters, grades, classes and subjects.
- Manage staff and student enrollment.
- Assign teachers to classes and subjects.
- Review and lock academic records.
- View school-level reports and audit logs.

### Teacher
- View assigned classes and timetable.
- Mark attendance.
- Create assessments and enter scores.
- Add comments for students.
- Publish class announcements.

### Homeroom teacher
- All normal teacher capabilities for the homeroom class.
- Review leave requests.
- Manage class-wide communication.
- Produce semester comments and summaries.

### Parent / guardian
- Link to one or more students.
- View attendance, scores, comments and announcements.
- Receive realtime notifications.
- Submit leave requests.
- Communicate with authorized teachers.

### Student
- View personal timetable, attendance, scores and announcements.
- View assignments when the module is enabled.

## Initial business capabilities

### Identity and access
- User accounts.
- Roles and permissions.
- School membership.
- Resource-level authorization.
- JWT access tokens and refresh-token lifecycle.

### School structure
- School / tenant.
- Academic year.
- Semester.
- Grade level.
- Class.
- Subject.
- Teacher.
- Student.
- Parent / guardian.

### Academic assignment
- Teacher-to-class-to-subject assignment.
- Homeroom teacher assignment.
- Class enrollment history.
- Timetable.

### Attendance
- Attendance session by class / lesson / date.
- Attendance records with PRESENT, ABSENT, EXCUSED, LATE and EARLY_LEAVE states.
- Bulk marking.
- Leave request workflow.
- Automatic parent notification for relevant events.

### Assessment and grading
- Configurable assessment categories.
- Weighted scores.
- Draft / submitted / locked workflow.
- Score-change history.
- Semester aggregation.

### Communication
- School announcements.
- Class announcements.
- Student-specific comments.
- Notification center.
- Realtime delivery over WebSocket or SSE.

### Audit
- Sensitive mutations produce immutable audit entries.
- Score and attendance corrections retain before / after values and reason.

## First vertical slice

The first end-to-end slice is intentionally narrow but production-shaped:

```text
School Admin creates school structure
        |
        v
Teacher has a valid teaching assignment
        |
        v
Teacher opens an attendance session
        |
        v
Teacher marks a student ABSENT
        |
        +--> PostgreSQL transaction commits
        |       - attendance record
        |       - audit log
        |       - outbox event
        |
        v
Outbox publisher -> NATS JetStream
        |
        v
Go notification/realtime consumer
        |
        +--> persist notification metadata
        +--> push to connected parent
        +--> mark delivery outcome
```

This slice proves authorization, transactions, outbox reliability, event contracts, Go/Java interoperability and realtime delivery before the project grows wider.

## Non-goals for the first release

These are deliberately postponed:

- Full LMS / course-content authoring.
- Video conferencing.
- Payroll or HR.
- Tuition billing.
- Complex AI recommendations.
- Splitting every bounded context into an independent microservice.
- Kubernetes as a development requirement.

## Quality attributes

### Scalability
- Stateless API replicas.
- Horizontal scaling of realtime gateways and workers.
- Cursor pagination on large collections.
- Batch endpoints for attendance and grading.
- Cache only read-heavy derived data.

### Reliability
- At-least-once event delivery.
- Idempotent consumers.
- Transactional outbox.
- Retry with backoff and dead-letter strategy.

### Security
- Tenant isolation.
- Resource-level authorization.
- Short-lived access tokens.
- Refresh-token rotation.
- Audit trails for sensitive operations.
- No trust in client-supplied `school_id` without membership validation.

### Maintainability
- Domain modules own their models and application services.
- API DTOs are separate from persistence entities.
- Events are versioned contracts.
- Database migrations are reviewed as code.

## Success criteria for MVP

The MVP is considered complete when a school can:

1. Configure its academic structure.
2. Enroll teachers, students and guardians.
3. Assign a teacher to a class and subject.
4. Record attendance and process leave requests.
5. Enter and lock scores.
6. Notify parents reliably about attendance and academic changes.
7. View complete audit history for sensitive records.
8. Run the entire system locally with one documented command.