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

// 시세판 SSE. 200 스트림이 끊기면 브라우저가 retry 대로 다시 붙지만, 503·429·502·401 처럼 200 이 아닌 응답 뒤에는
// 스스로 다시 붙지 않아(EventSource 규칙) 1~3초에서 시작해 30초까지 늘려 가며(지터) 손수 다시 연다. 먼저 로그인을 확인한다 — 401 이면 로그인 화면으로 간다.
// 받은 시세를 지금 믿을 수 있는가. delta 는 1초마다 오므로 10초 넘게 아무것도 오지 않았거나 연결이 끊겼으면
// 받아 둔 값은 멈춘 값이다 — 그때는 null 을 돌려 화면이 서버 값(/api/portfolio)을 쓰게 한다.
const STREAM_FRESH_MS = 10000;
function freshRows(status, lastAt, rows, now) {
  return status === 'live' && lastAt && now - lastAt <= STREAM_FRESH_MS ? rows : null;
}

// enabled 가 false 면(업비트 키가 없는 사용자) 연결하지 않는다 — 사용자당 스트림 자리를 쓰지 않게.
function useMarketStream(enabled = true) {
  const [rows, setRows] = React.useState(() => new Map());
  const [status, setStatus] = React.useState('connecting');
  const [lastAt, setLastAt] = React.useState(null);
  // 갱신이 멈춰도 '멈췄다' 를 알아채도록 1초마다 다시 본다.
  const [now, setNow] = React.useState(Date.now());

  React.useEffect(() => {
    if (!enabled) return undefined;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [enabled]);

  React.useEffect(() => {
    if (!enabled) return undefined;
    let es = null, timer = null, wait = 2000, closed = false;
    const apply = (data, replace) => {
      setLastAt(Date.now());
      setRows(prev => {
        const next = replace ? new Map() : new Map(prev);
        data.rows.forEach(r => next.set(r.m, r));
        (data.removed || []).forEach(m => next.delete(m));
        return next;
      });
    };
    const retry = () => {
      if (closed) return;
      setStatus('retrying');
      const delay = Math.min(30000, wait * (0.5 + Math.random()));
      wait = Math.min(wait * 2, 30000);
      timer = setTimeout(() => TideAPI.me().then(open, retry), delay);
    };
    const open = () => {
      if (closed) return;
      es = new EventSource('/api/market/stream');
      es.addEventListener('snapshot', e => { wait = 2000; setStatus('live'); apply(JSON.parse(e.data), true); });
      es.addEventListener('delta', e => apply(JSON.parse(e.data), false));
      es.onerror = () => {
        if (es.readyState !== EventSource.CLOSED) { setStatus('retrying'); return; }
        es.close();
        retry();
      };
    };
    open();
    return () => { closed = true; clearTimeout(timer); if (es) es.close(); };
  }, [enabled]);

  return { rows, status, lastAt, fresh: enabled ? freshRows(status, lastAt, rows, now) : null };
}

// /api/portfolio 의 보유 목록에 시세판 실시간 가격을 덧씌운다. 식은 서버(PortfolioController)와 같다.
// 시세판에 없는 코인(목록 밖·스트림 없음)은 서버가 준 값을 그대로 둔다.
function liveHoldings(portfolio, rows) {
  if (!portfolio) return portfolio;
  let total = portfolio.krw_balance || 0;
  const holdings = (portfolio.holdings || []).map(h => {
    const live = rows && rows.get(h.market);
    if (!live) { total += h.eval_amount || 0; return h; }
    const price = live.p;
    const evalAmount = price * h.balance;
    const investAmount = h.avg_buy_price * h.balance;
    total += evalAmount;
    return { ...h, current_price: price, eval_amount: evalAmount,
      pnl_percent: h.avg_buy_price > 0 ? (price - h.avg_buy_price) / h.avg_buy_price * 100 : 0,
      pnl_amount: evalAmount - investAmount };
  });
  return { ...portfolio, holdings, total_eval: total };
}

window.TideAPI = TideAPI;
window.liveHoldings = liveHoldings;
window.freshRows = freshRows;
window.useMarketStream = useMarketStream;
window.fmtKRW = fmtKRW; window.fmtPct = fmtPct; window.fmtNum = fmtNum;
window.useAPI = useAPI;
