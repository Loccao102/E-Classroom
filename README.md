# E-Classroom

> A scalable electronic school communication and student-performance platform connecting schools, teachers, students and parents.

E-Classroom is being designed **docs-first** as a polyglot backend. Java owns the transaction-heavy school domain while Go/Gin owns high-concurrency engagement workloads such as realtime delivery and notification fan-out.

## Architecture at a glance

```text
Web / Mobile
    |
    | HTTPS / WebSocket / SSE
    v
+----------------------+        +-------------------------+
| Java Core API        |        | Go Realtime Gateway     |
| Spring Boot          |        | Gin                     |
|----------------------|        |-------------------------|
| Identity & Access    |        | WebSocket / SSE         |
| School Management    |        | Presence / connections  |
| Academic Structure   |        | Realtime fan-out        |
| Enrollment           |        | Notification delivery   |
| Attendance           |        +------------+------------+
| Grading              |                     |
| Parent Communication |                     |
| Audit                 |                     |
+----------+-----------+                     |
           |                                 |
           | PostgreSQL transaction          | Redis ephemeral state
           | + transactional outbox          |
           v                                 v
      +---------+      domain events      +-------+
      |PostgreSQL| ---------------------> | NATS  |
      +---------+                         +---+---+
                                              |
                                              v
                                    +-------------------+
                                    | async consumers   |
                                    | notification/jobs |
                                    +-------------------+
```

## Why Java + Go?

The split is based on workload characteristics rather than language preference:

- **Java / Spring Boot**: rich domain model, transactions, authorization, validation, reporting, auditability and business workflows.
- **Go / Gin**: high-concurrency network workloads, realtime connections, notification fan-out and lightweight asynchronous workers.
- **PostgreSQL**: source of truth for transactional data.
- **Redis**: cache, presence, rate-limit state and short-lived realtime data.
- **NATS JetStream**: durable event delivery between the transactional core and asynchronous/realtime workloads.

The initial Java backend is a **modular monolith**, not a collection of tiny services. Modules can be extracted only when load, ownership or deployment requirements justify it.

## Repository layout

```text
.
├── docs/
│   ├── 01-product-and-scope.md
│   ├── 02-architecture.md
│   ├── 03-domain-and-data.md
│   ├── 04-api-and-events.md
│   ├── 05-scalability-and-reliability.md
│   └── 06-roadmap.md
├── services/
│   ├── core-api/              # Java / Spring Boot
│   └── realtime-gateway/      # Go / Gin
├── infra/
│   └── docker-compose.yml
└── .github/workflows/
```

## Initial bounded contexts

- Identity & Access
- School / Tenant Management
- Academic Years & Semesters
- Classes & Enrollment
- Teachers & Teaching Assignments
- Timetable
- Attendance & Leave Requests
- Assessments & Grades
- Parent–Student relationships
- Announcements & Comments
- Notifications
- Audit & Reporting

## Engineering principles

1. **Docs before code** for domain boundaries and contracts.
2. **Modular monolith first**, extract services later.
3. **Database ownership is explicit**; services do not write another service's tables.
4. **Transactional outbox** for reliable domain events.
5. **Stateless compute** wherever possible for horizontal scaling.
6. **Tenant-aware by design** using `school_id` in domain boundaries and access control.
7. **Observability from day one** with structured logs, metrics and tracing.
8. **Backward-compatible contracts** for APIs and events.
9. **Idempotent consumers** for asynchronous processing.
10. **Optimize from measurements**, but design hot paths so they can be partitioned and cached.

## Current implementation stage

- [x] Repository initialized
- [x] Architecture direction selected
- [ ] Domain documentation
- [ ] Local infrastructure
- [ ] Java Core API bootstrap
- [ ] Go realtime gateway bootstrap
- [ ] CI
- [ ] First vertical slice: School -> Class -> Student -> Attendance -> Parent notification

See [`docs/`](docs/) for the detailed design.