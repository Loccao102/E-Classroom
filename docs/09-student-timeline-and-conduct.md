# 09. Student Timeline and Conduct

## Purpose

The student timeline is a read model, not a second source of truth. It combines authorized facts from existing domains at query time so attendance, leave, grading, communication and conduct keep their own lifecycle rules.

The conduct domain exists separately from free-form teacher comments because behavior/recognition records need explicit categories, audience policy, versioning and correction history.

## Conduct model

`conduct.records` stores:

- school and student ownership
- category: `POSITIVE_RECOGNITION`, `REMINDER`, `VIOLATION`, `ACHIEVEMENT`, `GENERAL`
- optional severity: `INFO`, `LOW`, `MEDIUM`, `HIGH`
- title and note body
- occurred timestamp
- visibility: `STAFF_ONLY`, `GUARDIAN`, `STUDENT`, `STUDENT_AND_GUARDIAN`
- optional classroom and subject context
- recording actor
- optimistic `version`

Sensitive corrections update the current record only after an expected-version check. Every non-no-op correction appends a full before/after snapshot to `conduct.revisions`, with actor, reason and correlation ID. The normal audit trail also receives the create/update mutation.

There is no delete endpoint in V1. Corrections are explicit, reasoned revisions rather than silent history removal.

## Authorization

Create/update requires the school admin or a teacher currently assigned to the student. Optional classroom/subject context is validated inside the same school; teachers must also be authorized for the supplied context.

Reads first apply the existing student-resource authorization:

- school admin: any student in the school
- teacher: assigned students only
- guardian: linked child only
- student: self only

Visibility is then applied on top of resource authorization:

| Visibility | Staff | Guardian | Student |
| --- | --- | --- | --- |
| `STAFF_ONLY` | Yes | No | No |
| `GUARDIAN` | Yes | Yes | No |
| `STUDENT` | Yes | No | Yes |
| `STUDENT_AND_GUARDIAN` | Yes | Yes | Yes |

Multi-role accounts are resolved against the **relationship to the requested student**, not by granting the union of every school-wide role. For example, an account that is both a parent and a teacher only receives staff-only visibility for a child when that teacher is actually assigned to that student. Being a teacher somewhere else in the same school does not elevate the guardian view.

## Notifications and events

Create/update emits the domain event even when no family recipient exists:

```text
student.conduct.created
student.conduct.updated
```

Guardian recipients come from active guardian relationships with notifications enabled. Student delivery requires an active linked student user. `STAFF_ONLY` therefore creates the domain/outbox event but no family durable notification.

Conduct events currently use the existing `SYSTEM` notification-preference category; a dedicated preference category can be added later without changing domain event names.

## Timeline read model

Endpoint:

```text
GET /api/v1/schools/{schoolId}/students/{studentId}/timeline
```

Sources:

```text
ATTENDANCE
LEAVE
SCORE
COMMENT
CONDUCT
ANNOUNCEMENT
```

Only published/locked scores appear. Draft scores never enter the family/student timeline.

Teacher-comment and conduct visibility is applied before rows are returned. School-wide announcements are visible to authorized student viewers. Class-targeted announcements are included only when their publication date falls inside one of the student's enrollment intervals for that class, using the school's configured timezone.

### Pagination

The timeline uses deterministic keyset order:

```text
occurred_at DESC, id DESC
```

First page:

```text
GET .../timeline?limit=20
```

Next page:

```text
GET .../timeline?limit=20&beforeOccurredAt=<timestamp>&beforeId=<uuid>
```

Both cursor fields are required together. Response shape:

```json
{
  "items": [],
  "nextCursor": {
    "beforeOccurredAt": "2026-09-13T10:15:00Z",
    "beforeId": "uuid"
  }
}
```

### Filters

```text
types=CONDUCT,SCORE
fromDate=2026-09-01
toDate=2026-09-30
```

Date boundaries are interpreted in the school's configured timezone before being converted to an instant.

## Conduct API

```text
POST /api/v1/schools/{schoolId}/students/{studentId}/conduct
GET  /api/v1/schools/{schoolId}/students/{studentId}/conduct
PUT  /api/v1/schools/{schoolId}/students/{studentId}/conduct/{id}
GET  /api/v1/schools/{schoolId}/students/{studentId}/conduct/{id}/revisions
```

Create example:

```json
{
  "category": "POSITIVE_RECOGNITION",
  "severity": "INFO",
  "title": "Tích cực hỗ trợ nhóm",
  "body": "Chủ động hỗ trợ các bạn hoàn thành phần thuyết trình.",
  "visibility": "STUDENT_AND_GUARDIAN",
  "classroomId": null,
  "subjectId": null,
  "occurredAt": "2026-09-13T09:15:00+07:00"
}
```

Correction example:

```json
{
  "version": 0,
  "reason": "Bổ sung bối cảnh sau khi trao đổi với học sinh",
  "category": "ACHIEVEMENT",
  "severity": "INFO",
  "title": "Hoàn thành tốt phần trình bày",
  "body": "Nội dung đã được xác minh và bổ sung.",
  "visibility": "STUDENT_AND_GUARDIAN",
  "classroomId": null,
  "subjectId": null,
  "occurredAt": "2026-09-13T09:15:00+07:00"
}
```

Stale corrections return `VERSION_CONFLICT`. A no-op update returns the current record without incrementing version or appending a revision.

## Product UI

- Admin/teacher: **Hồ sơ học sinh** workspace with authorized student selector, filters and conduct authoring.
- Parent: timeline appears below the selected child's report.
- Student: self timeline appears below the learning report.
- Timeline entries use human-readable labels rather than raw payload JSON.

## Validation

PostgreSQL/Testcontainers coverage verifies:

- staff/guardian/student visibility boundaries
- unrelated teacher denial
- multi-role accounts cannot use an unrelated teacher role to elevate a guardian view
- notification audience derivation
- optimistic stale-write rejection
- append-only revision and correlated audit entries
- cross-domain timeline composition
- draft score exclusion
- deterministic keyset pagination without duplicate IDs
