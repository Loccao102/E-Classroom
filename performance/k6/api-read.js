import http from 'k6/http';
import { check, group, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:3000';
const vus = Number(__ENV.API_VUS || 25);
const duration = __ENV.API_DURATION || '30s';

export const options = {
  scenarios: {
    authenticated_reads: {
      executor: 'constant-vus',
      vus,
      duration,
      gracefulStop: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{scenario:authenticated_reads}': ['p(95)<500', 'p(99)<1000'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  const response = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({
    email: __ENV.PERF_EMAIL || 'admin@eclassroom.local',
    password: __ENV.PERF_PASSWORD || 'Admin123!',
  }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login_setup' } });

  check(response, { 'login succeeds': (r) => r.status === 200 });
  if (response.status !== 200) throw new Error(`Login failed: ${response.status} ${response.body}`);

  const body = response.json();
  const schoolId = body.memberships?.[0]?.schoolId || body.memberships?.[0]?.school_id;
  if (!schoolId) {
    const me = http.get(`${baseUrl}/api/v1/me`, { headers: { Authorization: `Bearer ${body.accessToken}` } });
    const meBody = me.json();
    const resolved = meBody.memberships?.[0]?.schoolId || meBody.memberships?.[0]?.school_id;
    if (!resolved) throw new Error('No school membership available for performance test');
    return { token: body.accessToken, schoolId: resolved };
  }
  return { token: body.accessToken, schoolId };
}

export default function (data) {
  const params = { headers: { Authorization: `Bearer ${data.token}` } };

  group('identity', () => {
    const response = http.get(`${baseUrl}/api/v1/me`, { ...params, tags: { name: 'GET /me' } });
    check(response, { 'me 200': (r) => r.status === 200 });
  });

  group('notifications', () => {
    const response = http.get(`${baseUrl}/api/v1/notifications/unread-count?schoolId=${data.schoolId}`, {
      ...params,
      tags: { name: 'GET /notifications/unread-count' },
    });
    check(response, { 'unread count 200': (r) => r.status === 200 });
  });

  group('reporting', () => {
    const response = http.get(`${baseUrl}/api/v1/schools/${data.schoolId}/reports/dashboard`, {
      ...params,
      tags: { name: 'GET /reports/dashboard' },
    });
    check(response, { 'dashboard 200': (r) => r.status === 200 });
  });

  sleep(Number(__ENV.API_THINK_TIME || 0.2));
}
