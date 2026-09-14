#!/usr/bin/env bash
set -euo pipefail

compose=(docker compose -f infra/docker-compose.yml)

required_indexes=(
  idx_attendance_sessions_reporting
  idx_attendance_records_reporting
  idx_assessments_reporting
  idx_scores_reporting
  idx_leave_requests_reporting
  idx_teacher_comments_timeline
  idx_timetable_reporting
  idx_enrollment_student_active
  idx_import_jobs_school_created
  idx_import_jobs_queue
  idx_import_rows_preview
  idx_export_audit_school_created
)

missing=0
for index in "${required_indexes[@]}"; do
  count="$(${compose[@]} exec -T postgres psql -U eclassroom -d eclassroom -Atc \
    "SELECT COUNT(*) FROM pg_indexes WHERE indexname='${index}'" | tr -d '\r')"
  if [[ "$count" != "1" ]]; then
    echo "missing index: ${index}" >&2
    missing=1
  fi
done

if [[ "$missing" != "0" ]]; then
  exit 1
fi

echo "All ${#required_indexes[@]} hot-path indexes are present."

echo "Largest current tables:"
${compose[@]} exec -T postgres psql -U eclassroom -d eclassroom -P pager=off -c "
SELECT schemaname, relname,
       n_live_tup,
       pg_size_pretty(pg_total_relation_size(format('%I.%I', schemaname, relname)::regclass)) AS total_size
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size(format('%I.%I', schemaname, relname)::regclass) DESC
LIMIT 15;
"

echo "Index usage snapshot:"
${compose[@]} exec -T postgres psql -U eclassroom -d eclassroom -P pager=off -c "
SELECT schemaname, relname, indexrelname, idx_scan
FROM pg_stat_user_indexes
WHERE indexrelname = ANY (ARRAY[
  'idx_attendance_sessions_reporting',
  'idx_attendance_records_reporting',
  'idx_assessments_reporting',
  'idx_scores_reporting',
  'idx_teacher_comments_timeline',
  'idx_import_rows_preview'
])
ORDER BY schemaname, relname, indexrelname;
"
