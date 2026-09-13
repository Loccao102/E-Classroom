# E-Classroom

> A scalable electronic school communication and student-performance platform connecting schools, teachers, students and parents.

E-Classroom is a docs-first, polyglot full-stack system. Java/Spring Boot owns the transaction-heavy school domain and authoritative data; Go/Gin owns high-concurrency realtime delivery; React/Vite provides role-aware web workspaces for administrators, teachers, parents and students.

## Architecture

```text
Browser
  |
  | HTTPS / WebSocket
  v
Nginx Web Gateway
  |                         +----------------------+
  | /api                    | Go Realtime Gateway  |
  +----> Java Core API      | Gin + WebSocket      |
  |      Spring Boot        | Redis presence       |
  |        |                +----------^-----------+
  |        | PostgreSQL                |
  |        | transaction               | NATS fan-out
  |        | + outbox                  |
  |        v                           |
  |   +------------+     events     +--+---+
  |   | PostgreSQL | -------------> | NATS |
  |   +------------+                +------+
  |
  +----> /realtime -> Go
```

### Why Java + Go?

- **Java / Spring Boot**: authentication, tenant isolation, resource authorization, academic workflows, transactions, attendance, grading, leave requests, communication, meetings, reports, audit and transactional outbox.
- **Go / Gin**: authenticated WebSockets, connection backpressure, Redis presence, NATS event fan-out and graceful horizontal scaling.
- **PostgreSQL**: durable source of truth, including notifications and audit history.
- **Redis**: ephemeral presence and future distributed rate-limit/cache state; never the academic source of truth.
- **NATS**: low-latency event transport for realtime fan-out. The local broker is JetStream-enabled, while durable notification state remains in PostgreSQL and events originate from a transactional outbox.
- **React / Vite + Nginx**: same-origin web UI and API/WebSocket reverse proxy.

The Java backend starts as a **modular monolith** rather than prematurely splitting the transactional domain into many microservices. Module extraction is reserved for measured scaling or ownership needs.

## Implemented capabilities

### Identity and authorization

- JWT access tokens and rotating opaque refresh tokens.
- BCrypt password hashing and SHA-256 refresh-token hashes.
- School memberships and roles: `SCHOOL_ADMIN`, `TEACHER`, `PARENT`, `STUDENT`.
- Platform administrator bootstrap.
- Resource-level authorization for class/subject teaching assignments, guardianship, homeroom workflows and student self-access.
- Tenant-aware access using `school_id`.
- Session listing/revocation, logout-all, password changes, temporary-password enforcement and login throttling.

### Academic structure

- Academic years and semesters.
- Grade levels, subjects and classrooms.
- Students, teachers and guardians.
- Student–guardian links.
- Enrollment history.
- Homeroom teacher and teaching assignments.
- Timetable entries.

### Attendance and leave

- Attendance sessions with optimistic concurrency.
- Bulk attendance entry.
- `PRESENT`, `ABSENT`, `EXCUSED`, `LATE`, `EARLY_LEAVE`.
- Submit/lock lifecycle.
- Parent leave requests and homeroom/admin approval.
- Approved leave reconciliation from `ABSENT` to `EXCUSED`.
- Guardian notifications for attendance changes.

### Assessment and grading

- Assessments, categories, maximum score and weight.
- Bulk score entry.
- Draft/submitted/locked workflow.
- Score revision history.
- Guardian notifications when results are submitted.
- Weighted student reporting.

### Student timeline and conduct

- Unified role-aware timeline across attendance, leave decisions, published scores, teacher comments, conduct and announcements.
- Deterministic keyset pagination and school-timezone date filters.
- Dedicated conduct categories, severity and explicit staff/guardian/student visibility.
- Optimistic conduct corrections with append-only before/after revisions and correlated audit entries.
- Relationship-scoped authorization that prevents multi-role privilege escalation.

### Communication and parent meetings

- School/class announcements.
- Teacher comments.
- Parent/teacher conversations and messages.
- Durable notification center with independent in-app and realtime preferences.
- Realtime WebSocket delivery from NATS events.
- Parent meetings scoped to school, classroom or student with server-derived guardian invitees.
- RSVP, optional one-to-one appointment slots, attendance, reminders and follow-up outcomes.
- Meeting audience snapshots so historical visibility is not rewritten by later enrollment changes.
- Parent-meeting notifications use the internal `MEETING` notification category; external SMS/Zalo/calendar/video integrations remain optional future adapters.

### Reporting and audit

- School/teacher KPI dashboards and time-range trends.
- Student attendance and weighted score summary.
- Explainable rule-based academic-risk indicators.
- Append-only sensitive audit history for attendance, scores, workflow transitions, conduct corrections and parent-meeting actions.
- Correlation IDs and stable API error envelopes.

## Web workspaces

The SPA adapts to the authenticated role:

- **Admin:** dashboard, academic-resource management, student records, meeting management and account/security operations.
- **Teacher:** assigned classes, attendance, assessment/score entry, leave-review, student timeline/conduct and authorized parent meetings.
- **Parent:** child reports/timeline, leave requests, meeting invitations/RSVP/slot booking and notifications.
- **Student:** personal academic report/timeline and explicitly shared meeting information.
- **All roles:** announcements, authorized conversations, meeting workspace, notification center and security/session controls.

## Repository layout

```text
.
├── apps/
│   └── web/                         # React + Vite + Nginx
├── docs/
│   ├── 01-product-and-scope.md
│   ├── 02-architecture.md
│   ├── 03-domain-and-data.md
│   ├── 04-api-and-events.md
│   ├── 05-scalability-and-reliability.md
│   ├── 06-roadmap.md
│   ├── 07-security-and-operations.md
│   ├── 08-api-reference.md
│   ├── 09-student-timeline-and-conduct.md
│   └── 10-parent-meetings.md
├── services/
│   ├── core-api/                    # Java / Spring Boot
│   └── realtime-gateway/            # Go / Gin
├── infra/
│   └── docker-compose.yml
├── scripts/
│   └── ci-smoke.sh
└── .github/workflows/ci.yml
```

## Run locally

Prerequisite: Docker with Compose v2.

```bash
docker compose -f infra/docker-compose.yml up --build -d
```

Open:

```text
Web UI                  http://localhost:3000
Java Core API           http://localhost:8080
Go realtime gateway     http://localhost:8090
NATS monitoring         http://localhost:8222
```

Local bootstrap account:

```text
Email:    admin@eclassroom.local
Password: Admin123!
```

The credentials above are intentionally local-development values. Replace all passwords and `APP_JWT_SECRET` before any hosted deployment.

To reset local data:

```bash
docker compose -f infra/docker-compose.yml down -v
```

## CI quality gates

Every pull request builds and validates all three application layers:

```text
Java: mvn verify
Go:   mod verify + tidy check + gofmt + tests + build
Web:  TypeScript typecheck + Vite production build
```

The final job builds the complete Docker Compose stack, waits for Nginx, authenticates through the same `/api` path used by the browser, reads `/me`, and checks Java and Go readiness probes. It also exercises the PostgreSQL outbox → JetStream → Go consumer path.

## Documentation

- Product scope: [`docs/01-product-and-scope.md`](docs/01-product-and-scope.md)
- Architecture: [`docs/02-architecture.md`](docs/02-architecture.md)
- Domain/data: [`docs/03-domain-and-data.md`](docs/03-domain-and-data.md)
- API/events: [`docs/04-api-and-events.md`](docs/04-api-and-events.md)
- Scaling/reliability: [`docs/05-scalability-and-reliability.md`](docs/05-scalability-and-reliability.md)
- Roadmap: [`docs/06-roadmap.md`](docs/06-roadmap.md)
- Security/operations: [`docs/07-security-and-operations.md`](docs/07-security-and-operations.md)
- API reference: [`docs/08-api-reference.md`](docs/08-api-reference.md)
- Student timeline/conduct: [`docs/09-student-timeline-and-conduct.md`](docs/09-student-timeline-and-conduct.md)
- Parent meetings: [`docs/10-parent-meetings.md`](docs/10-parent-meetings.md)

## Engineering principles

1. Transactional business state and outbox entries commit together.
2. Realtime delivery is an enhancement; durable notifications remain queryable after reconnect.
3. Services do not bypass ownership to mutate another service's domain tables.
4. Authorization is checked at the resource level, not only by role.
5. Compute stays stateless where practical for horizontal scaling.
6. Consumers and retried mutations are designed for idempotency where side effects require it.
7. Optimize from measurements, with bounded batches, cursor-friendly indexes and backpressure on realtime connections.
