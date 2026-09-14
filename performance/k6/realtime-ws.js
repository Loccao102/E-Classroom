import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:3000';
const wsBaseUrl = __ENV.WS_BASE_URL || 'ws://localhost:8090';
const wsConnectMs = new Trend('ws_connect_ms', true);
const wsFailures = new Rate('ws_failures');

export const options = {
  scenarios: {
    realtime_connections: {
      executor: 'constant-vus',
      vus: Number(__ENV.WS_VUS || 50),
      duration: __ENV.WS_DURATION || '30s',
      gracefulStop: '10s',
    },
  },
  thresholds: {
    ws_connect_ms: ['p(95)<750', 'p(99)<1500'],
    ws_failures: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  const response = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({
    email: __ENV.PERF_EMAIL || 'admin@eclassroom.local',
    password: __ENV.PERF_PASSWORD || 'Admin123!',
  }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login_setup' } });
  check(response, { 'login succeeds': (r) => r.status === 200 });
  if (response.status !== 200) throw new Error(`Login failed: ${response.status}`);
  return { token: response.json().accessToken };
}

export default function (data) {
  const started = Date.now();
  const response = ws.connect(`${wsBaseUrl}/realtime/v1/ws?access_token=${encodeURIComponent(data.token)}`, {}, (socket) => {
    socket.on('open', () => {
      wsConnectMs.add(Date.now() - started);
    });

    socket.on('error', () => {
      wsFailures.add(1);
    });

    socket.setTimeout(() => socket.close(), Number(__ENV.WS_HOLD_MS || 5000));
  });

  const ok = check(response, { 'websocket upgraded': (r) => r && r.status === 101 });
  wsFailures.add(ok ? 0 : 1);
}
