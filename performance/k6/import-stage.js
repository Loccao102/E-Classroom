// Isolated performance test for school-admin import staging.
// It never calls /commit, so academic business tables remain unchanged.
// The benchmark still writes transient staging jobs; use disposable databases only.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:3000';
const rows = Number(__ENV.IMPORT_ROWS || 1000);
const polls = Number(__ENV.IMPORT_MAX_POLLS || 90);
const interval = Number(__ENV.IMPORT_POLL_SECONDS || 1);
const iterations = Number(__ENV.IMPORT_ITERATIONS || 1);

const failureRate = new Rate('import_preview_failed');
const processingMs = new Trend('import_preview_duration_ms', true);

export const options = {
  vus: 1,
  iterations,
  thresholds: {
    import_preview_failed: ['rate<0.01'],
    import_preview_duration_ms: ['p(95)<60000'],
  },
};

function checkSafety() {
  if (__ENV.IMPORT_BENCHMARK_ALLOW_STAGING !== '1') {
    throw new Error('Refusing to create staging jobs: set IMPORT_BENCHMARK_ALLOW_STAGING=1 for an isolated disposable environment');
  }
  if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/?(?:\?.*)?$/.test(baseUrl)) {
    throw new Error('Import benchmark may only access the local disposable application stack');
  }
  if (!Number.isInteger(rows) || rows < 1 || rows > 5000) throw new Error('IMPORT_ROWS must be an integer in [1, 5000]');
  if (!Number.isInteger(iterations) || iterations < 1 || iterations > 2) throw new Error('IMPORT_ITERATIONS must be 1 or 2');
  if (!Number.isInteger(polls) || polls < 1 || polls > 180) throw new Error('IMPORT_MAX_POLLS must be in [1, 180]');
  if (!Number.isInteger(interval) || interval < 1 || interval > 10) throw new Error('IMPORT_POLL_SECONDS must be in [1, 10]');
}

function headers(token, tag, extra = {}) {
  return { Authorization: `Bearer ${token}`, 'X-Correlation-Id': `perf-stage-${tag}`, ...extra };
}

export function setup() {
  checkSafety();
  const login = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({
    email: __ENV.PERF_EMAIL || 'admin@eclassroom.local',
    password: __ENV.PERF_PASSWORD || 'Admin123!',
  }), { headers: { 'Content-Type': 'application/json' } });
  if (!check(login, { 'stage benchmark login 200': response => response.status === 200 })) {
    throw new Error(`Cannot log in to disposable E-Classroom test stack: HTTP ${login.status}`);
  }

  const body = login.json();
  let schoolId = body.memberships?.[0]?.schoolId || body.memberships?.[0]?.school_id;
  if (!schoolId) {
    const me = http.get(`${baseUrl}/api/v1/me`, { headers: headers(body.accessToken, 'me') });
    if (me.status !== 200) throw new Error(`Profile lookup returned HTTP ${me.status}`);
    const member = me.json('memberships.0');
    schoolId = member?.schoolId || member?.school_id;
  }
  if (!schoolId || !body.accessToken) throw new Error('Missing admin token or school ID');
  return { schoolId, token: body.accessToken };
}

function fixture(prefix) {
  const lines = ['student_code,full_name,date_of_birth,gender,email'];
  for (let i = 0; i < rows; i++) {
    lines.push(`${prefix}${String(i).padStart(5, '0')},Performance Student ${i},2010-01-01,,`);
  }
  return lines.join('\n');
}

export default function run(data) {
  const unique = `${Date.now()}-${exec.vu.idInTest}-${exec.scenario.iterationInTest}`;
  const prefix = `P${unique.replace(/\D/g, '').slice(-10)}`;
  const csv = fixture(prefix);
  const started = Date.now();
  const staged = http.post(
    `${baseUrl}/api/v1/schools/${data.schoolId}/imports?type=STUDENT`,
    { file: http.file(csv, `students-${unique}.csv`, 'text/csv') },
    { headers: headers(data.token, unique, { 'Idempotency-Key': `perf-stage-${unique}` }) }
  );
  if (!check(staged, { 'stage created': r => r.status === 200 })) {
    failureRate.add(true);
    return;
  }
  const jobId = staged.json('jobId');
  if (!jobId) {
    failureRate.add(true);
    return;
  }

  let status = '';
  for (let i = 0; i < polls; i++) {
    sleep(interval);
    const response = http.get(
      `${baseUrl}/api/v1/schools/${data.schoolId}/imports/${jobId}`,
      { headers: headers(data.token, `${unique}-poll`) }
    );
    if (response.status !== 200) {
      failureRate.add(true);
      return;
    }
    status = response.json('status');
    if (['PREVIEW_READY', 'FAILED', 'VALIDATION_FAILED', 'COMMITTED'].includes(status)) break;
  }
  // Never call the commit API. Preview must not modify academic tables.
  const ok = status === 'PREVIEW_READY';
  failureRate.add(!ok);
  check({ status }, { 'import reaches preview without committing': v => v.status === 'PREVIEW_READY' });
  processingMs.add(Date.now() - started, { rows: String(rows) });
}
