#!/usr/bin/env sh
set -eu

compose="docker compose -f infra/docker-compose.yml"
$compose up -d --build

ready=0
i=0
while [ "$i" -lt 60 ]; do
  if curl -fsS http://localhost:3000/healthz >/dev/null 2>&1; then ready=1; break; fi
  i=$((i+1))
  sleep 3
done
[ "$ready" -eq 1 ]

login=$(curl -fsS -X POST http://localhost:3000/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: smoke-login-correlation' \
  -d '{"email":"admin@eclassroom.local","password":"Admin123!"}')
token=$(printf '%s' "$login" | jq -r '.accessToken')
[ -n "$token" ]
[ "$token" != "null" ]

me=$(curl -fsS http://localhost:3000/api/v1/me -H "Authorization: Bearer $token")
printf '%s' "$me" | jq -e '.user.email == "admin@eclassroom.local"' >/dev/null
printf '%s' "$me" | jq -e '.memberships | length > 0' >/dev/null

curl -fsS http://localhost:8080/actuator/health/readiness | jq -e '.status == "UP"' >/dev/null
curl -fsS http://localhost:8090/ready | jq -e '.status == "ready"' >/dev/null

# The realtime gateway owns the JetStream topology and must expose both
# the durable event stream and durable dead-letter stream before traffic.
jsz=$(curl -fsS 'http://localhost:8222/jsz?streams=true&consumers=true')
printf '%s' "$jsz" | jq -e '.. | strings | select(. == "ECLASSROOM_EVENTS")' >/dev/null
printf '%s' "$jsz" | jq -e '.. | strings | select(. == "ECLASSROOM_DLQ")' >/dev/null
printf '%s' "$jsz" | jq -e '.. | strings | select(. == "realtime-gateway-v1")' >/dev/null

# Exercise PostgreSQL outbox -> Java JetStream publisher -> Go durable consumer.
user_id=$(printf '%s' "$me" | jq -r '.user.id')
school_id=$(printf '%s' "$me" | jq -r '.memberships[0].school_id')
event_id=$(cat /proc/sys/kernel/random/uuid)
aggregate_id=$(cat /proc/sys/kernel/random/uuid)
correlation_id="smoke-event-$event_id"
payload=$(jq -cn \
  --arg eventId "$event_id" \
  --arg correlationId "$correlation_id" \
  --arg schoolId "$school_id" \
  --arg recipient "$user_id" \
  --arg aggregateId "$aggregate_id" \
  '{eventId:$eventId,correlationId:$correlationId,eventType:"smoke.realtime",eventVersion:1,schoolId:$schoolId,recipients:[$recipient],data:{entityId:$aggregateId,entityType:"SMOKE_TEST",title:"CI smoke",body:"Outbox to JetStream to Go"}}')

$compose exec -T postgres psql -U eclassroom -d eclassroom -v ON_ERROR_STOP=1 \
  -v event_id="$event_id" \
  -v aggregate_id="$aggregate_id" \
  -v school_id="$school_id" \
  -v correlation_id="$correlation_id" \
  -v payload="$payload" <<'SQL'
INSERT INTO integration.outbox_events(
  id, aggregate_type, aggregate_id, event_type, event_version,
  school_id, correlation_id, payload, occurred_at
) VALUES (
  :'event_id'::uuid, 'SMOKE_TEST', :'aggregate_id'::uuid,
  'smoke.realtime', 1, :'school_id'::uuid, :'correlation_id',
  :'payload'::jsonb, NOW()
);
SQL

published=0
processed=0
i=0
while [ "$i" -lt 40 ]; do
  published=$($compose exec -T postgres psql -U eclassroom -d eclassroom -Atc \
    "SELECT CASE WHEN published_at IS NOT NULL THEN 1 ELSE 0 END FROM integration.outbox_events WHERE id='$event_id'::uuid" | tr -d '\r')
  processed=$(curl -fsS http://localhost:8090/ready | jq -r '.events.processed // 0')
  if [ "$published" = "1" ] && [ "$processed" -ge 1 ]; then break; fi
  i=$((i+1))
  sleep 1
done
[ "$published" = "1" ]
[ "$processed" -ge 1 ]
