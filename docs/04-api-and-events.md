# 04. API and Event Contracts

## API principles

Public client APIs use REST/JSON first. Contracts are versioned at the HTTP boundary and domain events are versioned independently.

Base path:

```text
/api/v1
```

The backend should return a consistent error envelope and correlation identifier.

Example:

```json
{
  "code": "ATTENDANCE_SESSION_LOCKED",
  "message": "The attendance session is locked.",
  "correlationId": "01J...",
  "details": {}
}
```

## Pagination

Do not use unbounded offset pagination for high-volume collections.

Preferred contract:

```text
GET /api/v1/notifications?limit=50&cursor=<opaque>
```

Response:

```json
{
  "items": [],
  "nextCursor": "opaque-or-null"
}
```

## Idempotency

Mutation APIs that may be retried by clients or infrastructure should accept an idempotency key where duplicate execution would be harmful.

Example:

```text
Idempotency-Key: 5df6...
```

Candidates:

- bulk attendance submission
- leave-request submission
- notification requests from future external integrations

## Initial REST surface

### Authentication

```text
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
GET  /api/v1/me
```

### Schools

```text
POST /api/v1/schools
GET  /api/v1/schools/{schoolId}
```

### Academic structure

```text
POST /api/v1/schools/{schoolId}/academic-years
POST /api/v1/schools/{schoolId}/semesters
POST /api/v1/schools/{schoolId}/subjects
POST /api/v1/schools/{schoolId}/classrooms
GET  /api/v1/schools/{schoolId}/classrooms
```

### Students / guardians

```text
POST /api/v1/schools/{schoolId}/students
GET  /api/v1/schools/{schoolId}/students/{studentId}
POST /api/v1/students/{studentId}/guardians
```

### Teaching assignments

```text
POST /api/v1/teaching-assignments
GET  /api/v1/teachers/{teacherId}/teaching-assignments
```

### Attendance

```text
POST /api/v1/attendance/sessions
GET  /api/v1/attendance/sessions/{sessionId}
PUT  /api/v1/attendance/sessions/{sessionId}/records
POST /api/v1/attendance/sessions/{sessionId}/submit
POST /api/v1/attendance/sessions/{sessionId}/lock
GET  /api/v1/students/{studentId}/attendance
```

Bulk attendance request example:

```json
{
  "version": 3,
  "records": [
    {
      "studentId": "uuid",
      "status": "PRESENT",
      "note": null
    },
    {
      "studentId": "uuid",
      "status": "ABSENT",
      "note": "Not present at roll call"
    }
  ]
}
```

### Leave requests

```text
POST /api/v1/students/{studentId}/leave-requests
GET  /api/v1/classes/{classId}/leave-requests
POST /api/v1/leave-requests/{id}/approve
POST /api/v1/leave-requests/{id}/reject
```

### Assessments and scores

```text
POST /api/v1/assessments
PUT  /api/v1/assessments/{assessmentId}/scores
POST /api/v1/assessments/{assessmentId}/submit
POST /api/v1/assessments/{assessmentId}/lock
GET  /api/v1/students/{studentId}/scores
```

## Realtime endpoints

Initial endpoint:

```text
GET /realtime/v1/ws
```

Authentication is established from a short-lived bearer token during connection setup.

Possible SSE alternative:

```text
GET /realtime/v1/events
```

A client subscribes only to channels derived from its authenticated identity and memberships. The client must not select arbitrary student IDs to bypass authorization.

## Event naming

Use semantic business names, not table-operation names.

Good:

```text
student.attendance.changed.v1
leave-request.approved.v1
student.score.changed.v1
announcement.published.v1
```

Avoid:

```text
attendance-row-updated
student-table-inserted
```

## Event envelope

Every event uses a common envelope:

```json
{
  "eventId": "uuid",
  "eventType": "student.attendance.changed",
  "eventVersion": 1,
  "occurredAt": "2026-09-13T08:05:00+07:00",
  "schoolId": "uuid",
  "correlationId": "uuid",
  "causationId": "uuid-or-null",
  "actorUserId": "uuid",
  "data": {}
}
```

`eventId` is the consumer idempotency key.

## Attendance event

Subject:

```text
eclassroom.attendance.changed.v1
```

Payload:

```json
{
  "eventId": "uuid",
  "eventType": "student.attendance.changed",
  "eventVersion": 1,
  "occurredAt": "2026-09-13T08:05:00+07:00",
  "schoolId": "uuid",
  "correlationId": "uuid",
  "actorUserId": "uuid",
  "data": {
    "attendanceSessionId": "uuid",
    "studentId": "uuid",
    "classroomId": "uuid",
    "subjectId": "uuid",
    "previousStatus": "PRESENT",
    "status": "ABSENT",
    "attendanceDate": "2026-09-13",
    "period": 1,
    "markedAt": "2026-09-13T08:05:00+07:00"
  }
}
```

## Consumer behavior

The Go notification consumer should:

1. Check whether `eventId` has already been processed.
2. Resolve authorized recipients from a notification projection or trusted core endpoint.
3. Create notification delivery records.
4. Push to connected recipients.
5. Ack only after local durable work succeeds.
6. Retry transient errors with backoff.
7. Send poison messages to a dead-letter stream after the retry policy is exhausted.

## Event compatibility

Rules:

- Do not remove fields from an existing event version.
- New optional fields may be added when consumers tolerate them.
- Breaking changes require a new event version/subject.
- Producers may temporarily publish two versions during migrations.

## Correlation and tracing

All incoming HTTP requests receive a correlation ID.

That ID propagates through:

```text
HTTP request
 -> database transaction
 -> outbox row
 -> NATS event
 -> Go consumer
 -> realtime delivery log
```

W3C Trace Context should be adopted through OpenTelemetry as observability is wired in.