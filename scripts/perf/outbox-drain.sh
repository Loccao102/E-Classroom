#!/usr/bin/env bash
set -euo pipefail

compose=(docker compose -f infra/docker-compose.yml)
count="${OUTBOX_COUNT:-100}"
timeout_seconds="${OUTBOX_TIMEOUT_SECONDS:-30}"

if ! [[ "$count" =~ ^[0-9]+$ ]] || (( count < 1 || count > 10000 )); then
  echo "OUTBOX_COUNT must be an integer between 1 and 10000" >&2
  exit 2
fi
if ! [[ "$timeout_seconds" =~ ^[0-9]+$ ]] || (( timeout_seconds < 1 || timeout_seconds > 300 )); then
  echo "OUTBOX_TIMEOUT_SECONDS must be an integer between 1 and 300" >&2
  exit 2
fi

run_id="perf-outbox-$(date +%s)-$RANDOM"

read -r school_id user_id < <(${compose[@]} exec -T postgres psql -U eclassroom -d eclassroom -AtF' ' -c "
SELECT m.school_id, u.id
FROM identity.users u
JOIN identity.school_memberships m ON m.user_id=u.id
WHERE u.email='admin@eclassroom.local'
ORDER BY m.created_at
LIMIT 1;
" | tr -d '\r')

if [[ -z "${school_id:-}" || -z "${user_id:-}" ]]; then
  echo "Bootstrap school/admin not found" >&2
  exit 1
fi

${compose[@]} exec -T postgres psql -U eclassroom -d eclassroom -v ON_ERROR_STOP=1 \
  -v school_id="$school_id" -v user_id="$user_id" -v run_id="$run_id" -v event_count="$count" <<'SQL'
WITH generated AS (
  SELECT gen_random_uuid() AS event_id, gen_random_uuid() AS aggregate_id, n
  FROM generate_series(1, :'event_count'::int) AS n
)
INSERT INTO integration.outbox_events(
  id, aggregate_type, aggregate_id, event_type, event_version,
  school_id, correlation_id, payload, occurred_at
)
SELECT
  g.event_id,
  'PERFORMANCE_TEST',
  g.aggregate_id,
  'performance.outbox',
  1,
  :'school_id'::uuid,
  :'run_id' || '-' || g.n::text,
  jsonb_build_object(
    'eventId', g.event_id::text,
    'correlationId', :'run_id' || '-' || g.n::text,
    'eventType', 'performance.outbox',
    'eventVersion', 1,
    'schoolId', :'school_id',
    'recipients', jsonb_build_array(:'user_id'),
    'data', jsonb_build_object(
      'entityId', g.aggregate_id::text,
      'entityType', 'PERFORMANCE_TEST',
      'title', 'Performance outbox event',
      'body', 'Outbox drain benchmark'
    )
  ),
  NOW()
FROM generated g;
SQL

started_at="$(date +%s)"
deadline=$((started_at + timeout_seconds))
remaining="$count"
processed=0

while (( $(date +%s) <= deadline )); do
  remaining="$(${compose[@]} exec -T postgres psql -U eclassroom -d eclassroom -Atc \
    "SELECT COUNT(*) FROM integration.outbox_events WHERE correlation_id LIKE '${run_id}-%' AND published_at IS NULL" | tr -d '\r')"
  processed="$(${compose[@]} exec -T postgres psql -U eclassroom -d eclassroom -Atc \
    "SELECT COUNT(*) FROM integration.consumer_inbox i JOIN integration.outbox_events o ON o.id=i.event_id WHERE o.correlation_id LIKE '${run_id}-%'" | tr -d '\r')"
  if [[ "$remaining" == "0" && "$processed" == "$count" ]]; then
    break
  fi
  sleep 1
done

finished_at="$(date +%s)"
elapsed=$((finished_at - started_at))
if (( elapsed < 1 )); then elapsed=1; fi
rate=$((count / elapsed))

echo "Outbox run: $run_id"
echo "Events: $count"
echo "Elapsed: ${elapsed}s"
echo "Approx throughput: ${rate} events/s"
echo "Unpublished: $remaining"
echo "Consumer processed: $processed"

if [[ "$remaining" != "0" || "$processed" != "$count" ]]; then
  echo "Outbox drain did not finish within ${timeout_seconds}s" >&2
  exit 1
fi
