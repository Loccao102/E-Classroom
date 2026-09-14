# 12. Performance and Testing Hardening

This phase starts after the V1 business workflows are functional. Its purpose is to make performance measurable and regressions reproducible without adding product features or prematurely changing architecture.

## Test pyramid

### Java unit and contract tests

Run without PostgreSQL-heavy workflow fixtures:

```bash
cd services/core-api
mvn -B -ntp -Punit-tests test
```

The `unit-tests` Maven profile excludes `*IntegrationTest` classes. It covers parser/contract/security-validator style tests and provides the fastest Java feedback loop.

### Java PostgreSQL integration tests

Run workflow and tenant-boundary tests against real PostgreSQL through Testcontainers:

```bash
cd services/core-api
mvn -B -ntp -Pintegration-tests test
```

The `integration-tests` profile runs only `*IntegrationTest` classes. These tests remain the source of truth for transaction behavior, Flyway migrations, locking, tenant boundaries, authorization and persistence semantics.

### Go realtime tests

CI runs:

```bash
go vet ./...
go test -race ./...
go build ./cmd/server
```

The race detector is deliberately part of normal CI because connection lifecycle, NATS delivery and websocket routing are concurrency-sensitive.

### Full-stack smoke

The existing smoke test is still the final functional gate:

```text
Nginx/web -> Java login -> /me -> Java readiness
          -> Go readiness
PostgreSQL outbox -> NATS JetStream -> Go durable consumer
```

It is not a load test. Its job is cross-service correctness.

## Performance suite

Performance scenarios live under `tests/performance/` and use k6. The repository pins the performance runner to `grafana/k6:2.2.0` so local and CI measurements use the same engine.

### API read path

`api-read.js` exercises authenticated high-frequency reads:

- `/me`
- school dashboard
- notifications page
- announcements

Baseline thresholds:

```text
request failure rate < 1%
/me p95 < 500 ms
dashboard p95 < 1000 ms
notifications p95 < 750 ms
announcements p95 < 750 ms
```

These are regression budgets for the local Docker baseline, not internet-facing production SLAs.

### Realtime connections

`realtime-connections.js` repeatedly opens authenticated websocket sessions and holds them for a bounded period.

Tracked metrics:

- websocket upgrade failures
- open/error behavior
- session duration

The baseline profile uses 50 concurrent VUs. The manual stress profile starts at 250; higher connection counts should be increased gradually while observing CPU, memory, Redis and Go process behavior.

### Import staging throughput

`import-staging.js` generates a valid student CSV in memory, uploads it through the real multipart endpoint and measures time until `PREVIEW_READY`.

It intentionally stops before Commit. This isolates parsing, validation, row staging and async job throughput without permanently growing the academic data set.

Supported profile sizes:

```text
smoke:       100 rows
baseline:  1,000 rows
stress:    5,000 rows
```

5,000 rows is the current V1 import boundary.

### Database sanity

`scripts/perf/db-sanity.sh` verifies that known hot-path indexes actually exist after Flyway migration and prints table-size/index-usage snapshots.

The check intentionally does not fail on low `idx_scan` counts in an empty/demo database; tiny tables can legitimately prefer sequential scans. Query-plan gates should be added only with representative seeded volume.

## Local runner

Run the complete Docker stack and all scenarios:

```bash
bash scripts/perf/run.sh all
```

Or run one layer:

```bash
bash scripts/perf/run.sh api
bash scripts/perf/run.sh ws
bash scripts/perf/run.sh import
bash scripts/perf/run.sh db
```

Useful overrides:

```bash
API_VUS=40 API_DURATION=60s bash scripts/perf/run.sh api
WS_VUS=200 WS_DURATION=60s WS_HOLD_MS=15000 bash scripts/perf/run.sh ws
IMPORT_ROWS=5000 bash scripts/perf/run.sh import
KEEP_PERF_STACK=1 bash scripts/perf/run.sh all
```

The runner starts from a clean Docker Compose environment by default and removes volumes afterwards so results are reproducible.

## GitHub Actions performance workflow

`.github/workflows/performance.yml` is deliberately separate from normal PR CI.

Profiles:

| Profile | API | WebSocket | Import |
| --- | --- | --- | --- |
| smoke | 5 VUs / 15s | 20 VUs / 15s | 100 rows |
| baseline | 20 VUs / 30s | 50 VUs / 30s | 1,000 rows |
| stress | 75 VUs / 60s | 250 VUs / 60s | 5,000 rows |

The scheduled run uses `baseline`. `stress` is intended for explicit manual runs only.

Normal pull-request CI should remain fast and deterministic; performance tests are noisy by nature and should not block every code change until a stable historical baseline exists.

## Interpreting results

Do not optimize from one number. A regression should be reproducible with:

1. the same git revision or an explicit before/after pair;
2. the same k6 image;
3. the same profile;
4. a clean database unless the test intentionally uses seeded scale data;
5. at least three runs when comparing small latency differences.

When a threshold fails, inspect the bottleneck before changing architecture:

```text
HTTP latency high
 -> Java request timing
 -> PostgreSQL query count/plan
 -> pool saturation
 -> serialization/payload size

WebSocket failures high
 -> Go CPU/memory
 -> open file/socket limits
 -> per-connection queue behavior
 -> Redis/NATS latency

Import staging slow
 -> parser CPU
 -> validation query count
 -> JDBC batch behavior
 -> PostgreSQL WAL/index pressure
```

## Next measurement targets

After the baseline suite is stable, add representative seeded-volume scenarios for:

- 10k+ notification rows per user to validate keyset pagination;
- large timeline histories;
- attendance write batches for 30/60/200 students;
- score batches up to the API boundary;
- outbox backlog drain rate;
- 1k, then 5k and 10k websocket connections on hardware sized for that experiment.

Those numbers should not be claimed until they have actually been measured.