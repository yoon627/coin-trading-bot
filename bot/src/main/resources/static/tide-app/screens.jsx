// Tide — Live screens connected to Spring Boot API

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

// ── DASHBOARD ─────────────────────────────────────────────
function Dashboard({ user, setActive }) {
  const portfolio = useAPI(() => TideAPI.portfolio().catch(() => null), [], 10000);
  const status = useAPI(() => TideAPI.botStatus().catch(() => null), [], 5000);

  const hasKeys = user?.has_upbit_keys;
  const p = portfolio.data;

  return (
    <Shell active="dashboard" setActive={setActive} user={user} onLogout={() => TideAPI.logout().then(() => location.href = '/login.html')}
           title="대시보드" subtitle={new Date().toLocaleString('ko-KR')}
           actions={<Button size="sm" icon="refresh" variant="outline" onClick={() => { portfolio.reload(); status.reload(); }}>새로고침</Button>}>
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

// ── BOT / STRATEGY ────────────────────────────────────────
function BotPage({ user, setActive }) {
  const status = useAPI(() => TideAPI.botStatus().catch(() => null), [], 3000);
  const strategies = useAPI(() => TideAPI.strategies().catch(() => []));
  const performance = useAPI(() => TideAPI.performance().catch(() => null));
  const [tickers, setTickers] = React.useState('KRW-BTC');
  const [selected, setSelected] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [toast, setToast] = React.useState(null);

  React.useEffect(() => {
    if (!selected && status.data?.strategy) setSelected(status.data.strategy);
    else if (!selected && strategies.data?.[0]) setSelected(strategies.data[0].name);
  }, [status.data, strategies.data]);

  const start = async () => {
    setBusy(true);
    try {
      await TideAPI.botStart({ tickers: tickers.split(',').map(s => s.trim()), strategy: selected });
      setToast({ msg: '봇이 시작되었습니다', tone: 'up' });
      status.reload();
    } catch (e) { setToast({ msg: e.message, tone: 'down' }); }
    finally { setBusy(false); }
  };
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
              <div style={{ fontSize: 12, color: 'var(--ink-500)', marginTop: 4 }}>전략과 거래쌍을 선택하고 시작하세요.</div>
            </div>
            <Badge tone={status.data?.running ? 'live' : 'neutral'} dot>
              {status.data?.running ? '실행 중' : '정지'}
            </Badge>
          </div>

          <div style={{ marginBottom: 20 }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ink-700)', marginBottom: 8 }}>거래쌍 (쉼표로 구분)</div>
            <input className="tide-input" value={tickers} onChange={e => setTickers(e.target.value)} placeholder="KRW-BTC, KRW-ETH"/>
          </div>

          <div>
            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ink-700)', marginBottom: 10 }}>전략 선택</div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 8 }}>
              {(strategies.data || []).map(s => (
                <div key={s.name} onClick={() => setSelected(s.name)} style={{
                  padding: 14, border: '1.5px solid', borderColor: selected === s.name ? 'var(--tide-primary)' : 'var(--ink-200)',
                  borderRadius: 12, cursor: 'pointer', background: selected === s.name ? 'var(--tide-primary-soft)' : '#fff',
                }}>
                  <div style={{ fontSize: 13, fontWeight: 600, color: selected === s.name ? 'var(--tide-primary-ink)' : 'var(--ink-900)' }}>{s.name}</div>
                </div>
              ))}
            </div>
          </div>

          <div style={{ display: 'flex', gap: 8, marginTop: 24 }}>
            {status.data?.running ? (
              <Button onClick={stop} variant="danger" icon="pause" disabled={busy} size="lg" full>봇 정지</Button>
            ) : (
              <Button onClick={start} icon="play" disabled={busy || !user?.has_upbit_keys} size="lg" full>봇 시작</Button>
            )}
          </div>
        </Card>

        <div>
          <Card padding={20} style={{ marginBottom: 12 }}>
            <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 12 }}>현재 상태</div>
            {status.loading ? <span className="tide-spinner"/> :
              <div style={{ fontSize: 12 }}>
                {[
                  ['실행', status.data?.running ? '✓ Yes' : '— No'],
                  ['전략', status.data?.strategy || '—'],
                  ['거래쌍', (status.data?.tickers || []).join(', ') || '—'],
                ].map(([k, v]) => (
                  <div key={k} style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderTop: '1px solid var(--ink-100)' }}>
                    <span style={{ color: 'var(--ink-500)' }}>{k}</span><span style={{ fontWeight: 600 }}>{v}</span>
                  </div>
                ))}
              </div>}
          </Card>

          <Card padding={20}>
            <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 12 }}>전략 성과</div>
            {!performance.data?.strategies?.length ? (
              <div style={{ fontSize: 12, color: 'var(--ink-500)', padding: '12px 0' }}>아직 거래 데이터가 없습니다</div>
            ) : (
              performance.data.strategies.slice(0, 5).map(s => (
                <div key={s.strategy} style={{ padding: '10px 0', borderTop: '1px solid var(--ink-100)' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
                    <span style={{ fontWeight: 600 }}>{s.strategy}</span>
                    <span className="num" style={{ color: s.total_pnl_pct >= 0 ? 'var(--up)' : 'var(--down)', fontWeight: 600 }}>{fmtPct(s.total_pnl_pct)}</span>
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--ink-500)', marginTop: 4 }}>
                    {s.total_trades}건 · 승률 {(s.win_rate || 0).toFixed(1)}%
                  </div>
                </div>
              ))
            )}
          </Card>
        </div>
      </div>
      {toast && <Toast message={toast.msg} tone={toast.tone} onClose={() => setToast(null)}/>}
    </Shell>
  );
}

// ── TRADE (manual buy/sell) ───────────────────────────────
function TradePage({ user, setActive }) {
  const [market, setMarket] = React.useState('KRW-BTC');
  const [amount, setAmount] = React.useState('10000');
  const [volume, setVolume] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [toast, setToast] = React.useState(null);
  const portfolio = useAPI(() => TideAPI.portfolio().catch(() => null), [], 10000);
  const prices = useAPI(() => TideAPI.pricesLatest().catch(() => null), [], 3000);

  const buy = async () => {
    setBusy(true);
    try {
      await TideAPI.buy(market, parseFloat(amount));
      setToast({ msg: `${market} 매수 주문 완료`, tone: 'up' });
      portfolio.reload();
    } catch (e) { setToast({ msg: e.message, tone: 'down' }); }
    finally { setBusy(false); }
  };
  const sell = async (sellAll) => {
    setBusy(true);
    try {
      await TideAPI.sell(market, sellAll ? { sellAll: true } : { volume });
      setToast({ msg: `${market} 매도 주문 완료`, tone: 'down' });
      portfolio.reload();
    } catch (e) { setToast({ msg: e.message, tone: 'down' }); }
    finally { setBusy(false); }
  };

  return (
    <Shell active="trade" setActive={setActive} user={user} onLogout={() => TideAPI.logout().then(() => location.href = '/login.html')}
           title="차트 매매" subtitle="수동 매수·매도">
      {!user?.has_upbit_keys && <div style={{ marginBottom: 16 }}><ApiKeyWarning go={() => setActive('settings')}/></div>}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 380px', gap: 16 }}>
        <Card padding={24}>
          <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 16 }}>실시간 가격</div>
          {!prices.data ? <div style={{ padding: 40, textAlign: 'center' }}><span className="tide-spinner"/></div> :
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 10 }}>
              {Object.entries(prices.data || {}).slice(0, 12).map(([m, price]) => (
                <div key={m} onClick={() => setMarket(m)} style={{
                  padding: 14, borderRadius: 10, cursor: 'pointer',
                  background: market === m ? 'var(--tide-primary-soft)' : 'var(--ink-50)',
                  border: '1px solid', borderColor: market === m ? 'var(--tide-primary)' : 'transparent',
                }}>
                  <div style={{ fontSize: 12, fontWeight: 600 }}>{m}</div>
                  <div className="num" style={{ fontSize: 16, fontWeight: 700, marginTop: 4 }}>{fmtKRW(price)}</div>
                </div>
              ))}
              {!Object.keys(prices.data || {}).length && <Empty icon="chart" title="가격 스트림 없음" message="봇을 시작하면 가격이 수집됩니다"/>}
            </div>
          }
        </Card>

        <Card padding={24}>
          <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 16 }}>주문</div>
          <div style={{ marginBottom: 14 }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ink-700)', marginBottom: 6 }}>거래쌍</div>
            <input className="tide-input" value={market} onChange={e => setMarket(e.target.value.toUpperCase())}/>
          </div>

          <div style={{ marginBottom: 14, padding: 14, background: 'var(--ink-50)', borderRadius: 10 }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--up)', marginBottom: 8 }}>매수</div>
            <div style={{ fontSize: 11, color: 'var(--ink-500)', marginBottom: 6 }}>주문 금액 (KRW)</div>
            <input className="tide-input" type="number" value={amount} onChange={e => setAmount(e.target.value)} style={{ marginBottom: 8 }}/>
            <Button full size="md" disabled={busy || !user?.has_upbit_keys} onClick={buy} style={{ background: 'var(--up)' }}>매수</Button>
          </div>

          <div style={{ padding: 14, background: 'var(--ink-50)', borderRadius: 10 }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--down)', marginBottom: 8 }}>매도</div>
            <div style={{ fontSize: 11, color: 'var(--ink-500)', marginBottom: 6 }}>수량</div>
            <input className="tide-input" value={volume} onChange={e => setVolume(e.target.value)} placeholder="0.001" style={{ marginBottom: 8 }}/>
            <div style={{ display: 'flex', gap: 6 }}>
              <Button size="md" disabled={busy || !user?.has_upbit_keys} onClick={() => sell(false)} style={{ background: 'var(--down)', flex: 1, color: '#fff' }}>매도</Button>
              <Button size="md" variant="outline" disabled={busy || !user?.has_upbit_keys} onClick={() => sell(true)} style={{ flex: 1 }}>전량</Button>
            </div>
          </div>
        </Card>
      </div>
      {toast && <Toast message={toast.msg} tone={toast.tone} onClose={() => setToast(null)}/>}
    </Shell>
  );
}

// ── ORDERS / TRADES HISTORY ───────────────────────────────
function OrdersPage({ user, setActive }) {
  const trades = useAPI(() => TideAPI.trades().catch(() => []), [], 10000);
  const list = trades.data || [];

  return (
    <Shell active="orders" setActive={setActive} user={user} onLogout={() => TideAPI.logout().then(() => location.href = '/login.html')}
           title="주문·내역" subtitle={`총 ${list.length}건의 거래`}
           actions={<Button size="sm" icon="refresh" variant="outline" onClick={trades.reload}>새로고침</Button>}>
      <Card padding={0}>
        {trades.loading ? <div style={{ padding: 40, textAlign: 'center' }}><span className="tide-spinner"/></div> :
         !list.length ? <Empty icon="orders" title="거래 내역이 없습니다" message="봇을 실행하거나 수동 매매를 시작해보세요"/> :
        <>
          <div style={{ display: 'grid', gridTemplateColumns: '160px 110px 70px 1fr 1fr 1fr 110px',
                        padding: '14px 24px', fontSize: 11, color: 'var(--ink-500)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.04em', borderBottom: '1px solid var(--ink-100)', gap: 10 }}>
            <span>시간</span><span>거래쌍</span><span>방향</span>
            <span style={{ textAlign: 'right' }}>가격</span><span style={{ textAlign: 'right' }}>수량</span><span style={{ textAlign: 'right' }}>총액</span><span>전략</span>
          </div>
          {list.slice(0, 100).map((o, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '160px 110px 70px 1fr 1fr 1fr 110px', padding: '14px 24px', fontSize: 13, alignItems: 'center', borderBottom: '1px solid var(--ink-100)', gap: 10 }}>
              <span className="num" style={{ color: 'var(--ink-500)', fontSize: 11.5 }}>{(o.tradedAt || o.traded_at || '').toString().slice(0, 19).replace('T', ' ')}</span>
              <span style={{ fontWeight: 600 }}>{o.market || o.ticker}</span>
              <span style={{ color: (o.side || '').toUpperCase() === 'BUY' ? 'var(--up)' : 'var(--down)', fontWeight: 600, fontSize: 12 }}>{(o.side || '').toUpperCase() === 'BUY' ? '매수' : '매도'}</span>
              <span className="num" style={{ textAlign: 'right' }}>{fmtKRW(o.price)}</span>
              <span className="num" style={{ textAlign: 'right' }}>{fmtNum(o.volume, 6)}</span>
              <span className="num" style={{ textAlign: 'right', fontWeight: 600 }}>{fmtKRW(o.totalAmount || o.total_amount)}</span>
              <span><Badge tone="primary">{o.strategy || '—'}</Badge></span>
            </div>
          ))}
        </>}
      </Card>
    </Shell>
  );
}

// ── BACKTEST ──────────────────────────────────────────────
function BacktestPage({ user, setActive }) {
  const strategies = useAPI(() => TideAPI.strategies().catch(() => []));
  const [strategy, setStrategy] = React.useState('');
  const [ticker, setTicker] = React.useState('KRW-BTC');
  const [days, setDays] = React.useState(180);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState(null);
  const [toast, setToast] = React.useState(null);

  const run = async () => {
    setBusy(true);
    try {
      const r = await TideAPI.backtest({ strategy: strategy || undefined, ticker, days: parseInt(days) });
      setResult(r);
    } catch (e) { setToast({ msg: e.message, tone: 'down' }); }
    finally { setBusy(false); }
  };

  return (
    <Shell active="backtest" setActive={setActive} user={user} onLogout={() => TideAPI.logout().then(() => location.href = '/login.html')}
           title="백테스팅" subtitle="과거 데이터로 전략을 검증합니다">
      <div style={{ display: 'grid', gridTemplateColumns: '320px 1fr', gap: 16 }}>
        <Card padding={24}>
          <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 16 }}>설정</div>
          <div style={{ marginBottom: 14 }}>
            <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>전략 (비워두면 전체 비교)</div>
            <select className="tide-input" value={strategy} onChange={e => setStrategy(e.target.value)}>
              <option value="">— 전체 비교 —</option>
              {(strategies.data || []).map(s => <option key={s.name} value={s.name}>{s.name}</option>)}
            </select>
          </div>
          <div style={{ marginBottom: 14 }}>
            <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>거래쌍</div>
            <input className="tide-input" value={ticker} onChange={e => setTicker(e.target.value.toUpperCase())}/>
          </div>
          <div style={{ marginBottom: 20 }}>
            <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>기간 (일)</div>
            <input className="tide-input" type="number" value={days} onChange={e => setDays(e.target.value)} min="30" max="200"/>
          </div>
          <Button full size="lg" icon="play" onClick={run} disabled={busy || !user?.has_upbit_keys}>
            {busy ? '실행 중…' : '백테스트 실행'}
          </Button>
        </Card>

        <Card padding={24}>
          <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 16 }}>결과</div>
          {!result ? <Empty icon="backtest" title="결과 없음" message="설정 후 실행해보세요"/> :
            <pre className="mono tide-scroll" style={{ fontSize: 11.5, background: 'var(--ink-50)', padding: 16, borderRadius: 10, maxHeight: 500, overflow: 'auto' }}>
              {JSON.stringify(result, null, 2)}
            </pre>}
        </Card>
      </div>
      {toast && <Toast message={toast.msg} tone={toast.tone} onClose={() => setToast(null)}/>}
    </Shell>
  );
}

// ── WALLET ────────────────────────────────────────────────
function WalletPage({ user, setActive }) {
  const portfolio = useAPI(() => TideAPI.portfolio().catch(() => null), [], 10000);
  const p = portfolio.data;

  return (
    <Shell active="wallet" setActive={setActive} user={user} onLogout={() => TideAPI.logout().then(() => location.href = '/login.html')}
           title="지갑·자산" subtitle="Upbit 계좌"
           actions={<Button size="sm" icon="refresh" variant="outline" onClick={portfolio.reload}>새로고침</Button>}>
      {!user?.has_upbit_keys ? <ApiKeyWarning go={() => setActive('settings')}/> :
       portfolio.loading ? <div style={{ padding: 40, textAlign: 'center' }}><span className="tide-spinner"/></div> :
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

// ── SETTINGS (API keys) ───────────────────────────────────
function SettingsPage({ user, setActive, refreshUser }) {
  const [accessKey, setAccessKey] = React.useState('');
  const [secretKey, setSecretKey] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [toast, setToast] = React.useState(null);

  const save = async () => {
    if (!accessKey || !secretKey) return;
    setBusy(true);
    try {
      await TideAPI.saveKeys(accessKey, secretKey);
      setToast({ msg: 'API 키가 저장되었습니다', tone: 'up' });
      setAccessKey(''); setSecretKey('');
      await refreshUser();
    } catch (e) { setToast({ msg: e.message, tone: 'down' }); }
    finally { setBusy(false); }
  };

  return (
    <Shell active="settings" setActive={setActive} user={user} onLogout={() => TideAPI.logout().then(() => location.href = '/login.html')}
           title="설정" subtitle="Upbit API 키 관리">
      <Card padding={28} style={{ maxWidth: 640 }}>
        <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 4 }}>Upbit API 키</div>
        <div style={{ fontSize: 13, color: 'var(--ink-500)', marginBottom: 20 }}>
          현재 상태: {user?.has_upbit_keys ?
            <Badge tone="live" dot>등록됨</Badge> :
            <Badge tone="warn">미등록</Badge>}
        </div>

        <div style={{ marginBottom: 14 }}>
          <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>Access Key</div>
          <input className="tide-input mono" value={accessKey} onChange={e => setAccessKey(e.target.value)} placeholder="••••••••••••••••"/>
        </div>
        <div style={{ marginBottom: 20 }}>
          <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>Secret Key</div>
          <input className="tide-input mono" type="password" value={secretKey} onChange={e => setSecretKey(e.target.value)} placeholder="••••••••••••••••"/>
        </div>

        <Button onClick={save} disabled={busy || !accessKey || !secretKey} size="lg">{busy ? '저장 중…' : 'API 키 저장'}</Button>

        <div style={{ marginTop: 24, padding: 16, background: 'var(--tide-primary-soft)', borderRadius: 10, fontSize: 12.5, color: 'var(--tide-primary-ink)' }}>
          🛡 API 키는 AES-GCM으로 암호화되어 저장됩니다. <strong>출금 권한은 절대 부여하지 마세요.</strong>
        </div>
      </Card>
      {toast && <Toast message={toast.msg} tone={toast.tone} onClose={() => setToast(null)}/>}
    </Shell>
  );
}

window.Dashboard = Dashboard;
window.BotPage = BotPage;
window.TradePage = TradePage;
window.OrdersPage = OrdersPage;
window.BacktestPage = BacktestPage;
window.WalletPage = WalletPage;
window.SettingsPage = SettingsPage;
