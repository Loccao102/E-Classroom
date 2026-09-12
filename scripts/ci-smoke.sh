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
  -d '{"email":"admin@eclassroom.local","password":"Admin123!"}')
token=$(printf '%s' "$login" | jq -r '.accessToken')
[ -n "$token" ]
[ "$token" != "null" ]

me=$(curl -fsS http://localhost:3000/api/v1/me -H "Authorization: Bearer $token")
printf '%s' "$me" | jq -e '.user.email == "admin@eclassroom.local"' >/dev/null
printf '%s' "$me" | jq -e '.memberships | length > 0' >/dev/null

curl -fsS http://localhost:8080/actuator/health/readiness | jq -e '.status == "UP"' >/dev/null
curl -fsS http://localhost:8090/ready | jq -e '.status == "ready"' >/dev/null
