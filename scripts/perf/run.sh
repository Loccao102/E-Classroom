#!/usr/bin/env bash
set -euo pipefail

mode="${1:-all}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

compose=(docker compose -f infra/docker-compose.yml)
k6_image="${K6_IMAGE:-grafana/k6:2.2.0}"
base_url="${PERF_BASE_URL:-http://host.docker.internal:3000}"

cleanup() {
  if [[ "${KEEP_PERF_STACK:-0}" != "1" ]]; then
    "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_for_stack() {
  local ready=0
  for _ in $(seq 1 90); do
    if curl -fsS http://localhost:3000/healthz >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 3
  done
  if [[ "$ready" != "1" ]]; then
    echo "E-Classroom stack did not become ready" >&2
    "${compose[@]}" logs --no-color
    exit 1
  fi
}

run_k6() {
  local script="$1"
  shift
  docker run --rm \
    --add-host=host.docker.internal:host-gateway \
    -v "$root/tests/performance:/scripts:ro" \
    -e BASE_URL="$base_url" \
    -e WS_BASE_URL="$base_url" \
    "$@" \
    "$k6_image" run "/scripts/$script"
}

"${compose[@]}" up -d --build
wait_for_stack

case "$mode" in
  api)
    run_k6 api-read.js \
      -e API_VUS="${API_VUS:-20}" \
      -e API_DURATION="${API_DURATION:-30s}"
    ;;
  ws)
    run_k6 realtime-connections.js \
      -e WS_VUS="${WS_VUS:-50}" \
      -e WS_DURATION="${WS_DURATION:-30s}" \
      -e WS_HOLD_MS="${WS_HOLD_MS:-10000}"
    ;;
  import)
    run_k6 import-staging.js \
      -e IMPORT_ROWS="${IMPORT_ROWS:-1000}" \
      -e IMPORT_ITERATIONS="${IMPORT_ITERATIONS:-1}"
    ;;
  db)
    bash scripts/perf/db-sanity.sh
    ;;
  all)
    run_k6 api-read.js \
      -e API_VUS="${API_VUS:-20}" \
      -e API_DURATION="${API_DURATION:-30s}"
    run_k6 realtime-connections.js \
      -e WS_VUS="${WS_VUS:-50}" \
      -e WS_DURATION="${WS_DURATION:-30s}" \
      -e WS_HOLD_MS="${WS_HOLD_MS:-10000}"
    run_k6 import-staging.js \
      -e IMPORT_ROWS="${IMPORT_ROWS:-1000}" \
      -e IMPORT_ITERATIONS="${IMPORT_ITERATIONS:-1}"
    bash scripts/perf/db-sanity.sh
    ;;
  *)
    echo "usage: $0 [api|ws|import|db|all]" >&2
    exit 2
    ;;
esac
