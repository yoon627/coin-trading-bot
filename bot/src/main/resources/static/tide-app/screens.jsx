function Shell({ active, setActive, user, onLogout, title, subtitle, actions, children }) {
  return (
    <div style={{ display: 'flex', minHeight: '100vh', background: 'var(--bg-soft)' }}>
      <Sidebar active={active} onSelect={setActive} user={user} onLogout={onLogout}/>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <TopBar title={title} subtitle={subtitle} actions={actions}/>
        <div className="tide-scroll" style={{ flex: 1, padding: 28 }}>{children}</div>
      </div>
    </div>
  );
}

function ApiKeyWarning({ go }) {
  return (
    <Card padding={28} style={{ background: 'var(--warn-soft)', borderColor: '#FFD89A' }}>
      <div style={{ display: 'flex', gap: 16 }}>
        <div style={{ fontSize: 24 }}>⚠</div>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 15, fontWeight: 700, color: '#A35E00' }}>Upbit API 키가 등록되지 않았습니다</div>
          <div style={{ fontSize: 13, color: '#A35E00', marginTop: 6 }}>설정에서 Upbit Access/Secret Key를 입력하면 포트폴리오와 봇이 활성화됩니다.</div>
          <Button size="sm" style={{ marginTop: 14 }} onClick={go}>설정으로 이동</Button>
        </div>
      </div>
    </Card>
  );
}

function Dashboard({ user, setActive }) {
  const hasKeys = user?.has_upbit_keys;
  // 체결·봇 상태는 신호를 받는 즉시 다시 읽는다. 신호가 붙어 있으면 polling 은 놓친 것만 메우면 되므로 늘린다.
  const eventsLive = useUserEventsLive(!!hasKeys);
  const portfolio = useAPI(() => TideAPI.portfolio(), [], eventsLive ? 30000 : 10000);
  const status = useAPI(() => TideAPI.botStatus().catch(() => null), [], eventsLive ? 15000 : 5000);
  // 잔고는 체결로만 바뀐다 — 상태 신호(고점 저장 등)마다 업비트 계정을 묻지 않는다.
  useUserEventReload(!!hasKeys, ['trade'], portfolio.reload);
  useUserEventReload(!!hasKeys, ['trade', 'state'], status.reload);
  const market = useMarketStream(!!hasKeys);
  // 수량·평단은 체결 신호를 받으면 곧바로 서버에서(신호가 없으면 10초마다), 가격은 시세판 스트림에서 1초마다(멈췄으면 서버 값).
  const p = liveHoldings(portfolio.data, market.fresh);

  return (
    <Shell active="dashboard" setActive={setActive} user={user} onLogout={() => TideAPI.logout().then(() => location.href = '/login.html')}
           title="대시보드" subtitle={new Date().toLocaleString('ko-KR')}
           actions={<>{hasKeys && <LiveBadge live={!!market.fresh}/>}<Button size="sm" icon="refresh" variant="outline" onClick={() => { portfolio.reload(); status.reload(); }}>새로고침</Button></>}>
      {!hasKeys && <div style={{ marginBottom: 16 }}><ApiKeyWarning go={() => setActive('settings')}/></div>}

      <div style={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr 1fr 1fr', gap: 16, marginBottom: 16 }}>
        <Card padding={24} style={{ background: 'var(--ink-900)', color: '#fff', border: 'none' }}>
          <div style={{ fontSize: 12, color: 'rgba(255,255,255,0.6)', fontWeight: 500 }}>총 평가 자산</div>
          <div className="num" style={{ fontSize: 34, fontWeight: 700, marginTop: 6, letterSpacing: '-0.03em' }}>
            {portfolio.loading ? <span className="tide-spinner"/> : fmtKRW(p?.total_eval)}
          </div>
          <div style={{ fontSize: 13, color: 'rgba(255,255,255,0.7)', marginTop: 8 }}>
            KRW 잔고 <span className="num" style={{ fontWeight: 600 }}>{fmtKRW(p?.krw_balance)}</span>
          </div>
        </Card>

        <Card padding={20}>
          <div style={{ fontSize: 12, color: 'var(--ink-500)' }}>봇 상태</div>
          <div style={{ marginTop: 10, display: 'flex', alignItems: 'center', gap: 8 }}>
            <Badge tone={status.data?.running ? 'live' : 'neutral'} dot>
              {status.data?.running ? '실행 중' : '정지'}
            </Badge>
          </div>
          <div style={{ fontSize: 12, color: 'var(--ink-500)', marginTop: 10 }}>
            전략: <span style={{ fontWeight: 600, color: 'var(--ink-900)' }}>{status.data?.strategy || '—'}</span>
          </div>
        </Card>

        <Card padding={20}>
          <div style={{ fontSize: 12, color: 'var(--ink-500)' }}>보유 코인</div>
          <div className="num" style={{ fontSize: 22, fontWeight: 700, marginTop: 12 }}>{p?.holdings?.length || 0}</div>
          <div style={{ fontSize: 11, color: 'var(--ink-500)', marginTop: 4 }}>종목</div>
        </Card>

        <Card padding={20}>
          <div style={{ fontSize: 12, color: 'var(--ink-500)' }}>총 손익</div>
          {(() => {
            const totalPnl = (p?.holdings || []).reduce((s, h) => s + (h.pnl_amount || 0), 0);
            return (
              <div className="num" style={{ fontSize: 22, fontWeight: 700, marginTop: 12, color: totalPnl >= 0 ? 'var(--up)' : 'var(--down)' }}>
                {totalPnl >= 0 ? '+' : ''}{fmtKRW(totalPnl)}
              </div>
            );
          })()}
        </Card>
      </div>

      <Card padding={0}>
        <div style={{ padding: '18px 24px 12px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ fontSize: 14, fontWeight: 600 }}>보유 포지션</div>
          <span style={{ fontSize: 12, color: 'var(--tide-primary)', fontWeight: 600, cursor: 'pointer' }} onClick={() => setActive('wallet')}>모두 보기 →</span>
        </div>
        {portfolio.loading ? (
          <div style={{ padding: 40, textAlign: 'center' }}><span className="tide-spinner"/></div>
        ) : !p?.holdings?.length ? (
          <Empty icon="wallet" title="보유 포지션 없음" message="첫 매매로 포지션을 만들어보세요"/>
        ) : (
          <div style={{ padding: '0 8px 8px' }}>
            {p.holdings.map(h => (
              <div key={h.currency} style={{ display: 'grid', gridTemplateColumns: '36px 1.4fr 1fr 1fr 110px', alignItems: 'center', gap: 12, padding: '12px 16px', borderRadius: 10 }}>
                <div style={{ width: 32, height: 32, borderRadius: 999, background: 'var(--ink-100)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 11, fontWeight: 700 }}>{h.currency}</div>
                <div>
                  <div style={{ fontSize: 13.5, fontWeight: 600 }}>{h.market}</div>
                  <div className="num" style={{ fontSize: 11.5, color: 'var(--ink-500)', marginTop: 2 }}>{fmtNum(h.balance, 6)} {h.currency}</div>
                </div>
                <div className="num" style={{ fontSize: 12, color: 'var(--ink-500)', textAlign: 'right' }}>
                  평단 {fmtKRW(h.avg_buy_price)}<br/>
                  현재 {fmtKRW(h.current_price)}
                </div>
                <div className="num" style={{ fontSize: 13, fontWeight: 600, textAlign: 'right' }}>{fmtKRW(h.eval_amount)}</div>
                <div style={{ textAlign: 'right' }}>
                  <Badge tone={h.pnl_percent >= 0 ? 'up' : 'down'}>{fmtPct(h.pnl_percent)}</Badge>
                  <div className="num" style={{ fontSize: 11, color: h.pnl_amount >= 0 ? 'var(--up)' : 'var(--down)', marginTop: 4 }}>
                    {h.pnl_amount >= 0 ? '+' : ''}{fmtKRW(h.pnl_amount)}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {portfolio.error && <div style={{ marginTop: 12, padding: 12, background: 'var(--up-soft)', color: 'var(--up)', borderRadius: 8, fontSize: 12 }}>{portfolio.error}</div>}
    </Shell>
  );
}

const MAX_TICKERS = 20;

// 감시 코인을 이름·코드로 검색해 태그로 고른다. 목록이 아직 없거나 못 받으면 KRW-코드를 그대로 입력해 Enter 로 넣는다(서버가 본다).
function TickerPicker({ value, onChange }) {
  const [markets, setMarkets] = React.useState(null);
  const [failed, setFailed] = React.useState(false);
  const [query, setQuery] = React.useState('');
  const [cursor, setCursor] = React.useState(0);
  const [open, setOpen] = React.useState(false);
  React.useEffect(() => {
    TideAPI.markets().then(r => setMarkets(r?.markets || []), () => setFailed(true));
  }, []);
  const names = React.useMemo(() => new Map((markets || []).map(m => [m.market, m.korean_name])), [markets]);
  const hits = open ? searchMarkets(markets, query, value) : [];
  const full = value.length >= MAX_TICKERS;
  const add = (code) => {
    if (!code || full || value.includes(code)) return;
    onChange([...value, code]);
    setQuery(''); setCursor(0);
  };
  const remove = (code) => onChange(value.filter(c => c !== code));
  const onKeyDown = (e) => {
    // 한글 조합 중의 Enter 는 조합을 끝내는 키다 — 여기서 넣으면 조합이 끝난 뒤의 Enter 로 한 번 더 들어간다.
    if (e.nativeEvent.isComposing || e.keyCode === 229) return;
    if (e.key === 'Escape') { setOpen(false); return; }
    if (e.key === 'ArrowDown') { e.preventDefault(); setCursor(c => Math.min(c + 1, Math.max(hits.length - 1, 0))); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setCursor(c => Math.max(c - 1, 0)); }
    else if (e.key === 'Enter') {
      e.preventDefault();
      if (hits[cursor]) add(hits[cursor].market);
      else if ((failed || markets === null) && /^KRW-[A-Z0-9]+$/.test(query.trim().toUpperCase())) add(query.trim().toUpperCase());
    }
  };
  const label = { fontSize: 12, fontWeight: 600, color: 'var(--ink-700)', marginBottom: 8 };
  return (
    <div>
      <div style={label}>감시 중인 코인 ({value.length}/{MAX_TICKERS})</div>
      <div data-testid="ticker-tags" style={{ display: 'flex', flexWrap: 'wrap', gap: 6, minHeight: 44, padding: 10, borderRadius: 10,
                                              background: 'var(--ink-50)', border: '1px dashed var(--ink-200)', marginBottom: 16 }}>
        {value.length === 0 && <span style={{ fontSize: 12, color: 'var(--ink-500)', alignSelf: 'center' }}>아직 없습니다 — 아래에서 검색해 추가하세요.</span>}
        {value.map(code => (
          <span key={code} data-ticker={code} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '6px 10px', borderRadius: 8,
                                    background: '#fff', border: '1px solid var(--tide-primary)', color: 'var(--tide-primary)', fontSize: 12, fontWeight: 700 }}>
            {names.get(code) ? `${names.get(code)} ` : ''}{code.replace(/^KRW-/, '')}
            <button type="button" aria-label={`${code} 빼기`} onClick={() => remove(code)}
                    style={{ border: 'none', background: 'none', color: 'inherit', cursor: 'pointer', padding: 0, fontSize: 14, lineHeight: 1 }}>×</button>
          </span>
        ))}
      </div>
      <div style={label}>코인 추가</div>
      <div style={{ position: 'relative' }}>
      <input className="tide-input" value={query} disabled={full} onKeyDown={onKeyDown}
             onChange={e => { setQuery(e.target.value); setCursor(0); setOpen(true); }}
             onFocus={() => setOpen(true)} onBlur={() => setOpen(false)}
             placeholder={full ? `최대 ${MAX_TICKERS}개까지 감시합니다` : failed ? 'KRW-BTC 처럼 입력하고 Enter' : '코인 이름·코드 검색 (예: 비트코인, BTC)'}/>
      {hits.length > 0 && (
        <div role="listbox" style={{ position: 'absolute', top: '100%', left: 0, right: 0, marginTop: 4, zIndex: 10, background: '#fff',
                                     border: '1px solid var(--ink-200)', borderRadius: 10, boxShadow: '0 8px 24px rgba(0,0,0,.08)', overflow: 'hidden' }}>
          {hits.map((m, i) => (
            <div key={m.market} role="option" aria-selected={i === cursor} onMouseDown={e => { e.preventDefault(); add(m.market); }}
                 onMouseEnter={() => setCursor(i)}
                 style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 14px', cursor: 'pointer', fontSize: 13,
                          background: i === cursor ? 'var(--ink-50)' : '#fff' }}>
              <span style={{ fontWeight: 700 }}>{m.korean_name || m.market}</span>
              <span style={{ color: 'var(--ink-500)' }}>{m.market.replace(/^KRW-/, '')} · {m.english_name}</span>
            </div>
          ))}
        </div>
      )}
      </div>
      {failed && <div style={{ fontSize: 11, color: 'var(--ink-500)', marginTop: 6 }}>코인 목록을 불러오지 못했습니다 — 코드를 직접 입력하세요.</div>}
    </div>
  );
}

// 화면에서 바꿀 수 있는 매매 값. pct 는 % 단위, 나머지는 그대로다.
const PARAM_FIELDS = [
  { key: 'take_profit_pct', label: '익절', unit: '%', step: 0.1 },
  { key: 'max_loss_pct', label: '손절', unit: '%', step: 0.1 },
  { key: 'trailing_stop_pct', label: '추적 손절', unit: '%', step: 0.1 },
  { key: 'trailing_arm_pct', label: '추적 시작(수익)', unit: '%', step: 0.1 },
  { key: 'max_hold_days', label: '최대 보유', unit: '거래일', step: 1 },
  { key: 'invest_ratio', label: '1회 매수 비율', unit: '(0~1)', step: 0.01 },
  { key: 'max_invest_amount', label: '1회 매수 상한', unit: '원', step: 1000 },
  { key: 'k_value', label: '돌파 계수 k', unit: '(0~2)', step: 0.05 },
];
const TRIGGER_LABEL = { take_profit: '익절', stop_loss: '손절', trailing_stop: '추적 손절', max_hold_days: '최대 보유' };

// 매매 값: 비운 칸은 서버 기본값을 쓴다. 저장하면 도는 봇과 보유 포지션의 청산 값에 바로 적용된다 — 보유 포지션이 바뀌면 서버가
// 바뀌는 내용(곧바로 팔릴 포지션 포함)을 보여 주고 확인을 받은 뒤에만 저장한다.
function TradingParamsCard({ setToast, onSaved }) {
  const [data, setData] = React.useState(null);
  const [form, setForm] = React.useState({});
  const [failed, setFailed] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [pending, setPending] = React.useState(null);
  const load = () => TideAPI.botParams().then(d => {
    setData(d); setFailed(false);
    setForm(Object.fromEntries(PARAM_FIELDS.map(f => [f.key, d.params?.[f.key] ?? ''])));
  }, () => setFailed(true));
  React.useEffect(() => { load(); }, []);

  // 빈 칸은 null(서버 기본값). 숫자가 아니거나 무한대면 보내지 않는다 — JSON 에서 null 이 되어 조용히 기본값으로 돌아간다.
  const body = () => {
    const out = {};
    for (const f of PARAM_FIELDS) {
      if (form[f.key] === '') { out[f.key] = null; continue; }
      const n = Number(form[f.key]);
      if (!Number.isFinite(n)) throw new Error(`${f.label} 값이 숫자가 아닙니다`);
      out[f.key] = n;
    }
    return out;
  };
  const done = (res) => {
    setPending(null);
    const warnings = res?.warnings || [];
    setToast(warnings.length
      ? { msg: `저장했습니다 — 확인할 점: ${warnings.join(' / ')}`, tone: 'warn' }
      : { msg: res?.status === 'unchanged' ? '바뀐 값이 없습니다' : '매매 값을 저장했습니다', tone: 'up' });
    load(); onSaved?.();
  };
  // 확인은 미리본 본문과 그 토큰을 그대로 보낸다. 그사이 서버 쪽 내용이 달라졌으면 다시 409 로 새 미리보기가 온다.
  const save = async (sent, token) => {
    setBusy(true);
    try { done(await TideAPI.saveBotParams(sent, token)); }
    catch (e) {
      if (e.status === 409 && e.body?.changes) setPending({ ...e.body, sent });
      else setToast({ msg: e.message, tone: 'down' });
    } finally { setBusy(false); }
  };
  const submit = () => {
    let sent;
    try { sent = body(); } catch (e) { setToast({ msg: e.message, tone: 'down' }); return; }
    save(sent, null);
  };

  if (failed) return (
    <Card padding={24}>
      <div style={{ fontSize: 12, color: 'var(--warn)' }}>매매 값을 불러오지 못했습니다.{' '}
        <button type="button" onClick={load} style={{ border: 'none', background: 'none', color: 'var(--tide-primary)', fontWeight: 700, cursor: 'pointer', padding: 0 }}>다시 불러오기</button>
      </div>
    </Card>
  );
  if (!data) return <Card padding={24}><span className="tide-spinner"/></Card>;
  return (
    <Card padding={24}>
      <div style={{ fontSize: 16, fontWeight: 700 }}>매매 설정</div>
      <div style={{ fontSize: 12, color: 'var(--ink-500)', margin: '4px 0 16px' }}>
        비운 칸은 서버 기본값을 씁니다. 저장하면 도는 봇과 <b>보유 중인 포지션의 청산 기준에도 바로 적용</b>됩니다.
      </div>
      <div data-testid="params-form" style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
        {PARAM_FIELDS.map(f => (
          <label key={f.key} style={{ fontSize: 12, fontWeight: 600, color: 'var(--ink-700)' }}>
            {f.label} <span style={{ color: 'var(--ink-500)', fontWeight: 400 }}>{f.unit}</span>
            <input className="tide-input" type="number" step={f.step} name={f.key} value={form[f.key]} disabled={!!pending}
                   placeholder={`기본 ${data.defaults[f.key]}`}
                   onChange={e => setForm({ ...form, [f.key]: e.target.value })} style={{ marginTop: 6 }}/>
          </label>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <Button onClick={submit} disabled={busy || !!pending}>저장</Button>
        <Button variant="ghost" disabled={busy || !!pending} onClick={() => setForm(Object.fromEntries(PARAM_FIELDS.map(f => [f.key, ''])))}>모두 기본값으로</Button>
      </div>
      {pending && (
        <div data-testid="params-confirm" style={{ marginTop: 16, padding: 16, borderRadius: 10, border: '1px solid var(--warn)', background: 'var(--warn-soft)' }}>
          <div style={{ fontSize: 13, fontWeight: 700, marginBottom: 8 }}>보유 포지션 {pending.changes.length}개의 청산 기준이 바뀝니다</div>
          {pending.changes.map(c => (
            <div key={c.ticker} style={{ fontSize: 12, padding: '6px 0', borderTop: '1px solid rgba(0,0,0,.06)' }}>
              <b>{c.ticker}</b>{' '}
              {c.pnl_pct == null ? '(현재가 모름)' : `현재 수익률 ${c.pnl_pct.toFixed(2)}%`}{' — '}
              {['take_profit_pct', 'max_loss_pct', 'trailing_stop_pct', 'trailing_arm_pct', 'max_hold_days']
                .filter(k => c.before[k] !== c.after[k]).map(k => `${PARAM_FIELDS.find(f => f.key === k).label} ${c.before[k]}→${c.after[k]}`).join(', ')}
              {c.triggers.length > 0 && (
                <span data-testid="sell-now" style={{ marginLeft: 6, color: 'var(--up)', fontWeight: 700 }}>
                  저장하면 바로 매도({c.triggers.map(t => TRIGGER_LABEL[t] || t).join(', ')})
                </span>
              )}
            </div>
          ))}
          {(pending.warnings || []).length > 0 && (
            <div style={{ fontSize: 12, marginTop: 8, color: 'var(--ink-700)' }}>확인할 점: {pending.warnings.join(' / ')}</div>
          )}
          <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
            <Button variant="danger" onClick={() => save(pending.sent, pending.confirm_token)} disabled={busy}>
              {pending.changes.some(c => c.triggers.length) ? '팔려도 저장' : '이대로 저장'}
            </Button>
            <Button variant="ghost" onClick={() => setPending(null)} disabled={busy}>취소</Button>
          </div>
        </div>
      )}
    </Card>
  );
}

function BotPage({ user, setActive }) {
  const eventsLive = useUserEventsLive(!!user?.has_upbit_keys);
  const status = useAPI(() => TideAPI.botStatus().catch(() => null), [], eventsLive ? 15000 : 3000);
  useUserEventReload(!!user?.has_upbit_keys, ['trade', 'state'], status.reload);
  // 감시 목록은 서버에 저장된 것을 보여 주고, 고치면 잠시 뒤 저장한다(연달아 고치면 마지막 것만). 도는 봇은 저장할 때 그 목록으로
  // 바뀌고(엔진을 다시 띄우는 몇 초는 매매하지 않는다), 멈춘 봇은 다음 시작이 그 목록으로 뜬다.
  const [tickers, setTickers] = React.useState(null);
  const [loadFailed, setLoadFailed] = React.useState(false);
  const [saveState, setSaveState] = React.useState('idle');
  const [busy, setBusy] = React.useState(false);
  const [toast, setToast] = React.useState(null);
  const running = !!status.data?.running;
  const timer = React.useRef(null);
  const latest = React.useRef(null);
  const saving = React.useRef(Promise.resolve());

  // 처음 불러오기에 실패하면 고칠 수 없게 둔다 — 빈 목록에서 고치면 저장된 목록 전체를 덮는다.
  const loadTickers = () => TideAPI.botTickers().then(
    r => { setLoadFailed(false); setTickers(r.tickers || []); },
    () => setLoadFailed(true));

  const save = (next) => {
    saving.current = saving.current.then(async () => {
      setSaveState('saving');
      try {
        const res = await TideAPI.saveBotTickers(next);
        setSaveState('saved');
        if (res?.status === 'tickers_changed') setToast({ msg: '감시 목록을 바꿨습니다 — 빠진 코인의 보유분은 청산될 때까지 관리합니다', tone: 'up' });
        status.reload();
      } catch (e) {
        // 실패하면 서버의 목록(되돌린 목록)을 다시 보여 준다. 그사이 고친 것이 저장을 기다리면 그 저장이 덮어쓴다.
        setSaveState('error');
        setToast({ msg: e.message, tone: 'down' });
        if (timer.current === null) loadTickers();
      }
    });
    return saving.current;
  };
  const flush = () => {
    if (timer.current === null) return saving.current;
    clearTimeout(timer.current);
    timer.current = null;
    return save(latest.current);
  };
  // 저장을 기다리는 동안 화면을 떠나거나 탭을 닫아도 고친 목록을 보낸다.
  React.useEffect(() => {
    loadTickers();
    const leave = () => {
      if (timer.current === null) return;
      clearTimeout(timer.current);
      timer.current = null;
      TideAPI.saveBotTickers(latest.current, true).catch(() => {});
    };
    window.addEventListener('pagehide', leave);
    return () => { window.removeEventListener('pagehide', leave); leave(); };
  }, []);

  const changeTickers = (next) => {
    setTickers(next);
    clearTimeout(timer.current);
    timer.current = null;
    if (next.length === 0) { setSaveState('empty'); return; }
    latest.current = next;
    setSaveState('pending');
    timer.current = setTimeout(() => { timer.current = null; save(next); }, 1500);
  };

  // 시작은 저장된 목록으로 뜬다 — 저장을 기다리는 변경이 있으면 먼저 저장한다.
  const start = async () => {
    setBusy(true);
    try {
      await flush();
      const res = await TideAPI.botStart({});
      setToast(res?.status === 'already_running'
        ? { msg: `이미 실행 중입니다 (전략: ${res.strategy})`, tone: 'warn' }
        : { msg: '봇이 시작되었습니다', tone: 'up' });
      status.reload();
    } catch (e) { setToast({ msg: e.message, tone: 'down' }); }
    finally { setBusy(false); }
  };
  const saveLabel = { pending: '저장 대기…', saving: '저장 중…', saved: '저장됨', error: '저장 실패', empty: '1개 이상 골라야 저장됩니다' }[saveState];
  const stop = async () => {
    setBusy(true);
    try { await TideAPI.botStop(); setToast({ msg: '봇이 정지되었습니다', tone: 'warn' }); status.reload(); }
    catch (e) { setToast({ msg: e.message, tone: 'down' }); }
    finally { setBusy(false); }
  };

  return (
    <Shell active="bot" setActive={setActive} user={user} onLogout={() => TideAPI.logout().then(() => location.href = '/login.html')}
           title="봇 / 전략" subtitle="자동 매매 봇을 제어합니다">
      {!user?.has_upbit_keys && <div style={{ marginBottom: 16 }}><ApiKeyWarning go={() => setActive('settings')}/></div>}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 360px', gap: 16 }}>
        <Card padding={24}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
            <div>
              <div style={{ fontSize: 16, fontWeight: 700 }}>봇 설정</div>
              <div style={{ fontSize: 12, color: 'var(--ink-500)', marginTop: 4 }}>감시할 코인을 고르면 바로 저장됩니다{running ? ' — 도는 봇에도 곧바로 적용합니다' : ''}.</div>
            </div>
            <Badge tone={status.data?.running ? 'live' : 'neutral'} dot>
              {status.data?.running ? '실행 중' : '정지'}
            </Badge>
          </div>

          <div style={{ marginBottom: 8 }}>
            {tickers !== null
              ? <TickerPicker value={tickers} onChange={changeTickers}/>
              : loadFailed
                ? <div style={{ fontSize: 12, color: 'var(--warn)' }}>
                    감시 목록을 불러오지 못했습니다 — 불러올 때까지 고칠 수 없습니다.{' '}
                    <button type="button" onClick={loadTickers} style={{ border: 'none', background: 'none', color: 'var(--tide-primary)', fontWeight: 700, cursor: 'pointer', padding: 0 }}>다시 불러오기</button>
                  </div>
                : <div style={{ fontSize: 12, color: 'var(--ink-500)' }}>감시 목록을 불러오는 중…</div>}
          </div>
          <div data-testid="save-state" style={{ fontSize: 11, minHeight: 16, color: saveState === 'error' || saveState === 'empty' ? 'var(--warn)' : 'var(--ink-500)' }}>
            {saveLabel || ''}
          </div>

          <div style={{ display: 'flex', gap: 8, marginTop: 24 }}>
            {running ? (
              <Button onClick={stop} variant="danger" icon="pause" disabled={busy} size="lg" full>봇 정지</Button>
            ) : (
              <Button onClick={start} icon="play" disabled={busy || !user?.has_upbit_keys} size="lg" full>봇 시작</Button>
            )}
          </div>
        </Card>

        <div>
          <Card padding={20}>
            <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 12 }}>현재 상태</div>
            {status.loading ? <span className="tide-spinner"/> :
              <div style={{ fontSize: 12 }}>
                {[
                  ['실행', status.data?.running ? '✓ Yes' : '— No'],
                  ['전략', status.data?.strategy || '—'],
                  // 청산 대기는 목록에서 빠졌지만 보유나 미체결 주문이 남아 청산까지만 관리하는 종목이다.
                  ['거래쌍', (status.data?.entry_tickers || []).join(', ') || '—'],
                  ['청산 대기', (status.data?.exit_only_tickers || []).join(', ')],
                ].filter(([k, v]) => v).map(([k, v]) => (
                  <div key={k} style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderTop: '1px solid var(--ink-100)' }}>
                    <span style={{ color: 'var(--ink-500)' }}>{k}</span><span style={{ fontWeight: 600 }}>{v}</span>
                  </div>
                ))}
              </div>}
          </Card>

        </div>
      </div>
      <div style={{ marginTop: 16 }}>
        <TradingParamsCard setToast={setToast} onSaved={status.reload}/>
      </div>
      {toast && <Toast message={toast.msg} tone={toast.tone} onClose={() => setToast(null)}/>}
    </Shell>
  );
}

// null 은 미청산이거나 매수 기록이 없어 보유기간을 계산할 수 없는 경우다.
function fmtHolding(sec) {
  if (sec == null) return '—';
  const d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60);
  if (d > 0) return `${d}일 ${h}시간`;
  if (h > 0) return `${h}시간 ${m}분`;
  return `${m}분`;
}

const SELL_REASON_LABEL = {
  TAKE_PROFIT: '익절', TRAILING_STOP: '트레일링', STOP_LOSS: '손절',
  DAILY_RESET: '일일리셋', MANUAL: '수동',
};

const RT_GRID = '110px 1.4fr 1.4fr 96px 120px 100px 92px';

function OrdersPage({ user, setActive }) {
  const [tab, setTab] = React.useState('roundtrips');
  const eventsLive = useUserEventsLive(!!user?.has_upbit_keys);
  const trades = useAPI(() => TideAPI.trades().catch(() => null), [], eventsLive ? 30000 : 10000);
  const rt = useAPI(() => TideAPI.roundTrips().catch(() => null), [], eventsLive ? 30000 : 10000);
  useUserEventReload(!!user?.has_upbit_keys, ['trade'], trades.reload);
  useUserEventReload(!!user?.has_upbit_keys, ['trade'], rt.reload);
  const list = trades.data?.records || [];
  const rtList = rt.data?.round_trips || [];

  const isRT = tab === 'roundtrips';
  const src = isRT ? rt : trades;
  // rtList 는 limit 으로 잘린 목록이라 건수는 API 의 total 을 쓴다.
  const count = isRT ? (rt.data?.total ?? rtList.length) : list.length;

  const tabBtn = (key, label) => (
    <button onClick={() => setTab(key)}
            style={{ padding: '7px 14px', fontSize: 12.5, fontWeight: 600, cursor: 'pointer', borderRadius: 8,
                     border: `1px solid ${tab === key ? 'transparent' : 'var(--ink-100)'}`,
                     background: tab === key ? 'var(--ink-700)' : 'transparent',
                     color: tab === key ? '#fff' : 'var(--ink-500)' }}>{label}</button>
  );

  const roundTripRows = (
    <>
      <div style={{ display: 'grid', gridTemplateColumns: RT_GRID,
                    padding: '14px 24px', fontSize: 11, color: 'var(--ink-500)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.04em', borderBottom: '1px solid var(--ink-100)', gap: 10 }}>
        <span>거래쌍</span><span>매수</span><span>매도</span>
        <span style={{ textAlign: 'right' }}>수익률</span>
        <span style={{ textAlign: 'right' }}>손익(원)</span>
        <span>보유기간</span><span>사유</span>
      </div>
      {rtList.map((r, i) => {
        const tone = r.pnl_percent == null ? 'var(--ink-500)' : (r.pnl_percent > 0 ? 'var(--up)' : 'var(--down)');
        // net 이 없는 옛 기록은 gross 로 근사한다. 색은 표시하는 금액의 부호를 따른다.
        const isNet = r.pnl_amount_net != null;
        const amount = isNet ? r.pnl_amount_net : r.pnl_amount_gross;
        const won = amount == null ? null : Math.round(amount);
        const amountTone = !won ? 'var(--ink-500)' : (won > 0 ? 'var(--up)' : 'var(--down)');
        return (
          <div key={i} style={{ display: 'grid', gridTemplateColumns: RT_GRID, padding: '14px 24px', fontSize: 13, alignItems: 'center', borderBottom: '1px solid var(--ink-100)', gap: 10 }}>
            <span style={{ fontWeight: 600 }}>
              {r.ticker}
              {r.open && <span style={{ display: 'block', fontSize: 10.5, color: 'var(--up)', fontWeight: 600 }}>
                {r.partially_closed ? '일부 매도 · 보유 중' : '보유 중'}
              </span>}
              {/* 남은 수량은 다음 매수 기록의 수량에 포함된다 */}
              {r.carried_volume != null && <span style={{ display: 'block', fontSize: 10.5, color: 'var(--ink-500)', fontWeight: 600 }}>
                잔량 다음 매수로 이월
              </span>}
            </span>
            <span>
              {r.buy_count === 0 ? <span style={{ color: 'var(--ink-500)', fontSize: 12 }}>매수 기록 없음</span> : <>
                <span className="num" style={{ display: 'block', fontSize: 11, color: 'var(--ink-500)' }}>
                  {(r.entry_at || '').toString().slice(0, 16).replace('T', ' ')}
                </span>
                <span className="num" style={{ fontWeight: 600 }}>{fmtKRW(r.entry_price)}</span>
                {r.buy_count > 1 && <span style={{ fontSize: 10.5, color: 'var(--ink-500)' }}> · {r.buy_count}회 분할</span>}
                {/* 앞 기록이 잘렸거나 초과 매도라 평단과 손익을 믿을 수 없는 경우 */}
                {r.partial && <span style={{ fontSize: 10.5, color: 'var(--down)' }}> · 기록 불완전</span>}
              </>}
            </span>
            <span>
              {r.sell_count === 0 ? (
                <span style={{ color: 'var(--ink-500)', fontSize: 12 }}>—</span>
              ) : <>
                <span className="num" style={{ display: 'block', fontSize: 11, color: 'var(--ink-500)' }}>
                  {(r.exit_at || '').toString().slice(0, 16).replace('T', ' ')}
                </span>
                <span className="num" style={{ fontWeight: 600 }}>{fmtKRW(r.exit_price)}</span>
                {r.sell_count > 1 && <span style={{ fontSize: 10.5, color: 'var(--ink-500)' }}> · {r.sell_count}회 분할</span>}
              </>}
            </span>
            <span className="num" style={{ textAlign: 'right', fontWeight: 700, color: tone }}>
              {r.pnl_percent == null ? '—' : `${r.pnl_percent > 0 ? '+' : ''}${fmtNum(r.pnl_percent, 2)}%`}
            </span>
            <span className="num" style={{ textAlign: 'right', color: amountTone }}>
              {won == null ? '—' : `${isNet ? '' : '≈ '}${fmtKRW(won)}`}
            </span>
            <span style={{ fontSize: 12, color: 'var(--ink-500)' }}>{fmtHolding(r.holding_seconds)}</span>
            <span>{r.reason ? <Badge tone="primary">{SELL_REASON_LABEL[r.reason] || r.reason}</Badge> : '—'}</span>
          </div>
        );
      })}
      <div style={{ padding: '12px 24px', fontSize: 11, color: 'var(--ink-500)', borderTop: '1px solid var(--ink-100)', lineHeight: 1.6 }}>
        손익(원)은 매도 기록의 <b>실현 손익</b>(설정 수수료율 차감, 기록 시점 평단·가격 기준) 합입니다. <b>≈</b> 는 손익 기록이 없는 옛 매도의 근사(매도액 − 판 만큼의 매수 원가, <b>수수료 미차감</b>)라 수익률(%)과 부호가 다를 수 있습니다. 오래된 기록은 수수료가 반영되지 않았을 수 있습니다.
      </div>
    </>
  );

  return (
    <Shell active="orders" setActive={setActive} user={user} onLogout={() => TideAPI.logout().then(() => location.href = '/login.html')}
           title="주문·내역" subtitle={isRT ? `총 ${count}건의 매매 (매수→매도)` : `총 ${count}건의 체결`}
           actions={<Button size="sm" icon="refresh" variant="outline" onClick={src.reload}>새로고침</Button>}>
      <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
        {tabBtn('roundtrips', '매매 단위')}
        {tabBtn('raw', '전체 체결 내역')}
      </div>

      {isRT && rt.data?.truncated &&
        <div style={{ marginBottom: 12, padding: '10px 14px', borderRadius: 8, background: 'var(--ink-50)', fontSize: 12, color: 'var(--ink-500)' }}>
          기록이 많아 오래된 일부가 잘렸습니다 — 가장 오래된 항목은 매수 기록이 비어 있을 수 있습니다.
        </div>}

      <Card padding={0}>
        {src.loading ? <div style={{ padding: 40, textAlign: 'center' }}><span className="tide-spinner"/></div> :
         isRT ? (
          !rtList.length ? <Empty icon="orders" title="매매 내역이 없습니다" message="봇을 실행해보세요"/> : roundTripRows
         ) :
         !list.length ? <Empty icon="orders" title="거래 내역이 없습니다" message="봇을 실행해보세요"/> :
        <>
          <div style={{ display: 'grid', gridTemplateColumns: '160px 110px 70px 1fr 1fr 1fr 110px',
                        padding: '14px 24px', fontSize: 11, color: 'var(--ink-500)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.04em', borderBottom: '1px solid var(--ink-100)', gap: 10 }}>
            <span>시간</span><span>거래쌍</span><span>방향</span>
            <span style={{ textAlign: 'right' }}>가격</span><span style={{ textAlign: 'right' }}>수량</span><span style={{ textAlign: 'right' }}>금액</span><span>전략</span>
          </div>
          {list.slice(0, 100).map((o, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '160px 110px 70px 1fr 1fr 1fr 110px', padding: '14px 24px', fontSize: 13, alignItems: 'center', borderBottom: '1px solid var(--ink-100)', gap: 10 }}>
              <span className="num" style={{ color: 'var(--ink-500)', fontSize: 11.5 }}>{(o.created_at || o.tradedAt || o.traded_at || '').toString().slice(0, 19).replace('T', ' ')}</span>
              <span style={{ fontWeight: 600 }}>{o.market || o.ticker}</span>
              <span style={{ color: (o.side || '').toUpperCase() === 'BUY' ? 'var(--up)' : 'var(--down)', fontWeight: 600, fontSize: 12 }}>{(o.side || '').toUpperCase() === 'BUY' ? '매수' : '매도'}</span>
              <span className="num" style={{ textAlign: 'right' }}>{fmtKRW(o.price)}</span>
              <span className="num" style={{ textAlign: 'right' }}>{fmtNum(o.volume, 6)}</span>
              {(() => {
                // order_amount 가 없으면 total_amount 를 쓰는데, 그 값은 경로마다 뜻이 달라 흐리게 표시한다.
                const orderAmount = o.orderAmount ?? o.order_amount;
                const known = orderAmount !== null && orderAmount !== undefined;
                return known
                  ? <span className="num" style={{ textAlign: 'right', fontWeight: 600 }}>{fmtKRW(orderAmount)}</span>
                  : <span className="num" title="체결금액 미상 — 기록 시점 금액(엔진 매수는 보유 원가, 매도는 평가액)" style={{ textAlign: 'right', fontWeight: 600, color: 'var(--ink-500)' }}>{fmtKRW(o.totalAmount ?? o.total_amount)}</span>;
              })()}
              <span><Badge tone="primary">{o.strategy || '—'}</Badge></span>
            </div>
          ))}
        </>}
      </Card>
    </Shell>
  );
}

function WalletPage({ user, setActive }) {
  const eventsLive = useUserEventsLive(!!user?.has_upbit_keys);
  const portfolio = useAPI(() => TideAPI.portfolio(), [], eventsLive ? 30000 : 10000);
  useUserEventReload(!!user?.has_upbit_keys, ['trade'], portfolio.reload);
  const market = useMarketStream(!!user?.has_upbit_keys);
  const p = liveHoldings(portfolio.data, market.fresh);

  return (
    <Shell active="wallet" setActive={setActive} user={user} onLogout={() => TideAPI.logout().then(() => location.href = '/login.html')}
           title="지갑·자산" subtitle="Upbit 계좌"
           actions={<>{user?.has_upbit_keys && <LiveBadge live={!!market.fresh}/>}<Button size="sm" icon="refresh" variant="outline" onClick={portfolio.reload}>새로고침</Button></>}>
      {!user?.has_upbit_keys ? <ApiKeyWarning go={() => setActive('settings')}/> :
       portfolio.loading ? <div style={{ padding: 40, textAlign: 'center' }}><span className="tide-spinner"/></div> :
       portfolio.error ? <Card padding={24}><div style={{ color: 'var(--down)', fontSize: 13 }}>{portfolio.error}</div></Card> :
       <>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16, marginBottom: 16 }}>
          <Card padding={24}><div style={{ fontSize: 12, color: 'var(--ink-500)' }}>총 평가</div><div className="num" style={{ fontSize: 26, fontWeight: 700, marginTop: 8 }}>{fmtKRW(p?.total_eval)}</div></Card>
          <Card padding={24}><div style={{ fontSize: 12, color: 'var(--ink-500)' }}>KRW 잔고</div><div className="num" style={{ fontSize: 26, fontWeight: 700, marginTop: 8 }}>{fmtKRW(p?.krw_balance)}</div></Card>
          <Card padding={24}><div style={{ fontSize: 12, color: 'var(--ink-500)' }}>코인 종류</div><div className="num" style={{ fontSize: 26, fontWeight: 700, marginTop: 8 }}>{p?.holdings?.length || 0}</div></Card>
        </div>

        <Card padding={0}>
          <div style={{ padding: '16px 24px', borderBottom: '1px solid var(--ink-100)', fontSize: 14, fontWeight: 600 }}>자산 상세</div>
          {!p?.holdings?.length ? <Empty icon="wallet" title="보유 코인 없음"/> :
          p.holdings.map((h, i) => (
            <div key={h.currency} style={{ display: 'grid', gridTemplateColumns: '40px 1fr 1fr 1fr 1fr 110px', alignItems: 'center', padding: '14px 24px', borderTop: i ? '1px solid var(--ink-100)' : 'none', gap: 12 }}>
              <div style={{ width: 32, height: 32, borderRadius: 999, background: 'var(--ink-100)', display:'flex',alignItems:'center',justifyContent:'center',fontSize:11,fontWeight:700 }}>{h.currency}</div>
              <div><div style={{ fontSize: 13.5, fontWeight: 600 }}>{h.market}</div><div className="num" style={{ fontSize: 11, color: 'var(--ink-500)' }}>{fmtNum(h.balance, 8)}</div></div>
              <div className="num" style={{ fontSize: 12, textAlign: 'right' }}>평단 {fmtKRW(h.avg_buy_price)}</div>
              <div className="num" style={{ fontSize: 12, textAlign: 'right' }}>현재 {fmtKRW(h.current_price)}</div>
              <div className="num" style={{ fontSize: 13, fontWeight: 600, textAlign: 'right' }}>{fmtKRW(h.eval_amount)}</div>
              <div style={{ textAlign: 'right' }}><Badge tone={h.pnl_percent >= 0 ? 'up' : 'down'}>{fmtPct(h.pnl_percent)}</Badge></div>
            </div>
          ))}
        </Card>
       </>}
    </Shell>
  );
}

// 표의 가격이 스트림에서 오는지. 아니면 주기적으로 다시 읽는 서버 값이다 — 숫자와 같은 조건(freshRows)으로 정한다.
function LiveBadge({ live }) {
  return live ? <Badge tone="live" dot>실시간</Badge> : <Badge dot>10초 갱신</Badge>;
}

// 한 줄. 가격이 같으면 다시 그리지 않는다(300줄이 1초마다 바뀐 몇 줄만 그린다).
const MarketRow = React.memo(function MarketRow({ r, first }) {
  const prev = React.useRef(r.p);
  const dir = r.p > prev.current ? 'up' : r.p < prev.current ? 'down' : null;
  prev.current = r.p;
  const tone = r.r > 0 ? 'var(--up)' : r.r < 0 ? 'var(--down)' : 'var(--ink-600)';
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 1fr 0.8fr 1fr', alignItems: 'center', padding: '10px 24px', borderTop: first ? 'none' : '1px solid var(--ink-100)', gap: 12 }}>
      <div style={{ fontSize: 13.5, fontWeight: 600 }}>{r.m.replace('KRW-', '')}<span style={{ fontSize: 11, color: 'var(--ink-400)', marginLeft: 6 }}>{r.m}</span></div>
      <div className="num" style={{ textAlign: 'right' }}><span key={r.p} className={dir ? 'flash-' + dir : undefined} style={{ fontSize: 13.5, fontWeight: 600, color: tone, padding: '2px 4px' }}>{r.p.toLocaleString(undefined, { maximumFractionDigits: 8 })}</span></div>
      <div className="num" style={{ fontSize: 13, textAlign: 'right', color: tone }}>{fmtPct(r.r * 100)}</div>
      <div className="num" style={{ fontSize: 12.5, textAlign: 'right', color: 'var(--ink-600)' }}>{Math.round(r.v / 1e6).toLocaleString()}백만</div>
    </div>
  );
}, (a, b) => a.r.p === b.r.p && a.r.r === b.r.r && a.r.v === b.r.v && a.first === b.first);

function MarketPage({ user, setActive }) {
  const { rows, status, fresh } = useMarketStream();
  const [query, setQuery] = React.useState('');

  const list = React.useMemo(() => {
    const q = query.trim().toUpperCase();
    return [...rows.values()].filter(r => !q || r.m.includes(q)).sort((a, b) => b.v - a.v);
  }, [rows, query]);

  // 연결은 살아 있는데 10초 넘게 갱신이 없으면 시세가 멈춘 것이다(freshRows).
  const delayed = status === 'live' && !fresh;
  const badge = status === 'live' && !delayed ? <Badge tone="live" dot>실시간</Badge>
    : status === 'live' ? <Badge tone="warn" dot>지연</Badge>
    : status === 'retrying' ? <Badge tone="warn" dot>다시 연결 중</Badge>
    : <Badge dot>연결 중</Badge>;

  return (
    <Shell active="market" setActive={setActive} user={user} onLogout={() => TideAPI.logout().then(() => location.href = '/login.html')}
           title="시세판" subtitle="Upbit 원화 마켓 · 1초마다 갱신"
           actions={badge}>
      <Card padding={0}>
        <div style={{ padding: '14px 24px', borderBottom: '1px solid var(--ink-100)', display: 'flex', alignItems: 'center', gap: 12 }}>
          <Icon d={Icons.search} size={16} stroke="var(--ink-400)"/>
          <input className="tide-input" value={query} onChange={e => setQuery(e.target.value)} placeholder="코인 검색 (예: BTC)" style={{ flex: 1, border: 'none', outline: 'none', fontSize: 14, fontFamily: 'inherit' }}/>
          <span style={{ fontSize: 12, color: 'var(--ink-500)' }}>{list.length}개 · 거래대금 순</span>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 1fr 0.8fr 1fr', padding: '8px 24px', gap: 12, fontSize: 11.5, color: 'var(--ink-500)', borderBottom: '1px solid var(--ink-100)' }}>
          <div>마켓</div><div style={{ textAlign: 'right' }}>현재가</div><div style={{ textAlign: 'right' }}>24시간</div><div style={{ textAlign: 'right' }}>거래대금</div>
        </div>
        {!rows.size ? <Empty icon="market" title={status === 'live' ? '시세 준비 중' : '연결 중'} message="업비트 시세를 받는 중입니다"/> :
          list.map((r, i) => <MarketRow key={r.m} r={r} first={i === 0}/>)}
      </Card>
    </Shell>
  );
}

function SettingsPage({ user, setActive, refreshUser }) {
  const [accessKey, setAccessKey] = React.useState('');
  const [secretKey, setSecretKey] = React.useState('');
  const [discordUrl, setDiscordUrl] = React.useState('');
  const [busyKeys, setBusyKeys] = React.useState(false);
  const [busyDiscord, setBusyDiscord] = React.useState(false);
  const [toast, setToast] = React.useState(null);

  const saveKeys = async () => {
    if (!accessKey || !secretKey) return;
    setBusyKeys(true);
    try {
      await TideAPI.saveKeys(accessKey, secretKey);
      setToast({ msg: 'API 키가 저장되었습니다', tone: 'up' });
      setAccessKey(''); setSecretKey('');
      await refreshUser();
    } catch (e) { setToast({ msg: e.message, tone: 'down' }); }
    finally { setBusyKeys(false); }
  };

  const saveDiscord = async (clear = false) => {
    setBusyDiscord(true);
    try {
      await TideAPI.updateSettings({ discord_webhook_url: clear ? '' : discordUrl });
      setToast({ msg: clear ? 'Discord 웹훅이 해제되었습니다' : 'Discord 웹훅이 저장되었습니다', tone: 'up' });
      setDiscordUrl('');
      await refreshUser();
    } catch (e) { setToast({ msg: e.message, tone: 'down' }); }
    finally { setBusyDiscord(false); }
  };

  return (
    <Shell active="settings" setActive={setActive} user={user} onLogout={() => TideAPI.logout().then(() => location.href = '/login.html')}
           title="설정" subtitle="API 키, Discord 알림">
      <Card padding={28} style={{ maxWidth: 640 }}>
        <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 4 }}>Upbit API 키</div>
        <div style={{ fontSize: 13, color: 'var(--ink-500)', marginBottom: 20 }}>
          현재 상태: {user?.has_upbit_keys ?
            <Badge tone="live" dot>등록됨</Badge> :
            <Badge tone="warn">미등록</Badge>}
        </div>

        <div style={{ marginBottom: 14 }}>
          <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>Access Key</div>
          <input className="tide-input mono" value={accessKey} onChange={e => setAccessKey(e.target.value)} placeholder="••••••••••••••••" autoComplete="off"/>
        </div>
        <div style={{ marginBottom: 20 }}>
          <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>Secret Key</div>
          {/* 비밀번호 관리자가 로그인 비밀번호로 채우지 않게 one-time-code 를 쓴다. password 칸에서 off 는 무시된다. */}
          <input className="tide-input mono" type="password" value={secretKey} onChange={e => setSecretKey(e.target.value)} placeholder="••••••••••••••••" autoComplete="one-time-code"/>
        </div>

        <Button onClick={saveKeys} disabled={busyKeys || !accessKey || !secretKey} size="lg">{busyKeys ? '저장 중…' : 'API 키 저장'}</Button>

        <div style={{ marginTop: 24, padding: 16, background: 'var(--tide-primary-soft)', borderRadius: 10, fontSize: 12.5, color: 'var(--tide-primary-ink)' }}>
          🛡 API 키는 AES-GCM으로 암호화되어 저장됩니다. <strong>출금 권한은 절대 부여하지 마세요.</strong>
        </div>
      </Card>

      <Card padding={28} style={{ maxWidth: 640, marginTop: 16 }}>
        <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 4 }}>Discord 알림</div>
        <div style={{ fontSize: 13, color: 'var(--ink-500)', marginBottom: 20 }}>
          현재 상태: {user?.has_discord_webhook ?
            <Badge tone="live" dot>등록됨</Badge> :
            <Badge tone="warn">미등록</Badge>}
        </div>
        <div style={{ marginBottom: 14 }}>
          <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>Webhook URL</div>
          <input className="tide-input mono" value={discordUrl} onChange={e => setDiscordUrl(e.target.value)} placeholder="https://discord.com/api/webhooks/..."/>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <Button onClick={() => saveDiscord(false)} disabled={busyDiscord || !discordUrl} size="lg">{busyDiscord ? '저장 중…' : '저장'}</Button>
          {user?.has_discord_webhook && <Button onClick={() => saveDiscord(true)} disabled={busyDiscord} size="lg" variant="outline">해제</Button>}
        </div>
        <div style={{ marginTop: 16, fontSize: 11.5, color: 'var(--ink-500)' }}>discord.com 도메인의 HTTPS 웹훅 URL만 허용됩니다.</div>
      </Card>

      {toast && <Toast message={toast.msg} tone={toast.tone} onClose={() => setToast(null)}/>}
    </Shell>
  );
}

window.Dashboard = Dashboard;
window.MarketPage = MarketPage;
window.BotPage = BotPage;
window.OrdersPage = OrdersPage;
window.WalletPage = WalletPage;
window.SettingsPage = SettingsPage;
