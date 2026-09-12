# 07. Security and Operations

## Security model

E-Classroom separates authentication, coarse roles and resource authorization.

### Authentication

- Java Core API issues short-lived HS256 access tokens and rotating opaque refresh tokens.
- Refresh tokens are stored only as SHA-256 hashes.
- Passwords are encoded with BCrypt.
- All business endpoints require authentication except login/refresh and health probes.
- The Go realtime gateway validates the same issuer and signing secret locally; it does not call Java for every WebSocket frame.

### Tenant isolation

The tenant is a school. Every school-owned business row contains `school_id` or has an immutable relationship to one.

Authorization rules never trust a client-provided school identifier alone. Membership is resolved server-side before access is granted.

### Resource authorization

Roles such as `SCHOOL_ADMIN`, `TEACHER`, `PARENT` and `STUDENT` are necessary but not sufficient for sensitive operations.

Examples:

- A teacher may change attendance or scores only through an active `TeachingAssignment`.
- A guardian may submit leave requests and read records only for linked students.
- A student may read only the linked student profile.
- Leave approval is restricted to the homeroom teacher or school admin.
- School creation is restricted to platform administrators.

### Sensitive audit trail

PostgreSQL triggers append audit entries for attendance and score mutations. Application-level audit APIs expose history to school administrators.

### Secrets

Never commit production secrets. At minimum replace:

```text
APP_JWT_SECRET
POSTGRES_PASSWORD
BOOTSTRAP_ADMIN_PASSWORD
```

The values in `infra/docker-compose.yml` are explicitly local-development credentials.

## Local deployment

Prerequisite: Docker with Compose v2.

```bash
docker compose -f infra/docker-compose.yml up --build -d
```

Open:

```text
Web UI              http://localhost:3000
Java API            http://localhost:8080
Go realtime         http://localhost:8090
NATS monitoring     http://localhost:8222
```

Local bootstrap account:

```text
admin@eclassroom.local
Admin123!
```

Stop and remove persistent local data:

```bash
docker compose -f infra/docker-compose.yml down -v
```

## Health probes

Java:

```text
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Go:

```text
GET /live
GET /ready
```

Web/Nginx:

```text
GET /healthz
```

Readiness is intended for load balancers and orchestration. Liveness should not fail merely because a downstream dependency is temporarily slow.

## Production deployment checklist

1. Put the web/Nginx container behind TLS.
2. Generate a high-entropy JWT secret (or migrate to asymmetric signing / external OIDC before multi-organization public deployment).
3. Use managed or HA PostgreSQL and configure backups / PITR.
4. Run Redis with persistence/HA appropriate to presence and rate-limit needs.
5. Run a three-node NATS JetStream cluster when durable messaging is production critical.
6. Disable bootstrap credentials after the first administrator is provisioned.
7. Restrict database and broker ports to the private network.
8. Configure structured-log collection and alerts.
9. Scrape Java Prometheus metrics and Go service-level metrics when enabled.
10. Configure retention policies for audit and notification data.

## Scaling

### Java Core API

Stateless HTTP replicas can scale horizontally. PostgreSQL is authoritative state. Outbox publishers use row locking with `SKIP LOCKED`, allowing several Java replicas without duplicate claims.

### Go realtime gateway

Each replica owns its local sockets. Every replica subscribes to the NATS event subjects and only delivers to users connected to that instance. Redis maintains short-lived presence keys for future targeted routing and operational visibility.

Each connection uses a bounded outbound queue. Persistently slow clients are disconnected rather than consuming unbounded memory.

### Database

Start with one primary. Optimize query plans and indexes before adding architectural complexity. High-growth candidates for later partitioning are notifications, audit entries and attendance history.

## Backup and recovery

Back up PostgreSQL regularly and test restores. Redis presence can be rebuilt and is not authoritative. NATS durable streams should have a retention policy matched to expected consumer outage windows.

RPO/RTO must be set by the operator; the application does not pretend one universal value fits every school.

## Observability

A correlation ID is returned on Java responses and included in error payloads. The next production hardening step is exporting OpenTelemetry traces across HTTP -> PostgreSQL -> outbox -> NATS -> Go delivery.

Critical alerts should include:

- Java readiness failures
- DB pool saturation
- unpublished outbox growth
- NATS disconnect/reconnect storms
- realtime connection count and disconnect spikes
- notification delivery failures
- repeated authentication failures

## Incident notes

If realtime delivery is unavailable, durable notifications remain in PostgreSQL and are visible after refresh. Academic writes remain owned by Java/PostgreSQL and should not depend on an active WebSocket connection.
