// Tide — API client + auth helpers
// Talks to the Spring Boot backend at the same origin.
// JWT is delivered as an httpOnly cookie by /api/auth/login,
// so we don't need to attach Authorization headers manually.

const TideAPI = {
  async _fetch(path, opts = {}) {
    const res = await fetch(path, {
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) },
      ...opts,
    });
    if (res.status === 401) {
      // Session expired — bounce to login
      if (!location.pathname.endsWith('login.html')) {
        location.href = '/login.html';
      }
      throw new Error('Unauthorized');
    }
    if (!res.ok) {
      let msg = `HTTP ${res.status}`;
      try { const j = await res.json(); msg = j.message || j.error || msg; } catch {}
      throw new Error(msg);
    }
    if (res.status === 204) return null;
    const ct = res.headers.get('content-type') || '';
    return ct.includes('json') ? res.json() : res.text();
  },

  // Auth
  login: (username, password) => TideAPI._fetch('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  // Backend uses Jackson SNAKE_CASE strategy, so optional API key fields must be
  // sent as snake_case or they're silently dropped from AuthRequest.
  register: (username, password, upbitAccessKey, upbitSecretKey) =>
    TideAPI._fetch('/api/auth/register', { method: 'POST', body: JSON.stringify({
      username, password,
      upbit_access_key: upbitAccessKey,
      upbit_secret_key: upbitSecretKey,
    }) }),
  logout: () => TideAPI._fetch('/api/auth/logout', { method: 'POST' }),
  me: () => TideAPI._fetch('/api/user/me'),

  // Bot
  botStatus: () => TideAPI._fetch('/api/bot/status'),
  botStart: (req = {}) => TideAPI._fetch('/api/bot/start', { method: 'POST', body: JSON.stringify(req) }),
  botStop: () => TideAPI._fetch('/api/bot/stop', { method: 'POST' }),
  botStrategy: (strategy) => TideAPI._fetch('/api/bot/strategy', { method: 'POST', body: JSON.stringify({ strategy }) }),

  // Trading data
  portfolio: () => TideAPI._fetch('/api/portfolio'),
  account: () => TideAPI._fetch('/api/account'),
  trades: () => TideAPI._fetch('/api/trades'),
  pricesLatest: () => TideAPI._fetch('/api/prices/latest'),

  // Strategy + backtest
  strategies: () => TideAPI._fetch('/api/strategies'),
  performance: () => TideAPI._fetch('/api/strategies/performance'),
  backtest: (req) => TideAPI._fetch('/api/strategies/backtest', { method: 'POST', body: JSON.stringify(req) }),

  // Manual trade
  buy: (market, amount) => TideAPI._fetch('/api/trade/buy', { method: 'POST', body: JSON.stringify({ market, amount }) }),
  sell: (market, opts) => TideAPI._fetch('/api/trade/sell', { method: 'POST', body: JSON.stringify({ market, ...opts }) }),

  // Settings
  // Same SNAKE_CASE concern as register — UpbitKeysRequest.accessKey on the
  // wire is access_key.
  saveKeys: (accessKey, secretKey) =>
    TideAPI._fetch('/api/user/keys', { method: 'POST', body: JSON.stringify({
      access_key: accessKey,
      secret_key: secretKey,
    }) }),

  // Profile + notifications. Server preserves any field omitted from the body
  // (snake_case wire format matches Jackson SNAKE_CASE strategy).
  updateSettings: (patch) =>
    TideAPI._fetch('/api/user/settings', { method: 'POST', body: JSON.stringify(patch) }),

  // Leaderboard
  leaderboard: () => TideAPI._fetch('/api/leaderboard'),

  // ML
  mlPredict: (ticker) => TideAPI._fetch(`/api/ml/predict?ticker=${encodeURIComponent(ticker)}`),
  mlStatus: (ticker) => TideAPI._fetch(`/api/ml/status?ticker=${encodeURIComponent(ticker)}`),
};

// Format helpers
const fmtKRW = v => '₩' + Math.round(v || 0).toLocaleString();
const fmtPct = v => ((v || 0) >= 0 ? '+' : '') + (v || 0).toFixed(2) + '%';
const fmtNum = (v, d = 4) => (v || 0).toLocaleString('en-US', { maximumFractionDigits: d });

// Hook to fetch + auto-refresh
function useAPI(fn, deps = [], intervalMs = null) {
  const [data, setData] = React.useState(null);
  const [error, setError] = React.useState(null);
  const [loading, setLoading] = React.useState(true);

  const reload = React.useCallback(async () => {
    try {
      setError(null);
      const d = await fn();
      setData(d);
    } catch (e) { setError(e.message || String(e)); }
    finally { setLoading(false); }
    // eslint-disable-next-line
  }, deps);

  React.useEffect(() => { reload(); }, [reload]);
  React.useEffect(() => {
    if (!intervalMs) return;
    const id = setInterval(reload, intervalMs);
    return () => clearInterval(id);
  }, [reload, intervalMs]);

  return { data, error, loading, reload };
}

window.TideAPI = TideAPI;
window.fmtKRW = fmtKRW; window.fmtPct = fmtPct; window.fmtNum = fmtNum;
window.useAPI = useAPI;
