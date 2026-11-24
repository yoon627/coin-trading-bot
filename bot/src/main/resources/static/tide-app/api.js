// JWT 는 /api/auth/login 이 httpOnly 쿠키로 내려주므로 Authorization 헤더를 붙이지 않는다.

// 접근 JWT 가 만료되면(401) 로그인 유지 쿠키(refresh)로 새로 받는다. 이 탭의 동시 요청은 한 번의 갱신을 함께 기다리고,
// 탭끼리는 navigator.locks 로 줄 세운다 — 같은 토큰으로 두 탭이 동시에 바꾸면 서버가 한쪽을 거절한다.
let refreshing = null;
function refreshSession() {
  if (!refreshing) {
    const call = () => fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' }).then(r => r.ok, () => false);
    const run = typeof navigator !== 'undefined' && navigator.locks ? navigator.locks.request('tide-refresh', call) : call();
    // 잠금 요청이 거절돼도(드물다) 갱신 실패로 보고 원래 요청을 한 번 다시 보내는 길로 간다.
    refreshing = Promise.resolve(run).catch(() => false).finally(() => { refreshing = null; });
  }
  return refreshing;
}

const TideAPI = {
  async _fetch(path, opts = {}) {
    const send = () => fetch(path, {
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) },
      ...opts,
    });
    let res = await send();
    // /api/auth/* 의 401 은 세션 만료가 아니라 인증 실패라 아래에서 서버 메시지를 그대로 올린다.
    if (res.status === 401 && !path.startsWith('/api/auth/')) {
      // 갱신이 거절돼도 한 번은 다시 보낸다 — 다른 탭이 방금 바꾼 새 쿠키가 이미 저장소에 있을 수 있다. 요청마다 갱신·재시도는 한 번뿐이다.
      await refreshSession();
      res = await send();
      if (res.status === 401) {
        if (!location.pathname.endsWith('login.html')) {
          location.href = '/login.html';
        }
        throw new Error('Unauthorized');
      }
    }
    if (!res.ok) {
      let msg = `HTTP ${res.status}`;
      let body = null;
      try { body = await res.json(); msg = body.message || body.error || msg; } catch {}
      // 409 같은 응답은 본문에 다음 단계에 쓸 내용이 있다(매매 값 저장의 미리보기).
      throw Object.assign(new Error(msg), { status: res.status, body });
    }
    if (res.status === 204) return null;
    const ct = res.headers.get('content-type') || '';
    return ct.includes('json') ? res.json() : res.text();
  },

  login: (username, password, remember = false) => TideAPI._fetch('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password, remember }) }),
  // 서버가 SNAKE_CASE 로 역직렬화하므로 camelCase 로 보내면 키가 조용히 버려진다.
  register: (username, password, upbitAccessKey, upbitSecretKey, remember = false) =>
    TideAPI._fetch('/api/auth/register', { method: 'POST', body: JSON.stringify({
      username, password, remember,
      upbit_access_key: upbitAccessKey,
      upbit_secret_key: upbitSecretKey,
    }) }),
  logout: () => TideAPI._fetch('/api/auth/logout', { method: 'POST' }),
  me: () => TideAPI._fetch('/api/user/me'),

  botStatus: () => TideAPI._fetch('/api/bot/status'),
  botStart: (req = {}) => TideAPI._fetch('/api/bot/start', { method: 'POST', body: JSON.stringify(req) }),
  botStop: () => TideAPI._fetch('/api/bot/stop', { method: 'POST' }),
  // 감시 목록. 저장하면 도는 봇은 그 목록으로 바뀌고({status: 'tickers_changed'}), 멈춘 봇은 다음 시작이 그 목록으로 뜬다({status: 'saved'}).
  botTickers: () => TideAPI._fetch('/api/bot/tickers'),
  // keepalive 는 탭을 닫을 때 보내는 저장이 페이지와 함께 끊기지 않게 한다.
  // 매매 값. 빠진 항목은 서버 기본값(env)이다. 보유 포지션이 바뀌면 미리본 내용의 confirm_token 이 없으면 409 와
  // { changes, confirm_token } 를 돌려준다 — 확인은 그 토큰과 같은 본문으로 다시 보낸다.
  botParams: () => TideAPI._fetch('/api/bot/params'),
  saveBotParams: (params, confirmToken = null) =>
    TideAPI._fetch('/api/bot/params', { method: 'PUT', body: JSON.stringify({ ...params, confirm_token: confirmToken }) }),
  saveBotTickers: (tickers, keepalive = false) =>
    TideAPI._fetch('/api/bot/tickers', { method: 'PUT', body: JSON.stringify({ tickers }), keepalive }),
  // 응답은 { markets: [{ market: 'KRW-BTC', korean_name, english_name }] } 이다. 목록을 못 받았으면 503.
  markets: () => TideAPI._fetch('/api/market/markets'),

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

// 감시 코인 검색: 코드(BTC·KRW-BTC)·한글·영문 이름에 검색어가 들어간 마켓을 고른 것은 빼고 limit 개까지.
// 코드나 이름이 검색어와 같은 마켓, 검색어로 시작하는 마켓 순으로 앞에 둔다 — Enter 는 첫 줄을 넣는다
// ("이더리움" 은 이더리움네임서비스보다 이더리움이, "BTC" 는 WBTC 보다 비트코인이 먼저).
function searchMarkets(markets, query, selected, limit = 8) {
  const q = (query || '').trim().toUpperCase();
  if (!q) return [];
  const taken = new Set(selected);
  const keys = m => [m.market.replace(/^KRW-/, ''), m.market, (m.korean_name || '').toUpperCase(), (m.english_name || '').toUpperCase()];
  const hits = (markets || []).filter(m => !taken.has(m.market) && keys(m).some(k => k.includes(q)));
  const rank = m => (keys(m).some(k => k === q) ? 0 : keys(m).some(k => k.startsWith(q)) ? 1 : 2);
  return hits.sort((a, b) => rank(a) - rank(b)).slice(0, limit);
}

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
window.searchMarkets = searchMarkets;
window.useMarketStream = useMarketStream;
window.fmtKRW = fmtKRW; window.fmtPct = fmtPct; window.fmtNum = fmtNum;
window.useAPI = useAPI;
