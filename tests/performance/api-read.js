import http from 'k6/http'
import { check, sleep } from 'k6'
import exec from 'k6/execution'
import { Rate } from 'k6/metrics'

const baseUrl = __ENV.BASE_URL || 'http://localhost:3000'
const vus = Number(__ENV.API_VUS || 20)
const duration = __ENV.API_DURATION || '30s'
const thinkTime = Number(__ENV.API_THINK_SECONDS || 0.25)

const functionalFailures = new Rate('functional_failures')

export const options = {
  vus,
  duration,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    functional_failures: ['rate<0.01'],
    'http_req_duration{endpoint:me}': ['p(95)<500'],
    'http_req_duration{endpoint:dashboard}': ['p(95)<1000'],
    'http_req_duration{endpoint:notifications}': ['p(95)<750'],
    'http_req_duration{endpoint:announcements}': ['p(95)<750'],
  },
}

function jsonHeaders(token, correlationSuffix) {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
    'X-Correlation-Id': `perf-${correlationSuffix}`,
  }
}

function iterationCorrelationSuffix(endpoint) {
  return `${endpoint}-${exec.vu.idInTest}-${exec.scenario.iterationInTest}`
}

export function setup() {
  const login = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({
    email: __ENV.PERF_EMAIL || 'admin@eclassroom.local',
    password: __ENV.PERF_PASSWORD || 'Admin123!',
  }), { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'login-setup' } })

  if (!check(login, { 'setup login succeeds': (r) => r.status === 200 })) {
    throw new Error(`Performance setup login failed with status ${login.status}`)
  }

  const tokens = login.json()
  const me = http.get(`${baseUrl}/api/v1/me`, {
    headers: jsonHeaders(tokens.accessToken, 'setup-me'),
    tags: { endpoint: 'setup-me' },
  })

  if (!check(me, { 'setup me succeeds': (r) => r.status === 200 })) {
    throw new Error(`Performance setup /me failed with status ${me.status}`)
  }

  const membership = me.json('memberships.0')
  if (!membership || !membership.school_id) throw new Error('Bootstrap user has no school membership')

  return { token: tokens.accessToken, schoolId: membership.school_id }
}

function get(url, token, endpoint) {
  const response = http.get(url, {
    headers: jsonHeaders(token, iterationCorrelationSuffix(endpoint)),
    tags: { endpoint },
  })
  const ok = check(response, { [`${endpoint} returns 200`]: (r) => r.status === 200 })
  functionalFailures.add(!ok, { endpoint })
}

export default function (data) {
  get(`${baseUrl}/api/v1/me`, data.token, 'me')
  get(`${baseUrl}/api/v1/schools/${data.schoolId}/reports/dashboard`, data.token, 'dashboard')
  get(`${baseUrl}/api/v1/notifications/page?schoolId=${data.schoolId}&limit=20`, data.token, 'notifications')
  get(`${baseUrl}/api/v1/schools/${data.schoolId}/announcements`, data.token, 'announcements')
  sleep(thinkTime)
}
