# 09. V1 Product Completeness

This document defines what **complete V1** means for E-Classroom.

A feature is not considered complete because a table or REST endpoint exists. The authorized workflow, usable role-specific UI, audit/event behavior and integration tests must exist as well. Deployment and external-provider decisions remain separate from product/domain completion.

## Product boundary

E-Classroom V1 connects school administrators, teachers, students and guardians around academic setup, attendance, grading, communication and school-family follow-up. It is deliberately not a full ERP/LMS.

## Completion matrix

| Area | Backend/domain | UI | V1 status |
| --- | --- | --- | --- |
| Authentication + rotating refresh sessions | Implemented | Login + security workspace | Implemented |
| Multi-school membership/RBAC | Implemented | School switcher | Implemented |
| Academic years/semesters/classes/subjects | Implemented | Guided admin setup | Implemented |
| Students/teachers/guardians | Implemented | Admin management + bulk onboarding | Implemented |
| Guardian linking/enrollment | Implemented | Guided setup + bulk import | Implemented |
| Teaching assignments/timetable | Implemented | Guided setup | Implemented |
| Attendance | Versioned workflow + audit | Fast teacher workflow | Implemented |
| Leave request | Submit/review/reconcile workflow | Parent + teacher flows | Implemented |
| Assessments/grading | Draft/submit/lock + revisions | Teacher score workflow | Implemented |
| Announcements/comments | Authorized + visibility-aware | Communication workspace | Implemented |
| In-app SMS/notifications | Durable + realtime + preferences | Notification center | Implemented |
| Parent-teacher conversations | Authorized conversations/read state | Inbox UI | Implemented |
| Reporting/dashboard | Explainable role-aware aggregates | School/teacher/student dashboards | Implemented |
| Student timeline/conduct | Unified timeline + auditable conduct revisions | Role-aware student record | Implemented |
| Parent meetings | Audience snapshot, RSVP, slots, attendance, outcomes | Multi-role meeting workspace | Implemented |
| Account lifecycle/security | Session revoke, change/reset password, lockout/audit | Security + admin management | Implemented |
| Bulk import/export | Staged CSV/XLSX jobs + explicit commit + audit | Guided import/export workspace | Implemented in #16 |
| Responsive role-specific UX | Product shell + role screens | Mobile/tablet responsive | Implemented core |
| Automated integration/smoke CI | Java/PostgreSQL + Go + Web + stack smoke | N/A | Implemented |

## V1 workflow coverage

### School onboarding

Administrators can create the academic structure manually through guided forms or use bounded CSV/XLSX imports for people, guardian relationships and enrollment. Bulk upload never writes directly to academic tables: it produces a validation preview and requires an explicit commit. Exports cover common people lists, class rosters, attendance and published scores.

### Teacher day-to-day workflow

Teachers can work only within assigned/homeroom resources: view their dashboard/timetable, mark attendance, grade assessments, review eligible leave requests, inspect student timelines/conduct, communicate with related families and manage authorized parent meetings.

### Parent workflow

Guardians can switch linked children and see attendance, scores, comments, risk factors, leave history, announcements, conversations, notifications and parent-meeting invitations/slots without exposure to unrelated students or families.

### Student workflow

Students receive self-scoped academic reporting, attendance/score history, allowed comments/announcements, timeline/conduct visibility, communication and explicitly student-visible meeting information.

## V1 quality gates

A V1 release is complete only when:

1. primary roles can finish normal workflows without entering raw UUIDs;
2. resource authorization and tenant boundaries are covered by PostgreSQL integration tests;
3. critical mutations and exports are auditable;
4. notifications remain durable when realtime delivery is unavailable;
5. parent/student surfaces work at mobile widths and teacher operations work on tablet/mobile;
6. no core workflow relies on browser `prompt()` or raw JSON as its primary UX;
7. lists and previews are bounded/paginated;
8. imports are staged, validated and idempotent before mutation;
9. CI runs Java/PostgreSQL, Go, Web and full-stack smoke successfully;
10. secrets and plaintext bulk credentials are never persisted in onboarding files/jobs;
11. deployment/provider-specific work remains replaceable through configuration/adapters.

## Remaining hardening after domain V1

The remaining work is primarily engineering hardening rather than a missing core product domain:

- measured performance/load testing at realistic school sizes
- query/read-model optimization where dashboards or histories prove expensive
- broader accessibility review including focus management, keyboard-only flows and screen-reader labeling
- deeper search/pagination on very large administrative collections
- operational observability/alerting and retention policies
- disaster-recovery/backup exercises
- optional SIS/provider adapters after real integration requirements are known

## Explicitly deferred from V1

The following remain intentionally outside the product/domain V1 boundary:

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
- Zoom/Meet/Teams/calendar integration
- arbitrary SIS column mapping before a concrete migration requires it

## Engineering direction after #16

```text
completed core domain V1
 -> performance/load/accessibility hardening
 -> operational readiness and observability
 -> optional external providers/SIS adapters
 -> advanced AI features only where they have a measurable product use case
```

The product should continue to prefer explainable workflows, server-side authorization, idempotent mutations and durable state over hidden automation that is difficult for a school operator to audit.
