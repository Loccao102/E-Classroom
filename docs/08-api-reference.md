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

Approved leave requests reconcile matching `ABSENT` records to `EXCUSED`.

## Assessments and grading

```text
POST /schools/{schoolId}/assessments
GET  /schools/{schoolId}/teaching-assignments/{assignmentId}/assessments
PUT  /assessments/{assessmentId}/scores
POST /assessments/{assessmentId}/submit
POST /assessments/{assessmentId}/lock
GET  /schools/{schoolId}/students/{studentId}/scores
```

Submitting an assessment makes score notifications visible to linked guardians. Locking is an administrator action.

## Communication

```text
POST /schools/{schoolId}/announcements
GET  /schools/{schoolId}/announcements

POST /schools/{schoolId}/students/{studentId}/comments
GET  /schools/{schoolId}/students/{studentId}/comments

POST /schools/{schoolId}/conversations
GET  /schools/{schoolId}/conversations
GET  /conversations/{id}/messages
POST /conversations/{id}/messages
```

Announcements support `SCHOOL` and `CLASSROOM` targets.

## Notifications

```text
GET  /notifications?limit=50
POST /notifications/{id}/read
```

Notifications are durable in PostgreSQL. Realtime is an additional delivery path rather than the only source of truth.

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
eclassroom.student.score.changed.v1
eclassroom.announcement.published.v1
```

## Reporting

```text
GET /schools/{schoolId}/reports/dashboard
GET /schools/{schoolId}/reports/students/{studentId}
GET /schools/{schoolId}/reports/risks
```

The current risk engine is explicit and explainable. It uses absence ratio and submitted/locked score averages; it is not an opaque ML classifier.

## Audit

```text
GET /schools/{schoolId}/audit/{entityType}/{entityId}
```

Sensitive attendance and score mutations are captured by append-only database audit triggers.

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
| Receive notifications | Yes | Yes | Yes | Yes |

## Error contract

```json
{
  "timestamp": "2026-09-13T00:00:00Z",
  "code": "VERSION_CONFLICT",
  "message": "Attendance session changed; reload before saving",
  "correlationId": "uuid",
  "details": {}
}
```

Stable error codes should be used by clients; human-readable messages may evolve.
