# 05. Scalability, Reliability and Operations

## Performance philosophy

E-Classroom should be easy to optimize without prematurely optimizing everything.

The design therefore focuses on:

- measurable hot paths
- explicit ownership
- stateless compute
- batch-oriented write APIs
- async side effects
- strong indexes
- bounded payloads
- reliable event delivery

## Expected hot paths

Likely high-traffic workloads:

1. Teacher opens a class roster.
2. Teacher submits attendance for 30-60 students at once.
3. Parents receive attendance notifications at school start times.
4. Many students/parents read timetables and recent scores around the same time.
5. Teachers bulk-enter scores after assessments.
6. Notification feeds grow continuously.

These workloads are bursty rather than uniformly distributed.

## Horizontal scaling

### Java Core API

The service must remain stateless between requests.

Scale by adding replicas behind a load balancer.

State belongs in:

- PostgreSQL
- Redis where explicitly appropriate
- JWT / security context reconstructed per request

Do not store critical session state in local memory.

### Go Realtime Gateway

Each instance owns its live socket connections.

For multiple replicas:

```text
Client A -> Gateway #1
Client B -> Gateway #2
                  ^
                  |
             NATS fan-out
```

Every gateway can consume relevant realtime events. Redis may store presence/routing metadata when targeted routing becomes necessary.

Start simple: broadcast relevant delivery events to all gateway replicas and let each instance deliver only to locally connected recipients. Optimize routing only when measurements justify it.

## Attendance write path

Avoid one HTTP request per student.

Use one bulk request:

```text
PUT /attendance/sessions/{id}/records
```

The server validates and writes the batch inside a bounded transaction.

For a normal class of 30-60 students this is straightforward. For large imports, use chunked bulk processing instead of extending the interactive endpoint indefinitely.

## Score entry path

Same principle:

- send a bounded batch
- validate all rows
- report per-row validation errors where useful
- perform set-based database work where possible
- avoid ORM N+1 reads

## Database indexes

Indexes should follow real query patterns.

Initial examples:

```text
attendance_record(attendance_session_id, student_id) UNIQUE
attendance_session(school_id, classroom_id, attendance_date)
class_enrollment(school_id, classroom_id, status, student_id)
teaching_assignment(school_id, teacher_id, classroom_id, subject_id, status)
student_score(assessment_id, student_id) UNIQUE
notification(recipient_user_id, created_at DESC, id DESC)
outbox_event(published_at, next_attempt_at, occurred_at)
audit_entry(school_id, entity_type, entity_id, created_at DESC)
```

Every additional index has write/storage cost and should be justified.

## Pagination

High-volume feeds use keyset/cursor pagination.

Example ordering:

```text
ORDER BY created_at DESC, id DESC
```

Cursor encodes the last `(created_at, id)` pair.

Avoid deep `OFFSET` queries.

## Partitioning

Do not partition small tables just because PostgreSQL supports it.

Candidates once volume is large enough:

- notifications by time
- notification delivery attempts by time
- audit entries by time
- attendance records by academic year or time for very large multi-school deployments

Partitioning is an operational decision driven by measured table/index size and maintenance needs.

## Cache

Cache only read-heavy data where invalidation is clear.

Initial candidates:

- school settings
- academic calendar
- permission/membership snapshots
- timetable projections

Do not place source-of-truth attendance/scores in Redis.

Cache-aside pattern:

```text
read cache
  | miss
  v
read PostgreSQL
  |
  +-> populate cache with bounded TTL
```

## Outbox publisher

Multiple Java instances may run outbox publishers concurrently.

Use PostgreSQL row claiming, for example with `FOR UPDATE SKIP LOCKED`, so publishers can scale horizontally without duplicating claims.

At-least-once publication is expected; consumers must remain idempotent.

## Idempotent event consumers

Each consumer persists processed `event_id` values or performs an equivalent atomic deduplication operation.

Pattern:

```text
BEGIN
  insert event_id into consumer_inbox (unique)
  if duplicate: COMMIT and ACK
  perform durable consumer work
COMMIT
ACK broker message
```

This is preferred over assuming a broker will provide exactly-once business semantics.

## Retry strategy

Classify failures:

### Transient
- broker/network timeout
- temporary database connection issue
- push provider unavailable

Retry with exponential backoff and jitter.

### Permanent
- invalid contract
- missing required domain reference after defined reconciliation window
- malformed recipient configuration

Move to dead-letter handling with diagnostics.

## Rate limiting

Apply rate limits by endpoint risk and actor, not one global number.

Candidates:

- authentication
- password reset
- messaging
- file upload
- realtime connection creation

Redis-backed distributed rate limiting can be added when multiple instances are deployed.

## Connection limits

The Go realtime service should expose metrics for:

- current connections
- connections per instance
- connect/disconnect rate
- messages delivered
- failed deliveries
- write latency
- backpressure / dropped connection count

A slow client must not block a global broadcast loop. Give each connection a bounded outbound queue and disconnect persistently slow consumers.

## Backpressure

Realtime design:

```text
NATS consumer
 -> validate event
 -> route recipient
 -> enqueue to bounded per-connection channel
 -> websocket writer goroutine
```

Never let arbitrary client slowness grow unbounded memory.

## Timeout policy

Every external operation requires a timeout:

- HTTP server read/write/idle timeouts
- PostgreSQL statement/context deadlines
- Redis calls
- NATS publish/consume operations
- outgoing push/email calls

No indefinite network calls.

## Observability

### Structured logs

JSON logs should carry:

- timestamp
- level
- service
- environment
- correlation_id
- trace_id
- school_id when safe
- actor_user_id when appropriate
- event_id for async handlers

Do not log secrets or raw passwords/tokens.

### Metrics

Core examples:

```text
http_request_duration_seconds
http_requests_total
db_query_duration_seconds
outbox_pending_total
outbox_publish_failures_total
nats_consumer_lag
realtime_connections
notification_delivery_total
notification_delivery_failures_total
```

### Tracing

OpenTelemetry spans should cross:

```text
HTTP -> Java use case -> PostgreSQL -> outbox -> NATS -> Go consumer -> delivery
```

## Health endpoints

Each service exposes:

```text
/live   process is alive
/ready  dependencies required to serve traffic are ready
```

Spring Boot Actuator can provide the Java service probes. The Go gateway implements lightweight equivalents.

## Graceful shutdown

Java:

- stop accepting new requests
- finish bounded in-flight work
- release DB/broker resources

Go:

- stop accepting new connections
- stop NATS consumption
- attempt bounded socket shutdown
- shutdown HTTP server with context timeout

## Security-related resilience

- JWT validation happens locally from trusted keys where possible.
- Authorization never depends solely on frontend state.
- Sensitive mutations carry optimistic version checks.
- Audit writes happen in the same transaction as the protected mutation when feasible.
- Attachments use signed/object-storage URLs rather than proxying large files through core APIs indefinitely.

## Capacity evolution

### Small deployment

```text
1 Java replica
1 Go replica
1 PostgreSQL
1 Redis
1 NATS
```

### Medium deployment

```text
2-4 Java replicas
2-4 Go replicas
PostgreSQL primary + optional read replica
Redis HA/managed
3-node NATS JetStream
```

### Larger deployment

At this point use measured service pressure to extract modules such as notifications, reporting or grading. Do not pre-decide dozens of services before operational evidence exists.