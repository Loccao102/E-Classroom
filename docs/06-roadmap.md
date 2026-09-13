# 06. Delivery Roadmap

## Delivery strategy

Build vertical slices that cross domain, persistence, messaging and delivery instead of finishing every database table first.

The project should always remain runnable.

## Phase 0 — Foundation

Goals:

- repository structure
- architecture docs
- local infrastructure
- CI
- Java Core API bootstrap
- Go Realtime Gateway bootstrap
- health/readiness endpoints
- structured configuration

Exit criteria:

```text
docker compose up
```

starts PostgreSQL, Redis and NATS, and both application services can be built/tested independently.

## Phase 1 — Identity + school structure

Deliver:

- user
- school
- school membership
- role model
- JWT authentication
- active school context
- academic year
- semester
- grade level
- classroom
- subject

Engineering concerns:

- Flyway migrations
- tenant-scoped indexes
- validation/error contract
- correlation IDs
- integration tests with PostgreSQL

## Phase 2 — People + assignments

Deliver:

- student
- teacher
- guardian
- student-guardian relationship
- classroom enrollment
- homeroom teacher
- teaching assignment
- timetable basics

Critical rule:

A teacher can modify class academic data only when a valid resource-level assignment authorizes that action.

## Phase 3 — First production-shaped vertical slice: Attendance

Deliver Java:

- attendance session aggregate
- bulk attendance endpoint
- attendance status transitions
- optimistic concurrency
- audit entry
- outbox event
- outbox publisher

Deliver Go:

- NATS consumer
- idempotency inbox
- notification projection
- authenticated WebSocket endpoint
- per-connection bounded send queue
- realtime parent delivery

Deliver infrastructure:

- NATS JetStream stream/consumer config
- Redis connection/presence support
- observability baseline

Exit scenario:

```text
Teacher marks Student A ABSENT
 -> Java commits attendance + audit + outbox
 -> event reaches NATS
 -> Go consumes event once logically
 -> parent connected by WebSocket receives notification
```

## Phase 4 — Leave-request workflow

Status: implemented and validated through the Phase 4 delivery PR.

Deliver:

```text
Parent submits request
 -> homeroom teacher/admin receives a durable notification
 -> approve/reject exactly once
 -> approved request reconciles matching ABSENT -> EXCUSED
 -> leave + attendance changes are auditable
 -> versioned submitted/reviewed events enter the transactional outbox
 -> guardian receives the review result through durable + realtime notification paths
```

Engineering guarantees:

- per-student serialization prevents overlapping active leave requests
- review transition is concurrency-safe (`SUBMITTED -> APPROVED|REJECTED`)
- only matching `ABSENT` attendance records in the approved school/date range are reconciled
- audit and outbox records carry the request correlation ID
- PostgreSQL/Testcontainers covers approve, reject, authorization and competing reviewers

This phase validates workflow transitions and cross-aggregate policy.

## Phase 5 — Assessments and grading

Deliver:

- assessment categories
- assessment lifecycle
- bulk score entry
- configurable weights
- score revision history
- submit / lock workflow
- student/parent score views
- score-change notification event

Performance focus:

- avoid N+1 queries
- batch validation/writes
- keyset pagination for revision history

## Phase 6 — Communication

Deliver:

- announcements
- student comments
- notification center
- delivery preferences
- read/unread state
- optional email/push adapters

## Phase 7 — Reporting and school dashboards

Deliver projections for:

- attendance rate
- absence trends
- score trend
- class performance distribution
- at-risk indicators based on explicit rules

Reporting must not make transactional attendance/grade writes slower. Prefer asynchronous projections for expensive aggregates.

## Phase 8 — Advanced capabilities

Potential additions:

- AI-generated student weekly summaries
- anomaly/risk analysis
- import/export pipelines
- mobile push
- object-storage attachments
- external SIS integration
- public integration API/webhooks

AI is deliberately after high-quality source data and auditability exist.

## Extraction roadmap

Do not extract a Java module by default.

Likely candidates if scale requires it:

1. Notification service — high independent throughput.
2. Reporting service — read/compute-heavy workload.
3. Import/export worker — bursty background workload.
4. Media service — object-storage-oriented workload.

Attendance and grading should remain in the transactional core until there is a strong reason to accept distributed-domain complexity.

## Testing strategy

### Unit

- domain invariants
- state transitions
- policy calculations

### Integration

- repositories against real PostgreSQL/Testcontainers
- migrations
- authorization with concrete assignments
- outbox transaction behavior

### Contract

- event fixtures shared/validated between Java and Go
- REST schema compatibility

### End-to-end

At least the critical vertical slice:

```text
HTTP attendance mutation
 -> DB
 -> outbox
 -> NATS
 -> Go consumer
 -> realtime notification
```

## Definition of done for each feature

A feature is not complete until it has:

- domain/API documentation updated
- migration when necessary
- authorization
- validation and stable error codes
- tests
- observability for critical paths
- audit/event behavior if relevant
- no secrets committed
- local run instructions updated