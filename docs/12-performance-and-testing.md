# 12. Performance and Testing Hardening

This document defines the V1 performance/testing gate for E-Classroom. It is deliberately separate from product-feature CI so routine changes remain fast while performance regressions still have a repeatable, measurable path.

## Test layers

### Normal CI

Every pull request still runs:

- Java unit + PostgreSQL/Testcontainers integration tests
- Go format, module integrity, tests and build
- React/TypeScript production build
- full Docker Compose smoke test
- PostgreSQL outbox -> JetStream -> Go consumer path

These tests answer: **is the system correct and deployable?**

### Performance workflow

`.github/workflows/performance.yml` runs automatically only for pull requests whose head branch starts with `perf/`. After merge it can also run manually and on the weekly schedule.

It contains two independent jobs:

1. `Bulk import 5k benchmark`
   - PostgreSQL 17 Testcontainers
   - stages 5,000 student rows
   - validates the entire file
   - commits all rows through the production import services
   - verifies every staging row has a committed entity id
   - 20 second validation budget
   - 20 second commit budget

2. `API and realtime load tests`
   - boots the complete Docker Compose stack
   - runs k6 from the pinned `grafana/k6:1.8.0` image
   - authenticated read traffic against `/me`, notification count and dashboard reporting
   - authenticated realtime WebSocket connections against the Go gateway

## k6 budgets

### Authenticated API reads

Quick profile:

- 20 constant VUs
- 20 seconds

Full profile:

- 75 constant VUs
- 90 seconds

Thresholds:

- HTTP failure rate `< 1%`
- p95 `< 500 ms`
- p99 `< 1000 ms`
- checks `> 99%`

The scenario intentionally mixes identity, notification and reporting reads rather than benchmarking only a health endpoint.

### Realtime WebSocket connections

Quick profile:

- 50 constant VUs
- 20 seconds
- each connection held for about 5 seconds

Full profile:

- 250 constant VUs
- 60 seconds
- each connection held for about 10 seconds

Thresholds:

- connection failure rate `< 1%`
- WebSocket connect p95 `< 750 ms`
- connect p99 `< 1500 ms`
- checks `> 99%`

These numbers are a regression budget for GitHub-hosted runners, not a claim about production maximum capacity. Higher-scale 1k-10k WebSocket tests should run on dedicated load generators so the generator does not become the bottleneck.

## Concurrency correctness

Performance without correctness is not acceptable. The normal Java integration suite includes a real transaction race for parent-meeting slot booking:

- two guardian transactions begin concurrently
- both target the same slot and same starting version
- exactly one booking may commit
- the loser must receive a conflict
- exactly one booking audit entry is persisted
- slot version increments exactly once

The test uses `TransactionTemplate` with separate connections so `SELECT ... FOR UPDATE` and transactional behavior are actually exercised. It does not rely on two sequential stale-version calls.

## Bulk import optimization

The original student/teacher commit path performed row-at-a-time work:

```text
for each staged row:
  SELECT existing entity
  INSERT or UPDATE entity
  UPDATE staging row
```

At 5,000 rows this creates many database round trips.

The hardened path now uses JDBC batch upserts for import types whose natural key is backed by a database uniqueness constraint:

- `STUDENT`: `(school_id, student_code)`
- `TEACHER`: `(school_id, teacher_code)`

After the batch upsert, one `UPDATE ... FROM` maps staging rows to the committed entity IDs.

`GUARDIAN`, `GUARDIAN_LINK` and `ENROLLMENT` remain on the reference-aware path because their semantics are different and should not be weakened purely for throughput.

## Interpreting regressions

A failed performance workflow should not be fixed by simply raising thresholds. Investigate in this order:

1. functional errors / authorization failures
2. database query count or missing indexes
3. connection-pool saturation
4. repeated serialization/deserialization
5. N+1 access patterns
6. lock contention or oversized transactions
7. GitHub runner variance

Only change a budget when the product workload or test environment intentionally changes, and document the reason in the PR.

## Running locally

Start the stack:

```bash
docker compose -f infra/docker-compose.yml up -d --build
```

Run the quick API scenario:

```bash
docker run --rm --network host -v "$PWD:/work" -w /work \
  -e BASE_URL=http://localhost:3000 \
  grafana/k6:1.8.0 run performance/k6/api-read.js
```

Run the realtime scenario:

```bash
docker run --rm --network host -v "$PWD:/work" -w /work \
  -e BASE_URL=http://localhost:3000 \
  -e WS_BASE_URL=ws://localhost:8090 \
  grafana/k6:1.8.0 run performance/k6/realtime-ws.js
```

Run only the JVM performance integration tests:

```bash
cd services/core-api
mvn -B -ntp -Pperformance-tests verify
```

The `performance-tests` Maven profile skips the normal Surefire suite and executes only `*PerformanceIT` through Failsafe.
