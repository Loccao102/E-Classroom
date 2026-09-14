# 11. Bulk import, export and school onboarding

## Purpose

V1 bulk data tooling is designed for practical school onboarding without turning file upload into an uncontrolled database writer.

The core invariant is:

> Upload and preview never mutate academic business tables. Only an explicit, authorized `commit` transition may apply validated rows.

The feature is intentionally limited to bounded CSV/XLSX jobs and natural keys that a school administrator can understand. Clients never need to put internal UUIDs into import files.

## Import types and natural keys

| Type | Natural key / reference | Template columns |
| --- | --- | --- |
| `STUDENT` | `student_code` | `student_code`, `full_name`, `date_of_birth`, `gender`, `email` |
| `TEACHER` | `teacher_code` | `teacher_code`, `full_name`, `email`, `phone` |
| `GUARDIAN` | `guardian_email` | `guardian_email`, `full_name`, `phone` |
| `GUARDIAN_LINK` | student code + guardian email | `student_code`, `guardian_email`, `relationship`, `primary_contact` |
| `ENROLLMENT` | student + classroom + academic year | `student_code`, `classroom_code`, `academic_year`, `start_date` |

Dates use ISO `YYYY-MM-DD`. CSV is UTF-8 and supports the BOM commonly emitted by Excel. XLSX is parsed as a real OOXML workbook rather than a renamed CSV file.

Headers are normalized to lowercase underscore names. Blank and duplicate headers are rejected. The V1 templates are the canonical mapping contract; arbitrary user-defined column mapping is deliberately deferred until there is a real migration need.

## Job lifecycle

```text
QUEUED
  -> PROCESSING
      -> PREVIEW_READY
      -> VALIDATION_FAILED
      -> FAILED

PREVIEW_READY
  -> COMMITTING
      -> COMMITTED
```

A job stores the uploaded source bytes only while it is waiting to be parsed. Once processing finishes or fails, `source_blob` is cleared. Parsed staging rows retain only normalized/raw JSON plus row-level warnings and errors required for review/audit.

### Limits

- maximum source file: 5 MB
- maximum data rows: 5,000
- maximum columns: 64
- row preview endpoint is paginated/bounded to 200 rows per request
- parsing is dispatched asynchronously
- staged rows are persisted with bounded JDBC batch writes

These limits make the in-memory parser explicitly bounded. A future high-volume migration service can use streaming XLSX/CSV ingestion without changing the public staged-job contract.

## Validation

Validation occurs before any academic mutation and includes:

- required fields and ISO dates
- email shape
- duplicate natural keys inside the same file
- existing-profile upsert warnings
- same-school student/guardian/classroom references
- cross-tenant reference rejection
- active enrollment conflict checks
- idempotency-key reuse checks

A file with even one invalid row receives `VALIDATION_FAILED`; the operator must correct the file and start/retry a clean job. V1 intentionally does not partially commit the valid subset of an invalid file.

## Idempotency and retry

`POST /schools/{schoolId}/imports` requires an `Idempotency-Key` header.

The pair `(school_id, idempotency_key)` is unique. The server stores the source SHA-256 and import type:

- same key + same type + same bytes returns the existing job
- same key with changed type or source returns `IDEMPOTENCY_KEY_REUSED`
- retrying `commit` after the job is already `COMMITTED` is a no-op result

Commit also checks the job version so two operators cannot race the same preview state.

## Account and password policy

Bulk import is an academic-data onboarding flow, not a credential distribution channel.

- import files never accept a password column
- staging never stores plaintext passwords
- bulk commit never returns a batch of temporary credentials
- imported people may exist as academic profiles without a login account
- account provisioning/reset remains owned by the account-security workflow, which hashes generated temporary passwords, forces password change and revokes old sessions when appropriate

This separation prevents an onboarding spreadsheet from becoming a credential dump.

## Import API

```text
POST /api/v1/schools/{schoolId}/imports?type=STUDENT
     Content-Type: multipart/form-data
     Idempotency-Key: <client-generated-key>
     file=<csv-or-xlsx>

GET  /api/v1/schools/{schoolId}/imports
GET  /api/v1/schools/{schoolId}/imports/{jobId}
GET  /api/v1/schools/{schoolId}/imports/{jobId}/rows?status=INVALID&limit=100&offset=0
POST /api/v1/schools/{schoolId}/imports/{jobId}/commit
GET  /api/v1/schools/{schoolId}/imports/templates/{type}?format=CSV|XLSX
```

Commit body:

```json
{
  "version": 1
}
```

All import operations are restricted to school administrators.

## Export API

```text
GET /api/v1/schools/{schoolId}/exports/STUDENTS?format=XLSX
GET /api/v1/schools/{schoolId}/exports/TEACHERS?format=CSV
GET /api/v1/schools/{schoolId}/exports/GUARDIANS?format=XLSX

GET /api/v1/schools/{schoolId}/exports/ROSTER?classroomId=<uuid>&format=XLSX
GET /api/v1/schools/{schoolId}/exports/ATTENDANCE?classroomId=<uuid>&from=YYYY-MM-DD&to=YYYY-MM-DD&format=CSV
GET /api/v1/schools/{schoolId}/exports/SCORES?classroomId=<uuid>&from=YYYY-MM-DD&to=YYYY-MM-DD&format=XLSX
```

People exports are school-admin only. Classroom exports use the same class-teacher-or-admin resource authorization as operational class data. Score export includes only published (`SUBMITTED`/`LOCKED`) assessments.

Date-scoped exports default to the previous 30 days and are capped at 400 days.

Every successful export appends an `integration.export_audit` row with actor, school, export type, scope, parameters, format and row count.

## Admin UI

The admin workspace presents bulk onboarding as four explicit steps:

1. choose an import type and inspect the canonical columns
2. download a CSV/XLSX template and upload the populated file
3. review totals plus row-level errors/warnings from staging
4. commit only a clean preview

Recent jobs remain reopenable so a long-running parse does not require the operator to keep the same screen open. Export controls share the same workspace and hide raw UUID entry behind classroom selectors.

## Test contract

Coverage includes:

- Excel-style UTF-8 BOM CSV parsing
- native XLSX parsing
- duplicate/blank header rejection
- partial-invalid import without academic mutation
- deterministic idempotency replay and key-reuse conflict
- clean preview followed by one explicit commit
- commit replay without duplicate academic rows
- tenant-isolated enrollment references
- export authorization and audit persistence

External SIS connectors, SFTP feeds, scheduled exports and arbitrary column mapping remain future adapters rather than V1 dependencies.
