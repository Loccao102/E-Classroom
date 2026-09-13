# 07. Security and Operations

## Security model

E-Classroom separates authentication, coarse roles and resource authorization.

### Authentication

- Java Core API issues short-lived HS256 access tokens and rotating opaque refresh tokens.
- Refresh tokens are stored only as SHA-256 hashes; raw refresh tokens never enter logs or audit metadata.
- Passwords are encoded with BCrypt (cost 12 in production configuration).
- Access tokens carry `sid`, `tokenVersion` and `mustChangePassword` claims in addition to identity/membership claims.
- Every access request is validated against the active account, token version and active refresh-session lineage, so logout/revoke/disable takes effect immediately instead of waiting for JWT expiry.
- The Go realtime gateway validates signature/issuer locally. A future hardening step is session-revocation propagation to long-lived websocket connections; the current web client closes/reconnects sockets as local auth state changes.

### Refresh-session lifecycle

A login creates one stable `session_id`. Refresh-token rotation creates a new opaque refresh token inside the same session lineage and revokes the previous token with reason `ROTATED`.

Reusing a token that has already been rotated is treated as replay. The Core API revokes active refresh sessions for that user, increments `token_version`, writes a security event and rejects the request. Security mutations on failed login/replay use transaction rules that deliberately commit those counters/revocations even though the public request returns an authentication error.

User controls:

- logout the current session;
- list recent sessions using safe device labels and timestamps;
- revoke one session;
- revoke all sessions;
- change the password, which increments `token_version` and revokes existing sessions.

Session UI never displays raw tokens or raw IP addresses. IP data used for abuse/security correlation is one-way hashed before persistence.

### Password and managed-account lifecycle

Initial and replacement passwords use the V1 policy: 10-128 characters with at least one upper-case letter, one lower-case letter and one digit. This is an application baseline, not a claim that composition rules are sufficient by themselves.

A school administrator can manage only single-school teacher/parent/student accounts belonging to that school. Platform administrators and ambiguous multi-school identities cannot be reset through the school-admin endpoint.

An administrator may:

- issue a one-time visible temporary password;
- force `must_change_password=true`;
- disable or re-enable a managed account.

Reset/disable operations revoke active sessions. A temporary-password user is restricted to `/me`, password/session security endpoints, logout and health until the password is changed.

### Login abuse controls

Failed login attempts are counted by a hash of the normalized identifier, not by a public account-existence response. Default policy:

- 5 failures within 10 minutes;
- 15-minute lock after the threshold;
- the public response remains generic (`INVALID_CREDENTIALS`) for absent, disabled, wrong-password and throttled accounts.

These defaults are configurable with `APP_LOGIN_MAX_FAILED_ATTEMPTS`, `APP_LOGIN_ATTEMPT_WINDOW_MINUTES` and `APP_LOGIN_LOCK_MINUTES`.

For a multi-instance public deployment, move this abuse counter to a shared low-latency store or a database strategy sized for login traffic; PostgreSQL is sufficient for the current V1 deployment profile.

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
- PII-heavy academic collections (`students`, `teachers`, `guardians`, assignments) are school-admin scoped; teacher access uses resource-specific classroom/assignment endpoints.

### Sensitive audit trail

PostgreSQL triggers append audit entries for attendance and score mutations. Application-level audit APIs expose history to school administrators.

Authentication/account events are additionally written to `identity.security_events`, including login success/failure/throttle, refresh replay, password change, logout, session revocation and administrator account actions. Events can include correlation ID and non-secret metadata, but never raw credentials or token values.

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
2. Generate a high-entropy JWT secret; prefer asymmetric signing or external OIDC before large public multi-organization deployment.
3. Use managed or HA PostgreSQL and configure backups / PITR.
4. Run Redis with persistence/HA appropriate to presence and future distributed rate-limit needs.
5. Run a three-node NATS JetStream cluster when durable messaging is production critical.
6. Disable bootstrap credentials after the first administrator is provisioned.
7. Restrict database and broker ports to the private network.
8. Configure structured-log collection and security-event alerts.
9. Scrape Java Prometheus metrics and Go service-level metrics when enabled.
10. Configure retention policies for audit, security-event and notification data.
11. Set trusted reverse-proxy rules so `X-Forwarded-For` cannot be spoofed by direct public clients.
12. Add MFA/external identity provider support if required by the deployment; MFA delivery/recovery vendors remain outside V1 scope.

## Scaling

### Java Core API

HTTP replicas can scale horizontally. PostgreSQL is authoritative state. Outbox publishers use row locking with `SKIP LOCKED`, allowing several Java replicas without duplicate claims.

Access-token validation currently performs a small indexed account/session lookup to guarantee immediate revocation semantics. If authentication QPS becomes large, preserve those semantics with a short-lived revocation/session cache rather than silently removing the check.

### Go realtime gateway

Each replica owns its local sockets. Every replica subscribes to the NATS event subjects and only delivers to users connected to that instance. Redis maintains short-lived presence keys for targeted routing and operational visibility.

Each connection uses a bounded outbound queue. Persistently slow clients are disconnected rather than consuming unbounded memory.

### Database

Start with one primary. Optimize query plans and indexes before adding architectural complexity. High-growth candidates for later partitioning are notifications, audit/security events and attendance history.

## Backup and recovery

Back up PostgreSQL regularly and test restores. Redis presence can be rebuilt and is not authoritative. NATS durable streams should have a retention policy matched to expected consumer outage windows.

RPO/RTO must be set by the operator; the application does not pretend one universal value fits every school.

## Observability

A correlation ID is returned on Java responses and included in error payloads. Security events persist that correlation ID when available. The next production hardening step is exporting OpenTelemetry traces across HTTP -> PostgreSQL -> outbox -> NATS -> Go delivery.

Critical alerts should include:

- Java readiness failures
- DB pool saturation
- unpublished outbox growth
- NATS disconnect/reconnect storms
- realtime connection count and disconnect spikes
- notification delivery failures
- repeated/throttled authentication failures
- refresh-token replay detections
- administrator account reset/disable events

## Incident notes

If realtime delivery is unavailable, durable notifications remain in PostgreSQL and are visible after refresh. Academic writes remain owned by Java/PostgreSQL and should not depend on an active WebSocket connection.

If refresh replay or suspicious sessions are reported, revoke all user sessions, require a password change, inspect `identity.security_events` by user/correlation ID, and only then re-enable affected accounts if they were disabled during response.
