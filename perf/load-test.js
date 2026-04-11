import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
const errorRate = new Rate('errors');
const loginDuration = new Trend('login_duration');

// Test configuration
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
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
  },
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
  const headers = { 'Content-Type': 'application/json' };

  // Register
  http.post(`${BASE_URL}/api/auth/register`, payload, { headers });

  // Login
  const loginRes = http.post(`${BASE_URL}/api/auth/login`, payload, { headers });
  loginDuration.add(loginRes.timings.duration);

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
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
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

    // Prometheus metrics
    const metrics = http.get(`${BASE_URL}/actuator/prometheus`);
    check(metrics, {
      'prometheus metrics is 200': (r) => r.status === 200,
    }) || errorRate.add(1);
  });

  sleep(1);
}

// ── Load Test ──

export function loadTest() {
  const token = registerAndLogin(__VU);

  group('Public Endpoints', () => {
    const health = http.get(`${BASE_URL}/actuator/health`);
    check(health, { 'health 200': (r) => r.status === 200 }) || errorRate.add(1);

    const leaderboard = http.get(`${BASE_URL}/api/leaderboard`);
    check(leaderboard, { 'leaderboard 200': (r) => r.status === 200 }) || errorRate.add(1);

    const latest = http.get(`${BASE_URL}/api/prices/latest`);
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
        'trades 200': (r) => r.status === 200 || r.status === 204,
      }) || errorRate.add(1);

      // Portfolio
      const portfolio = http.get(`${BASE_URL}/api/portfolio`, authHeaders(token));
      check(portfolio, {
        'portfolio accessible': (r) => r.status < 500,
      }) || errorRate.add(1);

      // ML status
      const mlStatus = http.get(`${BASE_URL}/api/ml/status`, authHeaders(token));
      check(mlStatus, {
        'ml status accessible': (r) => r.status < 500,
      }) || errorRate.add(1);
    });
  }

  sleep(1 + Math.random() * 2);
}
