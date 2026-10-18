// JWT 는 /api/auth/login 이 httpOnly 쿠키로 내려주므로 Authorization 헤더를 붙이지 않는다.

const TideAPI = {
  async _fetch(path, opts = {}) {
    const res = await fetch(path, {
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) },
      ...opts,
    });
    // /api/auth/* 의 401 은 세션 만료가 아니라 인증 실패라 아래에서 서버 메시지를 그대로 올린다.
    if (res.status === 401 && !path.startsWith('/api/auth/')) {
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

  login: (username, password) => TideAPI._fetch('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  // 서버가 SNAKE_CASE 로 역직렬화하므로 camelCase 로 보내면 키가 조용히 버려진다.
  register: (username, password, upbitAccessKey, upbitSecretKey) =>
    TideAPI._fetch('/api/auth/register', { method: 'POST', body: JSON.stringify({
      username, password,
      upbit_access_key: upbitAccessKey,
      upbit_secret_key: upbitSecretKey,
    }) }),
  logout: () => TideAPI._fetch('/api/auth/logout', { method: 'POST' }),
  me: () => TideAPI._fetch('/api/user/me'),

  botStatus: () => TideAPI._fetch('/api/bot/status'),
  botStart: (req = {}) => TideAPI._fetch('/api/bot/start', { method: 'POST', body: JSON.stringify(req) }),
  botStop: () => TideAPI._fetch('/api/bot/stop', { method: 'POST' }),
  // 화면 버튼 없이 콘솔에서 쓴다. 정지된 봇의 막힌 pending 을 지운다. side 는 'buy' | 'sell'.
  clearPending: (ticker, side) => TideAPI._fetch('/api/bot/pending/clear', { method: 'POST', body: JSON.stringify({ ticker, side }) }),

  portfolio: () => TideAPI._fetch('/api/portfolio'),
  account: () => TideAPI._fetch('/api/account'),
  trades: () => TideAPI._fetch('/api/trades'),
  // 응답은 { total, limit, truncated, round_trips } 이다.
  roundTrips: () => TideAPI._fetch('/api/trades/roundtrips'),

  saveKeys: (accessKey, secretKey) =>
    TideAPI._fetch('/api/user/keys', { method: 'POST', body: JSON.stringify({
      access_key: accessKey,
      secret_key: secretKey,
    }) }),

  // 필드를 빼면 서버가 저장된 웹훅을 유지한다.
  updateSettings: (patch) =>
    TideAPI._fetch('/api/user/settings', { method: 'POST', body: JSON.stringify(patch) }),

};

const fmtKRW = v => '₩' + Math.round(v || 0).toLocaleString();
const fmtPct = v => ((v || 0) >= 0 ? '+' : '') + (v || 0).toFixed(2) + '%';
const fmtNum = (v, d = 4) => (v || 0).toLocaleString('en-US', { maximumFractionDigits: d });

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
