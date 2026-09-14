import http from 'k6/http'
import { check, sleep } from 'k6'
import exec from 'k6/execution'
import { Rate, Trend } from 'k6/metrics'

const baseUrl = __ENV.BASE_URL || 'http://localhost:3000'
const rows = Math.max(1, Math.min(Number(__ENV.IMPORT_ROWS || 1000), 5000))
const iterations = Math.max(1, Number(__ENV.IMPORT_ITERATIONS || 1))
const pollSeconds = Math.max(1, Number(__ENV.IMPORT_POLL_SECONDS || 1))
const maxPolls = Math.max(5, Number(__ENV.IMPORT_MAX_POLLS || 120))

const importFailures = new Rate('import_failures')
const importProcessingMs = new Trend('import_processing_ms', true)

export const options = {
  vus: 1,
  iterations,
  thresholds: {
    import_failures: ['rate<0.01'],
    import_processing_ms: ['p(95)<60000'],
  },
}

function authHeaders(token, correlationSuffix, extra = {}) {
  return {
    Authorization: `Bearer ${token}`,
    'X-Correlation-Id': `perf-import-${correlationSuffix}`,
    ...extra,
  }
}

function iterationId() {
  return `${exec.vu.idInTest}-${exec.scenario.iterationInTest}`
}

export function setup() {
  const login = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({
    email: __ENV.PERF_EMAIL || 'admin@eclassroom.local',
    password: __ENV.PERF_PASSWORD || 'Admin123!',
  }), { headers: { 'Content-Type': 'application/json' } })

  if (!check(login, { 'setup login succeeds': (r) => r.status === 200 })) {
    throw new Error(`Import setup login failed with status ${login.status}`)
  }

  const token = login.json('accessToken')
  const me = http.get(`${baseUrl}/api/v1/me`, { headers: authHeaders(token, 'setup-me') })
  if (!check(me, { 'setup me succeeds': (r) => r.status === 200 })) {
    throw new Error(`Import setup /me failed with status ${me.status}`)
  }

  const schoolId = me.json('memberships.0.school_id')
  if (!schoolId) throw new Error('Bootstrap user has no school membership')
  return { token, schoolId }
}

function csvFixture(prefix) {
  const lines = ['student_code,full_name,date_of_birth,gender,email']
  for (let i = 0; i < rows; i += 1) {
    const code = `${prefix}${String(i).padStart(5, '0')}`
    lines.push(`${code},Performance Student ${i},2010-01-01,,`)
  }
  return lines.join('\n')
}

export default function (data) {
  const iteration = iterationId()
  const unique = `${Date.now()}-${iteration}`
  const csv = csvFixture(`P${unique.replace(/\D/g, '').slice(-10)}`)
  const idempotencyKey = `perf-${unique}`
  const startedAt = Date.now()

  const stage = http.post(
    `${baseUrl}/api/v1/schools/${data.schoolId}/imports?type=STUDENT`,
    { file: http.file(csv, `students-${unique}.csv`, 'text/csv') },
    { headers: authHeaders(data.token, `${iteration}-stage`, { 'Idempotency-Key': idempotencyKey }), tags: { endpoint: 'import-stage' } },
  )

  const staged = check(stage, { 'import stage accepted': (r) => r.status === 200 })
  if (!staged) {
    importFailures.add(1)
    return
  }

  const jobId = stage.json('jobId')
  let terminalStatus = ''
  for (let i = 0; i < maxPolls; i += 1) {
    sleep(pollSeconds)
    const job = http.get(`${baseUrl}/api/v1/schools/${data.schoolId}/imports/${jobId}`, {
      headers: authHeaders(data.token, `${iteration}-poll-${i}`),
      tags: { endpoint: 'import-poll' },
    })
    if (!check(job, { 'import poll succeeds': (r) => r.status === 200 })) {
      importFailures.add(1)
      return
    }
    terminalStatus = job.json('status')
    if (['PREVIEW_READY', 'VALIDATION_FAILED', 'FAILED', 'COMMITTED'].includes(terminalStatus)) break
  }

  const ok = terminalStatus === 'PREVIEW_READY'
  importFailures.add(!ok)
  check({ terminalStatus }, { 'import reaches PREVIEW_READY': (v) => v.terminalStatus === 'PREVIEW_READY' })
  importProcessingMs.add(Date.now() - startedAt, { rows: String(rows) })
}
