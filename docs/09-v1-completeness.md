# 09. V1 Product Completeness

This document defines what **complete V1** means for E-Classroom.

It deliberately separates product/domain completion from deployment and external-provider integration. A feature is not considered complete merely because a table or REST endpoint exists; the authorized workflow, usable UI, audit/event behavior and tests must all exist.

## Product boundary

E-Classroom V1 is an electronic school communication and student-performance platform connecting:

- school administrators
- teachers
- homeroom teachers
- students
- guardians/parents

The V1 goal is not to become a full ERP/LMS. It should make the normal school-to-family communication loop usable without requiring an external SMS/Zalo provider.

## Completion matrix

| Area | Backend/domain | UI | V1 status |
| --- | --- | --- | --- |
| Authentication + refresh rotation | Implemented | Basic login | Partial |
| Multi-school membership/RBAC | Implemented | School switcher | Implemented |
| Academic years/semesters/classes/subjects | Implemented | Generic admin console | Partial |
| Students/teachers/guardians | Implemented | Generic admin console | Partial |
| Guardian linking/enrollment | Implemented API | No guided product flow | Partial |
| Teaching assignments/timetable | Implemented | Limited/basic | Partial |
| Attendance | Production-shaped workflow | Functional teacher grid | Implemented core; UX improvement needed |
| Leave request | Production-shaped workflow | Parent submit + teacher review | Implemented core; UX improvement needed |
| Assessments/grading | Production-shaped workflow | Functional score grid | Implemented core; UX improvement needed |
| Announcements | Skeleton / Phase 6 hardening | Basic | In progress |
| Teacher comments | Skeleton / Phase 6 hardening | Limited | In progress |
| In-app SMS/notifications | Durable + realtime baseline / Phase 6 hardening | Basic feed | In progress |
| Parent-teacher conversations | Skeleton / Phase 6 hardening | Basic | In progress |
| Student timeline | Partial data exists | Missing unified view | Missing |
| Conduct/behavior records | Missing dedicated domain | Missing | Missing |
| Parent meetings | Missing | Missing | Missing |
| Reporting/dashboard | Basic synchronous aggregates | Raw/basic tables and JSON | Partial |
| Audit history | Backend exists for critical mutations | No usable audit UI | Partial |
| Account self-service/security | Login/refresh only | Missing | Partial |
| Bulk import/export | Missing product workflow | Missing | Missing |
| Responsive role-specific UX | N/A | Prototype/demo-level | Missing |
| Automated integration/smoke CI | Implemented | N/A | Implemented |

## V1 must-have backlog

### 1. Communication and internal SMS

V1 uses **in-app durable notifications + realtime delivery** as its SMS-like channel.

Must have:

- category preferences
- unread/read state
- notification filters and pagination
- announcements
- teacher comments with explicit visibility
- authorized parent/teacher conversations
- message unread state
- useful notification/inbox UI

External SMS Brandname, Zalo OA, email and mobile push are not required for V1.

### 2. Productized role UX

Replace the current functional console with role-oriented product screens.

Admin:

- real forms/drawers instead of browser prompts
- student/teacher/guardian/class management
- guided enrollment and guardian linking
- teaching assignment and timetable management
- search, filters and pagination

Teacher:

- today/timetable dashboard
- fast attendance
- grading workflow with lifecycle status
- leave-review inbox
- student detail/timeline
- communication inbox

Parent:

- child switcher
- attendance, scores, comments and announcements at a glance
- leave request/history
- teacher conversation
- notification preferences/feed

Student:

- timetable
- attendance
- published scores
- allowed comments/announcements
- notifications

### 3. Reporting

Must have useful, explainable views rather than raw JSON:

- school attendance trends
- class/subject score trends
- student attendance and published score trends
- students requiring attention with contributing factors
- date/semester/class filters

Heavy reporting queries should move toward read models/projections when measurement shows synchronous aggregation becoming expensive.

### 4. Student timeline and conduct

A student detail page should combine useful school-family history:

- attendance events
- leave decisions
- published scores
- teacher comments
- conduct/behavior records
- important communication events

Conduct needs a dedicated auditable record rather than overloading free-form comments.

### 5. Parent meetings

Minimum V1 meeting support:

- school/teacher creates parent-meeting event or appointment slots
- target class/student/guardian audience
- parent can see invitation/time/status
- teacher/admin can record attendance and short outcome/notes
- reminders use the same internal notification pipeline

Video-call integration is not required.

### 6. Account lifecycle and security

Before production use:

- logout/revoke current refresh token
- revoke all sessions
- change password
- administrator-assisted password reset / temporary password flow
- sensible login throttling/lockout protection
- account status/session audit where useful

External email/SMS password recovery can be added later; V1 may use an admin-assisted reset flow.

### 7. Import/export and school onboarding

Real schools should not have to create hundreds of records manually.

Minimum:

- CSV/XLSX import for students, guardians and teachers
- validation preview before commit
- row-level errors
- idempotent/re-runnable import strategy
- export for common rosters/attendance/score views

Large imports should be processed as background jobs once file sizes justify it.

## V1 quality gates

A V1 release is complete only when:

1. all primary roles can finish their normal workflows without entering raw UUIDs;
2. core workflows have authorization and tenant-boundary integration tests;
3. critical mutations are auditable;
4. notifications are durable even when realtime delivery fails;
5. parent/student screens are usable on mobile widths;
6. no core workflow relies on browser `prompt()`/raw JSON as the primary UX;
7. large lists use bounded pagination/search;
8. CI runs Java/PostgreSQL, Go, Web and full-stack smoke successfully;
9. secrets are not committed;
10. deployment/provider-specific work remains replaceable through configuration/adapters.

## Explicitly deferred from V1

The following are intentionally not blockers for product/domain V1 completion:

- public cloud deployment choice
- production domain/DNS/TLS setup
- SMS Brandname vendor
- Zalo OA
- external email provider
- mobile push provider
- native iOS/Android application
- online payments/tuition collection
- canteen/library/transport management
- full LMS course/content/homework engine
- opaque ML risk scoring

## Recommended implementation order from the current state

```text
Phase 6 communication + internal SMS
 -> role-specific frontend productization
 -> Phase 7 reporting/dashboard
 -> student timeline + conduct
 -> parent meetings
 -> account lifecycle/security hardening
 -> import/export/onboarding
 -> performance/load/accessibility hardening
 -> optional advanced AI/integrations
```

This order keeps the application usable throughout development while avoiding external-provider decisions before the core product is ready.
