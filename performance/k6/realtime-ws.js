import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:3000';
const wsBaseUrl = __ENV.WS_BASE_URL || 'ws://localhost:8090';

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
    ws_connecting: ['p(95)<750', 'p(99)<1500'],
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
  const response = ws.connect(`${wsBaseUrl}/realtime/v1/ws?access_token=${encodeURIComponent(data.token)}`, {}, (socket) => {
    socket.setTimeout(() => socket.close(), Number(__ENV.WS_HOLD_MS || 5000));
  });

  check(response, { 'websocket upgraded': (r) => r && r.status === 101 });
}
