# 03. Domain and Data

## Core domain model

The domain is organized around school operations instead of database tables. The important aggregates and relationships are below.

## School / tenant

`School`

Key fields:

- `id`
- `code`
- `name`
- `timezone`
- `status`
- `created_at`
- `updated_at`

A school is the primary tenant boundary.

## Identity and membership

`User`

- login identity
- password hash / authentication metadata
- account state

`SchoolMembership`

- `user_id`
- `school_id`
- roles
- status

Roles are coarse-grained. Resource authorization still checks concrete assignments.

## People

`Student`

- `id`
- `school_id`
- `student_code`
- name and profile fields
- admission status

`Teacher`

- `id`
- `school_id`
- `teacher_code`
- user linkage
- profile fields

`Guardian`

- `id`
- `school_id`
- user linkage
- profile fields

`StudentGuardian`

Many-to-many relationship containing:

- `student_id`
- `guardian_id`
- relationship type
- primary-contact flag
- notification preferences

This supports one guardian with several children and one student with several guardians.

## Academic structure

`AcademicYear`

- school
- name
- start / end date
- state: UPCOMING, ACTIVE, COMPLETED

`Semester`

- academic year
- name
- start / end date
- ordering

`GradeLevel`

Examples: Grade 6, Grade 7.

`Classroom`

Represents a class group for one academic year.

- school
- academic year
- grade level
- code / name
- homeroom teacher

`Subject`

Tenant-owned catalog of subjects.

## Enrollment

`ClassEnrollment`

Tracks the student's membership in a class over time.

Fields should include effective dates/state rather than overwriting history.

Important invariant:

> A student can have at most one active primary classroom for an academic period unless the school explicitly enables a different model.

## Teaching assignment

`TeachingAssignment`

Defines the authorization relationship:

```text
Teacher + Classroom + Subject + AcademicYear/Semester
```

A teacher may enter attendance/grades only when an active assignment authorizes the operation.

## Timetable

`TimetableEntry`

- school
- classroom
- subject
- teacher / teaching assignment
- weekday
- period
- room
- validity range

Do not encode the weekly schedule directly into the class table.

## Attendance aggregate

### AttendanceSession

Represents one attendance-taking context.

Example:

```text
Class 6A
2026-09-13
Period 1
Mathematics
Teacher T001
```

Fields:

- `id`
- `school_id`
- `classroom_id`
- `teaching_assignment_id`
- `attendance_date`
- `period`
- `status`: OPEN, SUBMITTED, LOCKED
- timestamps

### AttendanceRecord

One record per enrolled student per session.

Status:

- PRESENT
- ABSENT
- EXCUSED
- LATE
- EARLY_LEAVE

Other fields:

- reason
- note
- marked_by
- marked_at
- version

Constraints:

- unique `(attendance_session_id, student_id)`
- student must be actively enrolled in the target classroom for the relevant date
- teacher must be authorized for the session

### LeaveRequest

Workflow:

```text
DRAFT -> SUBMITTED -> APPROVED / REJECTED / CANCELLED
```

An approved leave request may change an attendance result from ABSENT to EXCUSED according to school policy. That mutation must be audited.

## Assessment and grading aggregate

### Assessment

- classroom
- subject / teaching assignment
- semester
- type/category
- title
- maximum score
- weight
- due/test date
- state

### StudentScore

- assessment
- student
- score
- state: DRAFT, SUBMITTED, LOCKED
- recorded_by
- timestamps
- optimistic-lock version

Unique:

`(assessment_id, student_id)`

### ScoreRevision

Immutable history of sensitive changes:

- score id
- previous value
- new value
- actor
- reason
- timestamp

## Communication

`Announcement`

Targets one of:

- school
- grade
- classroom
- selected users/students

`TeacherComment`

Student-specific academic/behavior comment, optionally visible to guardians/student.

## Notification model

A notification is separated into two concepts:

### Notification

Business message for a recipient.

Example:

```text
Student A was marked ABSENT in Mathematics at 08:05.
```

### NotificationDelivery

A delivery attempt over a channel:

- realtime
- push
- email
- SMS (future)

This allows retries and channel-specific delivery status without duplicating the business notification.

## Audit model

`AuditEntry`

Recommended fields:

- `id`
- `school_id`
- `actor_user_id`
- `action`
- `entity_type`
- `entity_id`
- `old_values` JSONB
- `new_values` JSONB
- `reason`
- `correlation_id`
- `created_at`

Audit entries are append-only from the application perspective.

## Transactional outbox

`OutboxEvent`

Recommended fields:

- `id` UUID
- `aggregate_type`
- `aggregate_id`
- `event_type`
- `event_version`
- `school_id`
- `payload` JSONB
- `occurred_at`
- `published_at`
- `attempt_count`
- `next_attempt_at`

Indexes should support claiming unpublished events efficiently.

## PostgreSQL schema direction

One PostgreSQL cluster is sufficient initially, but logical schemas make ownership visible:

```text
identity.*
school.*
academic.*
attendance.*
grading.*
communication.*
audit.*
integration.*
notification.*
```

This is not a promise that each schema will become a service. It is a boundary marker.

## ID strategy

Use UUIDs for public/domain identifiers to avoid coordination between nodes.

For very hot append-only tables, evaluate UUIDv7 or time-ordered identifiers to reduce random index insertion. The application contract should treat IDs as opaque.

## Concurrency strategy

Use optimistic locking for mutable records that can be edited concurrently:

- attendance session state
- leave-request workflow
- scores
- assessment state

Conflicts return an explicit version/concurrency error rather than silently overwriting another user's update.

## High-volume table considerations

Likely high-growth tables:

- attendance records
- score revisions
- notifications
- notification deliveries
- audit entries
- outbox events

Design considerations:

- composite indexes begin with `school_id` for tenant-scoped access patterns
- avoid unbounded `OFFSET` pagination
- archive or partition append-heavy tables when data volume proves necessary
- retain business data according to explicit policy rather than deleting historical records casually

## Example attendance transaction

One transaction performs:

```text
1. Validate teacher assignment.
2. Validate enrollment.
3. Upsert/modify attendance record.
4. Append audit entry when applicable.
5. Insert StudentAttendanceChanged.v1 into outbox.
6. Commit.
```

The network call to NATS is deliberately outside the transaction. The outbox publisher handles it after commit.