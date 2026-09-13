# 10. Parent Meeting Workflow

## Purpose

Parent meetings are a first-class school-family workflow rather than free-form chat messages. The V1 domain handles invitations, RSVP, optional one-to-one appointment slots, attendance, reminders and follow-up outcomes while remaining independent from external calendar, video-call, SMS or Zalo providers.

## Audience model

A meeting has one of three scopes:

```text
SCHOOL
CLASSROOM
STUDENT
```

The client selects only the scope. It never supplies guardian user IDs.

When a meeting is created, the backend resolves the active students in scope and snapshots them in `communication.meeting_students`. Guardian invitees are then derived server-side from those students' active guardian relationships. This snapshot makes historical authorization deterministic even when a student later changes class.

- `SCHOOL`: school admin only; snapshots all active students in the school.
- `CLASSROOM`: school admin or a teacher authorized for the class; snapshots active enrolled students.
- `STUDENT`: school admin or an assigned teacher; snapshots that student.

`includeStudents=true` additionally gives the targeted student accounts read access. It does not change guardian derivation.

## Data model

`communication.parent_meetings`

- scope, title, agenda, note and location
- start/end timestamps
- whether student accounts may see the meeting
- lifecycle status `SCHEDULED`, `CANCELLED`, `COMPLETED`
- optimistic `version`

`communication.meeting_students`

- immutable audience snapshot used for historical scope and student visibility

`communication.meeting_invitees`

- guardian + student pair
- response `PENDING`, `ACCEPTED`, `DECLINED`
- attendance `UNKNOWN`, `PRESENT`, `ABSENT`
- reminder timestamp and optimistic version

`communication.meeting_slots`

- optional one-to-one appointment windows inside the meeting range
- atomic booking ownership
- optimistic version
- a partial unique index prevents a guardian/student pair from holding more than one slot in the same meeting

`communication.meeting_outcomes`

- optional student context
- follow-up text
- visibility `STAFF_ONLY`, `GUARDIAN`, `STUDENT_AND_GUARDIAN`

## Lifecycle

```text
SCHEDULED -> CANCELLED
SCHEDULED -> COMPLETED
```

Completion is allowed only after the meeting end time. Both transitions use the expected meeting version and return `VERSION_CONFLICT` on stale state.

A cancelled/completed meeting no longer accepts RSVP or slot booking changes.

## RSVP and appointment slots

A parent responds separately for each invited child:

```text
PENDING -> ACCEPTED
PENDING -> DECLINED
ACCEPTED <-> DECLINED
```

Response changes use the invitee version. Declining automatically releases any one-to-one slot held for that child.

A slot may be booked only after the parent has accepted the invitation for that student. Booking performs a row/version check and a conditional database update. The database uniqueness rule protects against a parent/student pair simultaneously acquiring multiple slots.

Other families' booking identities are not exposed to parents. Staff managers may see the booking owner for operational purposes.

## Attendance and outcomes

Only an authorized meeting manager may record guardian attendance, and only after the meeting has started.

Outcomes are append-only in V1:

- `STAFF_ONLY`: visible only to authorized staff; student context is optional.
- `GUARDIAN`: requires a student in the meeting and is visible only to guardians invited for that student.
- `STUDENT_AND_GUARDIAN`: requires a student in the meeting and `includeStudents=true`; visible to that student's guardians and student account.

Outcome creation is audited and family-visible outcomes reuse the internal notification/outbox pipeline.

## Reminders

Managers may send reminders only for future scheduled meetings. Declined invitees are excluded. Each invitee is throttled to at most one reminder per hour by `last_reminded_at`.

Meeting events use the notification category:

```text
MEETING
```

Representative event types:

```text
meeting.invited
meeting.response.updated
meeting.slot.booked
meeting.slot.cancelled
meeting.reminder
meeting.outcome.created
meeting.cancelled
meeting.completed
```

The durable notification preference and realtime preference continue to work independently through `NotificationService`.

## API

```text
POST /api/v1/schools/{schoolId}/meetings
GET  /api/v1/schools/{schoolId}/meetings
GET  /api/v1/meetings/{meetingId}

PUT    /api/v1/meetings/{meetingId}/response
PUT    /api/v1/meetings/{meetingId}/slots/{slotId}/booking
DELETE /api/v1/meetings/{meetingId}/slots/{slotId}/booking?studentId=<uuid>&version=<n>

PUT  /api/v1/meetings/{meetingId}/invitees/{inviteeId}/attendance
POST /api/v1/meetings/{meetingId}/outcomes
POST /api/v1/meetings/{meetingId}/reminders
POST /api/v1/meetings/{meetingId}/cancel
POST /api/v1/meetings/{meetingId}/complete
```

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

The service accepts at most 100 slots, all inside the meeting time range and non-overlapping.

## Product UI

All roles receive a **Lịch họp** workspace.

- Admin: create school/class/student meetings and manage authorized meetings.
- Teacher: create meetings only for classes/students they are responsible for.
- Parent: view invitations per child, RSVP, select/cancel a one-to-one slot, and see permitted outcomes.
- Student: read meetings only when `includeStudents=true` and see only explicitly student-visible outcomes.
- Staff manager: view invitation responses, record attendance, send reminders, add outcomes and close/cancel the meeting.

The normal UI never asks a user to paste internal UUIDs.

## Validation coverage

PostgreSQL/Testcontainers tests cover:

- server-derived and snapshotted audience
- role/resource visibility and unrelated-teacher denial
- school-wide scope with null `scopeId`
- optimistic RSVP and slot versions
- one-slot-per-guardian/student conflict protection
- booking release on decline/cancel
- attendance timing/authorization
- outcome visibility for staff, guardian and student
- reminder throttling and `MEETING` notification category
- terminal meeting lifecycle

## Explicitly out of scope for V1

- Google/Microsoft calendar synchronization
- Zoom, Meet or Teams integration
- external SMS Brandname
- Zalo OA reminders
- external email delivery

Those can later be delivery/integration adapters over the same meeting domain and events without changing the core workflow.
