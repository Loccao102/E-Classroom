# 08. API Reference

Base URL: `/api/v1`

All endpoints except login and refresh require `Authorization: Bearer <access-token>`.

## Authentication

```text
POST /auth/login
POST /auth/refresh
GET  /me
```

Login:

```json
{
  "email": "admin@eclassroom.local",
  "password": "Admin123!"
}
```

Token response contains `accessToken`, rotating `refreshToken`, `expiresAt`, and `tokenType`.

## School and academic structure

```text
POST /schools
GET  /schools/{schoolId}

POST /schools/{schoolId}/academic-years
POST /schools/{schoolId}/semesters
POST /schools/{schoolId}/grade-levels
POST /schools/{schoolId}/subjects
POST /schools/{schoolId}/teachers
POST /schools/{schoolId}/students
POST /schools/{schoolId}/guardians
POST /schools/{schoolId}/student-guardians
POST /schools/{schoolId}/classrooms
POST /schools/{schoolId}/enrollments
POST /schools/{schoolId}/teaching-assignments
POST /schools/{schoolId}/timetable
```

Read collections:

```text
GET /schools/{schoolId}/academic-years
GET /schools/{schoolId}/semesters
GET /schools/{schoolId}/grade-levels
GET /schools/{schoolId}/subjects
GET /schools/{schoolId}/teachers
GET /schools/{schoolId}/students
GET /schools/{schoolId}/guardians
GET /schools/{schoolId}/classrooms
GET /schools/{schoolId}/teaching-assignments
GET /schools/{schoolId}/timetable
GET /schools/{schoolId}/classrooms/{classroomId}/roster
```

Role-specific views:

```text
GET /schools/{schoolId}/me/children
GET /schools/{schoolId}/me/teaching-assignments
GET /schools/{schoolId}/me/student
```

## Attendance

```text
POST /schools/{schoolId}/attendance/sessions
GET  /attendance/sessions/{sessionId}
PUT  /attendance/sessions/{sessionId}/records
POST /attendance/sessions/{sessionId}/submit
POST /attendance/sessions/{sessionId}/lock
GET  /schools/{schoolId}/classrooms/{classroomId}/attendance?date=YYYY-MM-DD
GET  /schools/{schoolId}/students/{studentId}/attendance
```

Bulk save example:

```json
{
  "version": 0,
  "records": [
    {"studentId":"uuid","status":"PRESENT","note":null},
    {"studentId":"uuid","status":"ABSENT","note":"No roll-call response"}
  ]
}
```

Supported statuses:

```text
PRESENT
ABSENT
EXCUSED
LATE
EARLY_LEAVE
```

The session `version` provides optimistic concurrency protection.

## Leave requests

```text
POST /schools/{schoolId}/students/{studentId}/leave-requests
GET  /schools/{schoolId}/students/{studentId}/leave-requests
GET  /schools/{schoolId}/leave-requests/pending
POST /leave-requests/{id}/approve
POST /leave-requests/{id}/reject
```

Submit example:

```json
{
  "startDate": "2026-09-14",
  "endDate": "2026-09-15",
  "reason": "Fever"
}
```

Rules:

- only an active linked guardian may submit for the student
- `reason` is required and limited to 1000 characters
- active `SUBMITTED`/`APPROVED` requests may not overlap for the same student
- only the current homeroom teacher or school admin may review
- review is a single terminal transition: `SUBMITTED -> APPROVED|REJECTED`
- competing/double review returns `LEAVE_ALREADY_REVIEWED`
- approved leave reconciles only matching `ABSENT` records to `EXCUSED`
- rejected leave never mutates attendance
- leave rows maintain a monotonically increasing `version`

Submission creates a durable reviewer notification when reviewers exist and always writes a versioned `student.leave-request.submitted` outbox event. Review writes `student.leave-request.reviewed`; guardian notification and realtime delivery reuse the same event envelope.

## Assessments and grading

```text
POST /schools/{schoolId}/assessments
GET  /schools/{schoolId}/teaching-assignments/{assignmentId}/assessments
PUT  /assessments/{assessmentId}/scores
GET  /assessments/{assessmentId}/scores
POST /assessments/{assessmentId}/submit
POST /assessments/{assessmentId}/lock
GET  /assessments/{assessmentId}/revisions
GET  /schools/{schoolId}/students/{studentId}/scores
```

Assessment lifecycle:

```text
DRAFT -> SUBMITTED -> LOCKED
```

Rules:

- only the assigned teacher or school admin may create/manage the assessment
- title/category are required, `maxScore > 0`, `weight > 0`
- when a semester is supplied, it must belong to the same school and match the teaching assignment
- an assessment date must fall inside the supplied semester
- score mutation is allowed only while the assessment is `DRAFT`
- score batches are validated completely before mutation and may contain at most 1000 unique students
- every scored student must have an active enrollment in the assignment classroom
- numeric no-op updates do not increment score versions or create revision rows
- `submit` is idempotent: a retry after successful submission does not publish duplicate notifications/events
- only a `SUBMITTED` assessment may be locked, and only by a school admin
- student/guardian score views expose only `SUBMITTED` or `LOCKED` scores

Transition clients should send the assessment version:

```json
{
  "version": 0
}
```

The version is used for optimistic concurrency and returns `VERSION_CONFLICT` if the workflow state changed first. For backward compatibility, transition requests without a body are still accepted; the server performs an atomic status/version transition using the current row version.

Bulk score save example:

```json
{
  "reason": "Teacher entry",
  "scores": [
    {"studentId":"uuid-a","score":8.5},
    {"studentId":"uuid-b","score":7.0}
  ]
}
```

Staff may reload draft/published values with:

```text
GET /assessments/{assessmentId}/scores
```

Revision history uses deterministic keyset pagination:

```text
GET /assessments/{assessmentId}/revisions?limit=50
GET /assessments/{assessmentId}/revisions?limit=50&beforeCreatedAt=2026-09-13T10:00:00+07:00&beforeId=<uuid>
```

Both cursor fields must be supplied together. The response contains `items` and `nextCursor` (`beforeCreatedAt`, `beforeId`). Revision rows include previous/new scores, actor, reason, correlation ID and timestamp.

Submission writes one `assessment.submitted.v1` domain event and one `student.score.published.v1` event per scored student. Guardian durable notifications and realtime delivery are generated only on the first successful submit. Locking writes `assessment.locked.v1`.

## Communication

### Announcements

```text
POST /schools/{schoolId}/announcements
GET  /schools/{schoolId}/announcements
```

Create example:

```json
{
  "title": "School notice",
  "body": "Classes begin at 07:15 tomorrow.",
  "targetType": "SCHOOL",
  "targetId": null,
  "expiresAt": null,
  "pinned": true
}
```

Targets are `SCHOOL` and `CLASSROOM`. School announcements require school-admin authority; classroom announcements require class-teacher/homeroom/admin resource authority. Expired announcements are excluded from normal audience reads, and pinned announcements sort first.

### Teacher comments

```text
POST /schools/{schoolId}/students/{studentId}/comments
GET  /schools/{schoolId}/students/{studentId}/comments
```

Supported visibility values:

```text
STAFF_ONLY
GUARDIAN
STUDENT_AND_GUARDIAN
```

Assigned/homeroom teachers or administrators create comments. Staff may inspect all authorized comments, linked guardians see guardian-visible comments, and students see only `STUDENT_AND_GUARDIAN` entries.

### Conversations and messages

```text
POST /schools/{schoolId}/conversations
GET  /schools/{schoolId}/conversations
GET  /conversations/{id}/messages
GET  /conversations/{id}/messages/page
POST /conversations/{id}/messages
POST /conversations/{id}/read
PUT  /conversations/{id}/mute?muted=true|false
```

Conversation creation accepts a subject and participant user IDs. The service does not allow arbitrary same-school messaging:

- school admins may communicate with active school members
- parents may communicate with teachers responsible for a linked child, or an admin
- teachers may communicate with students/guardians in their assigned or homeroom classes, or an admin
- students may communicate with their assigned/homeroom teachers, or an admin

Each participant maintains `last_read_at` and mute state. Conversation lists return `unread_count`. The legacy message list returns the newest bounded window in chronological order and marks it read. New clients should use keyset pagination:

```text
GET /conversations/{id}/messages/page?limit=50
GET /conversations/{id}/messages/page?limit=50&beforeCreatedAt=<timestamp>&beforeId=<uuid>
```

Muted participants retain durable message history but do not receive new-message notification fan-out until unmuted.

## Parent meetings

```text
POST /schools/{schoolId}/meetings
GET  /schools/{schoolId}/meetings
GET  /meetings/{meetingId}

PUT    /meetings/{meetingId}/response
PUT    /meetings/{meetingId}/slots/{slotId}/booking
DELETE /meetings/{meetingId}/slots/{slotId}/booking?studentId=<uuid>&version=<n>

PUT  /meetings/{meetingId}/invitees/{inviteeId}/attendance
POST /meetings/{meetingId}/outcomes
POST /meetings/{meetingId}/reminders
POST /meetings/{meetingId}/cancel
POST /meetings/{meetingId}/complete
```

Meeting scopes are `SCHOOL`, `CLASSROOM`, and `STUDENT`. The client selects scope only; guardian invitees are derived by the backend from active guardian relationships. The target student audience is snapshotted when the meeting is created so later enrollment changes do not rewrite meeting history.

Create example:

```json
{
  "scopeType": "CLASSROOM",
  "scopeId": "classroom-uuid",
  "title": "Họp phụ huynh giữa học kỳ",
  "agenda": "Tình hình học tập và kế hoạch giai đoạn tiếp theo",
  "note": "Mang theo bài kiểm tra gần nhất",
  "location": "Phòng 101",
  "startsAt": "2026-09-20T08:00:00+07:00",
  "endsAt": "2026-09-20T10:00:00+07:00",
  "includeStudents": false,
  "slots": [
    {"startsAt":"2026-09-20T08:00:00+07:00","endsAt":"2026-09-20T08:15:00+07:00"}
  ]
}
```

Rules:

- school-wide meetings require a school admin
- classroom/student meetings require admin or the relevant teacher resource authority
- optional appointment slots must be non-overlapping, inside meeting bounds, and are limited to 100 per meeting
- parent RSVP is per child and versioned with `PENDING`, `ACCEPTED`, `DECLINED`
- accepting is required before a one-to-one slot can be booked
- slot booking is atomic and a guardian/student may hold at most one slot per meeting
- attendance is staff-managed after the meeting starts
- outcomes use `STAFF_ONLY`, `GUARDIAN`, or `STUDENT_AND_GUARDIAN` visibility
- student-visible outcomes require the meeting to have `includeStudents=true`
- reminders skip declined invitees and are throttled per invitee for one hour
- lifecycle is `SCHEDULED -> CANCELLED|COMPLETED`; completion is allowed only after the meeting end time

See `docs/10-parent-meetings.md` for the full workflow and authorization model.

## Internal notifications / in-app SMS

The V1 "SMS" channel is the internal durable notification center plus optional realtime WebSocket delivery. External SMS Brandname, Zalo OA, email and mobile push are intentionally deferred delivery adapters.

Backward-compatible feed:

```text
GET /notifications?limit=50
```

New API:

```text
GET  /notifications/page?schoolId=<uuid>&limit=50
GET  /notifications/page?schoolId=<uuid>&category=ATTENDANCE&unreadOnly=true&limit=50
GET  /notifications/unread-count?schoolId=<uuid>
POST /notifications/{id}/read
POST /notifications/read-all?schoolId=<uuid>
GET  /notifications/preferences?schoolId=<uuid>
PUT  /notifications/preferences/{category}?schoolId=<uuid>
```

Notification categories:

```text
ATTENDANCE
LEAVE
SCORE
ANNOUNCEMENT
COMMENT
MESSAGE
MEETING
SYSTEM
```

Preference update example:

```json
{
  "inAppEnabled": true,
  "realtimeEnabled": false
}
```

`inAppEnabled` controls whether a durable notification row is created for that user/category. `realtimeEnabled` controls whether the user is placed in the event envelope's realtime recipient list. The underlying domain event is still written to the transactional outbox even when there are no realtime recipients.

Notification pagination uses the same `(created_at,id)` keyset pattern as message/revision history. `beforeCreatedAt` and `beforeId` must be supplied together.

## Realtime

Go gateway:

```text
GET /realtime/v1/ws?access_token=<short-lived-access-token>
```

The gateway validates the Java-issued JWT and delivers only event envelopes whose recipient list contains the connected user ID.

Domain event subjects follow:

```text
eclassroom.<event-type>.v<version>
```

Examples:

```text
eclassroom.student.attendance.changed.v1
eclassroom.student.leave-request.submitted.v1
eclassroom.student.leave-request.reviewed.v1
eclassroom.assessment.submitted.v1
eclassroom.student.score.published.v1
eclassroom.assessment.locked.v1
eclassroom.announcement.published.v1
eclassroom.teacher-comment.created.v1
eclassroom.message.created.v1
eclassroom.meeting.invited.v1
eclassroom.meeting.reminder.v1
```

## Reporting

```text
GET /schools/{schoolId}/reports/dashboard
GET /schools/{schoolId}/reports/students/{studentId}
GET /schools/{schoolId}/reports/risks
```

The current risk engine is explicit and explainable. It uses absence ratio and submitted/locked score averages; student subject averages already apply configurable assessment weights. It is not an opaque ML classifier.

## Audit

```text
GET /schools/{schoolId}/audit/{entityType}/{entityId}
```

Sensitive attendance and score mutations are captured by append-only database audit triggers. Workflow-level audit entries such as leave submit/approve/reject, assessment create/submit/lock, conduct correction and parent-meeting state/action changes are appended by transactional services and include the current correlation ID. Score/conduct revisions also preserve actor, reason and correlation ID.

## Core role matrix

| Capability | School Admin | Teacher | Parent | Student |
| --- | --- | --- | --- | --- |
| Configure academic structure | Yes | No | No | No |
| Manage people / enrollment | Yes | No | No | No |
| Mark attendance | Yes | Assigned only | No | No |
| Enter scores | Yes | Assigned only | No | No |
| Lock scores / attendance | Yes | No | No | No |
| Review leave request | Yes | Homeroom only | No | No |
| Submit leave request | No | No | Linked child | No |
| View student records | Yes | Assigned students | Linked child | Self |
| Publish school announcement | Yes | No | No | No |
| Publish class announcement | Yes | Assigned class | No | No |
| Create teacher comment / conduct | Yes | Assigned students | No | No |
| Create parent meeting | Yes | Assigned class/student | No | No |
| Respond / book meeting slot | Operational view | Operational view | Invited child | No |
| Record meeting attendance/outcome | Yes | Managed meeting | No | No |
| View student-targeted meeting | Yes | Authorized scope | Invited child | If explicitly included |
| Start conversation | Yes | Related members | Related teachers/admin | Related teachers/admin |
| Receive internal notifications | Yes | Yes | Yes | Yes |

## Error contract

```json
{
  "timestamp": "2026-09-13T00:00:00Z",
  "code": "VERSION_CONFLICT",
  "message": "Resource changed; reload before changing workflow state",
  "correlationId": "uuid",
  "details": {}
}
```

Stable error codes should be used by clients; human-readable messages may evolve.
