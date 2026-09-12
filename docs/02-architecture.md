# 02. Architecture

## Decision summary

E-Classroom starts as a **polyglot modular system**:

- `core-api`: Java + Spring Boot. Owns transactional school and academic business logic.
- `realtime-gateway`: Go + Gin. Owns realtime connections and high-concurrency notification delivery.
- PostgreSQL: source of truth for durable relational data.
- Redis: ephemeral state, distributed cache, presence and rate limiting.
- NATS JetStream: durable asynchronous domain-event transport.
- S3-compatible object storage: attachments and exported reports when required.

Spring Boot `4.1.x` is the baseline for the Java service. The project intentionally uses Java 21 as a conservative LTS runtime baseline. Go uses the current supported Go toolchain, with Gin as the HTTP framework.

## Why not full microservices now?

Premature microservices would create distributed transactions, duplicated operational work and difficult local development before the domain boundaries are proven.

The Java application therefore begins as a **modular monolith**. Each module has explicit package boundaries and owns its application logic. A module is extracted only when one or more of these become true:

1. It requires independent scaling.
2. It has a clearly independent data lifecycle.
3. It has a different availability or latency requirement.
4. Team ownership requires independent deployment.
5. Its deployment cadence is materially different from the core.

Realtime delivery already satisfies several of these conditions, which is why it starts separately in Go.

## Logical architecture

```text
                         +----------------------+
                         | Web / Mobile clients |
                         +----------+-----------+
                                    |
                         HTTPS / WS / SSE
                                    |
                    +---------------+---------------+
                    |                               |
                    v                               v
       +--------------------------+      +-------------------------+
       | Java Core API            |      | Go Realtime Gateway     |
       | Spring Boot              |      | Gin                     |
       |--------------------------|      |-------------------------|
       | identity                 |      | auth token validation   |
       | school                   |      | websocket / sse         |
       | academic                 |      | presence                |
       | enrollment               |      | connection registry     |
       | attendance               |      | event consumers         |
       | grading                  |      | notification fan-out    |
       | communication            |      +-----------+-------------+
       | audit                    |                  |
       | outbox                   |                  | Redis
       +------------+-------------+                  v
                    |                         +-------------+
                    |                         | Redis       |
                    |                         +-------------+
                    |
                    | SQL transaction
                    v
              +-----------+
              |PostgreSQL |
              +-----+-----+
                    |
                    | poll/claim outbox
                    v
              +-----------+
              | publisher |
              +-----+-----+
                    |
                    v
             +--------------+
             | NATS JetStream|
             +------+-------+
                    |
         +----------+----------+
         |                     |
         v                     v
  realtime consumer     notification worker
```

## Java module boundaries

Suggested package structure:

```text
com.eclassroom
├── shared
│   ├── security
│   ├── web
│   ├── persistence
│   └── events
├── identity
├── school
├── academic
├── enrollment
├── attendance
├── grading
├── communication
├── audit
└── outbox
```

Each domain module should prefer this internal structure:

```text
<module>/
├── domain/
├── application/
├── infrastructure/
└── api/
```

Rules:

- `domain` must not depend on web/controller details.
- `application` coordinates use cases and transactions.
- `api` maps HTTP contracts to application commands/queries.
- `infrastructure` contains persistence and external adapters.
- One module must not directly mutate another module's persistence entities.
- Cross-module work should go through application interfaces or domain events.

## Go service structure

```text
services/realtime-gateway/
├── cmd/server/
├── internal/
│   ├── config/
│   ├── http/
│   ├── auth/
│   ├── realtime/
│   ├── notification/
│   ├── messaging/
│   └── observability/
└── pkg/
```

The Go service is stateless from a deployment perspective. Connection ownership is local to an instance, while presence/routing metadata can be stored in Redis when multiple replicas are running.

## Request path

Normal transactional requests:

```text
Client
 -> Java Core API
 -> authorization
 -> application use case
 -> PostgreSQL transaction
 -> response
```

Realtime delivery path:

```text
Java transaction
 -> domain state + outbox row
 -> outbox publisher
 -> NATS JetStream
 -> Go consumer
 -> connected client(s)
```

## Database ownership

### Java Core API
Owns authoritative tables for:

- users and memberships
- schools
- academic structure
- students / guardians / teachers
- enrollment
- teaching assignment
- timetable
- attendance
- grading
- leave requests
- announcements
- audit
- outbox

### Go Realtime Gateway
Must **not** mutate core academic tables.

It may own:

- notification delivery metadata
- device subscriptions
- connection/presence metadata
- channel subscriptions

Durable notification metadata can live in PostgreSQL under a dedicated schema. Ephemeral connection state belongs in memory/Redis.

## Multi-tenancy

The initial tenant is a school.

Every tenant-owned aggregate must carry `school_id` either directly or through an immutable relationship that can be enforced efficiently.

Rules:

- Authentication resolves a user's school memberships.
- Authorization determines which school context is active.
- Queries always include tenant predicates.
- Unique constraints include `school_id` where uniqueness is tenant-scoped.
- Never authorize based only on a client-provided tenant identifier.

A later version can migrate large tenants to schema-per-tenant or database-per-tenant if measurements justify it, without changing the domain API.

## API strategy

- Public client APIs: REST/JSON first.
- Realtime: WebSocket, with SSE supported where one-way server push is enough.
- Internal asynchronous communication: versioned events over NATS JetStream.
- gRPC is postponed until a concrete synchronous service-to-service need appears.

This avoids operating REST + gRPC + messaging from day one without a real requirement.

## Transaction strategy

No distributed transactions.

When a business transaction needs an event:

1. Domain state and an outbox row are inserted in the same PostgreSQL transaction.
2. A publisher claims unpublished outbox rows.
3. Events are published to JetStream.
4. The row is marked published only after broker acknowledgment.
5. Consumers are idempotent because delivery is at-least-once.

## Cache strategy

Redis is not the source of truth.

Good cache candidates:

- school configuration
- permission snapshots
- timetable views
- frequently requested aggregate summaries
- realtime presence

Avoid caching mutable academic records until read pressure proves it useful.

## Deployment evolution

### Stage 1 — local / development
Docker Compose:

- PostgreSQL
- Redis
- NATS
- Java Core API
- Go Realtime Gateway

### Stage 2 — first hosted environment
Containers behind a reverse proxy/load balancer. Managed PostgreSQL is preferred if available.

### Stage 3 — scale-out
Kubernetes only when there is an operational reason:

- multiple replicas
- autoscaling
- rolling deployments
- service discovery needs
- stronger environment isolation

The application must be Kubernetes-ready but must not require Kubernetes to develop locally.