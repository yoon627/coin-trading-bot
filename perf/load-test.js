import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
const errorRate = new Rate('errors');
const loginDuration = new Trend('login_duration');

// Test configuration.
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// ONLY=smoke|load 로 시나리오를 고른다(k6 에는 시나리오 선택 플래그가 없다 — grafana/k6#3054).
// load 는 iteration 마다 계정을 등록하므로 운영 도메인에는 ONLY=smoke 로만 돌린다(perf/README.md).
const ONLY = __ENV.ONLY;
// LOCAL_CLIENT_IPS=1: iteration 마다 다른 X-Forwarded-For 를 붙여 한 머신의 VU 들이 rate limit 버킷 하나를 나눠 쓰지 않게 한다.
// 프록시 없는 로컬 서버 전용 — 운영은 Caddy 가 이 헤더를 실제 peer IP 로 덮어쓰므로 아무 효과가 없다(perf/README.md).
const LOCAL_CLIENT_IPS = __ENV.LOCAL_CLIENT_IPS === '1';

function clientIpHeader() {
  if (!LOCAL_CLIENT_IPS) return {};
  return { 'X-Forwarded-For': `10.${__VU % 256}.${Math.floor(__ITER / 256) % 256}.${__ITER % 256}` };
}

const scenarios = {
    // Smoke test: 1 user, quick sanity check
    smoke: {
      executor: 'constant-vus',
      vus: 1,
      duration: '30s',
      tags: { scenario: 'smoke' },
      exec: 'smokeTest',
    },
    // Load test: ramp up to 50 users
    load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 10 },
        { duration: '3m', target: 30 },
        { duration: '1m', target: 50 },
        { duration: '2m', target: 50 },
        { duration: '1m', target: 0 },
      ],
      startTime: '35s',
      tags: { scenario: 'load' },
      exec: 'loadTest',
    },
};

export const options = {
  scenarios: ONLY ? { [ONLY]: scenarios[ONLY] } : scenarios,
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1500'],
    errors: ['rate<0.05'],
    http_req_failed: ['rate<0.05'],
  },
};

// ── Helper ──

function registerAndLogin(id) {
  const username = `k6user_${id}_${Date.now()}`;
  const payload = JSON.stringify({
    username: username,
    password: 'testpass123',
  });
  const headers = Object.assign({ 'Content-Type': 'application/json' }, clientIpHeader());

  // Register
  http.post(`${BASE_URL}/api/auth/register`, payload, { headers });

  // Login
  const loginRes = http.post(`${BASE_URL}/api/auth/login`, payload, { headers });
  loginDuration.add(loginRes.timings.duration);
  check(loginRes, { 'login 200': (r) => r.status === 200 }) || errorRate.add(1);

  if (loginRes.status === 200) {
    try {
      const body = JSON.parse(loginRes.body);
      return body.token;
    } catch (e) {
      return null;
    }
  }
  return null;
}

function authHeaders(token) {
  return {
    headers: Object.assign({
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    }, clientIpHeader()),
  };
}

// ── Smoke Test ──

export function smokeTest() {
  group('Health & Public Endpoints', () => {
    // Health check
    const health = http.get(`${BASE_URL}/actuator/health`);
    check(health, {
      'health status is 200': (r) => r.status === 200,
      'health is UP': (r) => r.json('status') === 'UP',
    }) || errorRate.add(1);

    // Leaderboard (public)
    const leaderboard = http.get(`${BASE_URL}/api/leaderboard`);
    check(leaderboard, {
      'leaderboard is 200': (r) => r.status === 200,
    }) || errorRate.add(1);

    // Price status (public)
    const priceStatus = http.get(`${BASE_URL}/api/prices/status`);
    check(priceStatus, {
      'price status is 200': (r) => r.status === 200,
    }) || errorRate.add(1);
  });

  sleep(1);
}

// ── Load Test ──

export function loadTest() {
  const token = registerAndLogin(__VU);

  group('Public Endpoints', () => {
    const health = http.get(`${BASE_URL}/actuator/health`, { headers: clientIpHeader() });
    check(health, { 'health 200': (r) => r.status === 200 }) || errorRate.add(1);

    const leaderboard = http.get(`${BASE_URL}/api/leaderboard`, { headers: clientIpHeader() });
    check(leaderboard, { 'leaderboard 200': (r) => r.status === 200 }) || errorRate.add(1);

    const latest = http.get(`${BASE_URL}/api/prices/latest`, { headers: clientIpHeader() });
    check(latest, { 'latest prices 200': (r) => r.status === 200 }) || errorRate.add(1);
  });

  if (token) {
    group('Authenticated Endpoints', () => {
      // Get user info
      const me = http.get(`${BASE_URL}/api/user/me`, authHeaders(token));
      check(me, { 'user/me 200': (r) => r.status === 200 }) || errorRate.add(1);

      // Bot status
      const botStatus = http.get(`${BASE_URL}/api/bot/status`, authHeaders(token));
      check(botStatus, {
        'bot status 200': (r) => r.status === 200,
      }) || errorRate.add(1);

      // Strategies list
      const strategies = http.get(`${BASE_URL}/api/strategies`, authHeaders(token));
      check(strategies, {
        'strategies 200': (r) => r.status === 200,
      }) || errorRate.add(1);

      // Trade history
      const trades = http.get(`${BASE_URL}/api/trades`, authHeaders(token));
      check(trades, {
        'trades 200': (r) => r.status === 200,
      }) || errorRate.add(1);

      // Portfolio — k6 유저는 Upbit 키가 없어 400 이 정상 응답이다(PortfolioController). 그 외는 실패로 센다.
      // expectedStatuses 가 없으면 이 400 이 http_req_failed 에 iteration 당 1건씩(=10%) 쌓여 임계를 항상 깬다.
      const portfolio = http.get(
        `${BASE_URL}/api/portfolio`,
        Object.assign(authHeaders(token), { responseCallback: http.expectedStatuses(200, 400) }),
      );
      check(portfolio, {
        'portfolio 200 or 400(no keys)': (r) => r.status === 200 || r.status === 400,
      }) || errorRate.add(1);
    });
  }

  sleep(1 + Math.random() * 2);
}
