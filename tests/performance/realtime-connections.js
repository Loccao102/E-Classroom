import http from 'k6/http'
import ws from 'k6/ws'
import { check } from 'k6'
import { Rate, Trend } from 'k6/metrics'

const baseUrl = __ENV.BASE_URL || 'http://localhost:3000'
const wsBaseUrl = (__ENV.WS_BASE_URL || baseUrl).replace(/^http/, 'ws')
const vus = Number(__ENV.WS_VUS || 50)
const duration = __ENV.WS_DURATION || '30s'
const holdMs = Number(__ENV.WS_HOLD_MS || 10000)

const websocketFailures = new Rate('websocket_failures')
const websocketSessionMs = new Trend('websocket_session_ms', true)

export const options = {
  vus,
  duration,
  thresholds: {
    websocket_failures: ['rate<0.01'],
    websocket_session_ms: ['p(95)<15000'],
  },
}

export function setup() {
  const login = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({
    email: __ENV.PERF_EMAIL || 'admin@eclassroom.local',
    password: __ENV.PERF_PASSWORD || 'Admin123!',
  }), { headers: { 'Content-Type': 'application/json' } })

  if (!check(login, { 'setup login succeeds': (r) => r.status === 200 })) {
    throw new Error(`Realtime setup login failed with status ${login.status}`)
  }

  return { token: login.json('accessToken') }
}

export default function (data) {
  const startedAt = Date.now()
  let opened = false
  const response = ws.connect(`${wsBaseUrl}/realtime/v1/ws?access_token=${encodeURIComponent(data.token)}`, {}, (socket) => {
    socket.on('open', () => {
      opened = true
      socket.setTimeout(() => socket.close(), holdMs)
    })
    socket.on('error', () => websocketFailures.add(1))
  })

  const accepted = check(response, { 'websocket upgrade succeeds': (r) => r && r.status === 101 })
  websocketFailures.add(!accepted || !opened)
  websocketSessionMs.add(Date.now() - startedAt)
}
