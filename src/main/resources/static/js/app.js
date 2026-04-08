// ── Auth ──
const token = localStorage.getItem('token');
if (!token) window.location.href = '/login.html';

function authHeaders() {
    return { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' };
}

async function fetchJson(url, options = {}) {
    options.headers = { ...authHeaders(), ...options.headers };
    const res = await fetch(url, options);
    if (res.status === 401) { logout(); return null; }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
}

function logout() {
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    window.location.href = '/login.html';
}

// ── Init ──
document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('user-info').textContent = localStorage.getItem('username') || '';
    updateClock();
    setInterval(updateClock, 1000);
    initUser();
    refreshAll();
    setInterval(refreshAll, 5000);
});

async function initUser() {
    try {
        const me = await fetchJson('/api/user/me');
        if (me && !me.has_upbit_keys) {
            document.getElementById('keys-banner').style.display = '';
        }
    } catch (_) {}
}

async function refreshAll() {
    await Promise.allSettled([refreshStatus(), refreshPortfolio(), refreshTrades()]);
    document.getElementById('last-updated').textContent = 'Updated: ' + new Date().toLocaleTimeString('ko-KR');
}

// ── Bot Status ──
async function refreshStatus() {
    try {
        const data = await fetchJson('/api/bot/status');
        if (!data) return;
        const badge = document.getElementById('status-badge');
        const btnStart = document.getElementById('btn-start');
        const btnStop = document.getElementById('btn-stop');

        if (data.running) {
            badge.textContent = 'RUNNING'; badge.className = 'badge badge-running';
            btnStart.disabled = true; btnStop.disabled = false;
        } else {
            badge.textContent = 'STOPPED'; badge.className = 'badge badge-stopped';
            btnStart.disabled = false; btnStop.disabled = true;
        }
        document.getElementById('strategy-select').value = data.strategy;
        renderPositions(data.positions || []);
    } catch (_) {}
}

// ── Portfolio ──
async function refreshPortfolio() {
    try {
        const data = await fetchJson('/api/portfolio');
        if (!data) return;
        document.getElementById('krw-balance').textContent = formatKRW(data.krw_balance);
        document.getElementById('total-eval').textContent = formatKRW(data.total_eval);
        renderHoldings(data.holdings || []);
    } catch (e) {
        document.getElementById('krw-balance').textContent = '-';
        document.getElementById('total-eval').textContent = '-';
        document.getElementById('holdings-body').innerHTML =
            '<tr><td colspan="6" class="empty-state">Set Upbit API keys to view portfolio</td></tr>';
    }
}

function renderHoldings(holdings) {
    const tbody = document.getElementById('holdings-body');
    if (!holdings.length) {
        tbody.innerHTML = '<tr><td colspan="6" class="empty-state">No holdings</td></tr>';
        return;
    }
    tbody.innerHTML = holdings.map(h => {
        const pnlClass = h.pnl_percent > 0 ? 'pnl-positive' : h.pnl_percent < 0 ? 'pnl-negative' : 'pnl-zero';
        const sign = h.pnl_percent >= 0 ? '+' : '';
        return `<tr>
            <td><strong>${h.currency}</strong></td>
            <td>${h.balance.toFixed(8)}</td>
            <td>${formatKRW(h.avg_buy_price)}</td>
            <td>${formatKRW(h.current_price)}</td>
            <td>${formatKRW(h.eval_amount)}</td>
            <td class="${pnlClass}">${sign}${h.pnl_percent.toFixed(2)}%<br><small>${sign}${formatKRW(h.pnl_amount)}</small></td>
        </tr>`;
    }).join('');
}

// ── Trades ──
async function refreshTrades() {
    try {
        const data = await fetchJson('/api/trades?limit=50');
        if (!data) return;
        const tbody = document.getElementById('trades-body');
        if (!data.records || !data.records.length) {
            tbody.innerHTML = '<tr><td colspan="7" class="empty-state">No trades yet</td></tr>';
            return;
        }
        tbody.innerHTML = data.records.map(t => {
            const sideClass = t.side === 'BUY' ? 'side-buy' : 'side-sell';
            const pnl = t.pnl_percent != null ? formatPnl(t.pnl_percent) : '-';
            return `<tr>
                <td>${formatTime(t.created_at)}</td>
                <td>${t.ticker}</td>
                <td class="${sideClass}">${t.side}</td>
                <td>${formatKRW(t.price)}</td>
                <td>${formatKRW(t.total_amount)}</td>
                <td>${pnl}</td>
                <td>${t.reason || t.strategy || '-'}</td>
            </tr>`;
        }).join('');
    } catch (_) {}
}

// ── Positions ──
function renderPositions(positions) {
    const container = document.getElementById('positions-container');
    const active = positions.filter(p => p.position);
    if (!active.length) {
        container.innerHTML = '<p class="empty-state">No open positions</p>';
        return;
    }
    container.innerHTML = active.map(p => `
        <div class="position-card">
            <div class="position-header">
                <span class="position-ticker">${p.ticker}</span>
                <span>Avg: ${formatKRW(p.avg_buy_price)}</span>
            </div>
            <div class="position-details">
                <span>Volume: ${p.hold_volume.toFixed(8)}</span>
                <span>Today: ${p.bought_today ? 'Yes' : 'No'}</span>
            </div>
        </div>`).join('');
}

// ── Actions ──
async function startBot() {
    try {
        const data = await fetchJson('/api/bot/start', { method: 'POST', body: '{}' });
        if (data?.error) { toast(data.error, 'error'); return; }
        toast('Bot started', 'success');
        await refreshStatus();
    } catch (e) { toast('Failed to start bot', 'error'); }
}

async function stopBot() {
    try {
        await fetchJson('/api/bot/stop', { method: 'POST' });
        toast('Bot stopped', 'info');
        await refreshStatus();
    } catch (e) { toast('Failed to stop bot', 'error'); }
}

async function changeStrategy() {
    const strategy = document.getElementById('strategy-select').value;
    try {
        const data = await fetchJson('/api/bot/strategy', {
            method: 'POST', body: JSON.stringify({ strategy }),
        });
        if (data?.status === 'changed') toast(`Strategy: ${strategy}`, 'success');
        else toast(data?.message || 'Failed', 'error');
    } catch (e) { toast('Failed to change strategy', 'error'); }
}

async function saveKeys() {
    const accessKey = document.getElementById('input-access-key').value.trim();
    const secretKey = document.getElementById('input-secret-key').value.trim();
    if (!accessKey || !secretKey) { toast('Fill in both keys', 'error'); return; }
    try {
        await fetchJson('/api/user/keys', {
            method: 'POST',
            body: JSON.stringify({ access_key: accessKey, secret_key: secretKey }),
        });
        toast('API keys saved', 'success');
        document.getElementById('keys-banner').style.display = 'none';
        refreshPortfolio();
    } catch (e) { toast('Failed to save keys', 'error'); }
}

// ── ML ──
async function trainMl() {
    const el = document.getElementById('ml-result');
    el.innerHTML = '<em>Training model... (this may take a few seconds)</em>';
    try {
        const data = await fetchJson('/api/ml/train', {
            method: 'POST',
            body: JSON.stringify({
                ticker: document.getElementById('ml-ticker').value,
                days: parseInt(document.getElementById('ml-days').value),
                target_pct: parseFloat(document.getElementById('ml-target').value),
                horizon: parseInt(document.getElementById('ml-horizon').value),
            }),
        });
        if (!data) return;
        if (data.success && data.metrics) {
            const m = data.metrics;
            const features = (m.top_features || []).map(f => `${f.name}: ${f.importance}`).join(', ');
            el.innerHTML = `
                <div style="display:grid; grid-template-columns:repeat(auto-fit,minmax(120px,1fr)); gap:0.5rem; margin-bottom:0.75rem;">
                    <div class="account-item"><span class="label">Accuracy</span><span class="value" style="font-size:1rem;">${m.accuracy}</span></div>
                    <div class="account-item"><span class="label">Precision</span><span class="value" style="font-size:1rem;">${m.precision}</span></div>
                    <div class="account-item"><span class="label">Recall</span><span class="value" style="font-size:1rem;">${m.recall}</span></div>
                    <div class="account-item"><span class="label">Data</span><span class="value" style="font-size:1rem;">${m.train_size}/${m.test_size}</span></div>
                    <div class="account-item"><span class="label">Buy Signal Rate</span><span class="value" style="font-size:1rem;">${m.positive_rate}</span></div>
                </div>
                <div style="font-size:0.8rem;color:var(--text-muted);">Top features: ${features}</div>
                <div style="margin-top:0.5rem;color:var(--green);font-size:0.85rem;">Model ready — select "ML Model" strategy to use it.</div>`;
            toast('Model trained successfully', 'success');
        } else {
            el.innerHTML = `<span style="color:var(--red);">Training failed: ${data.error || 'Unknown error'}</span>`;
        }
    } catch (e) {
        el.innerHTML = '<span style="color:var(--red);">Training failed. Check API keys.</span>';
        toast('ML training failed', 'error');
    }
}

async function predictMl() {
    const ticker = document.getElementById('ml-ticker').value;
    const el = document.getElementById('ml-result');
    try {
        const data = await fetchJson(`/api/ml/predict?ticker=${ticker}`);
        if (!data) return;
        const sigClass = data.signal === 'BUY' ? 'pnl-positive' : 'pnl-zero';
        el.innerHTML = `
            <div style="font-size:1.1rem;margin-bottom:0.5rem;">
                Signal: <strong class="${sigClass}">${data.signal}</strong>
                (confidence: ${data.confidence})
            </div>`;
        toast(`${ticker}: ${data.signal} (${data.confidence})`, data.signal === 'BUY' ? 'success' : 'info');
    } catch (e) {
        toast('Prediction failed — train model first', 'error');
    }
}

// ── Backtest ──
async function runBacktest() {
    const tbody = document.getElementById('backtest-body');
    tbody.innerHTML = '<tr><td colspan="9" class="empty-state">Running backtest...</td></tr>';
    document.getElementById('bh-baseline').style.display = 'none';
    try {
        const ticker = document.getElementById('bt-ticker').value;
        const days = parseInt(document.getElementById('bt-days').value);
        const tp = parseFloat(document.getElementById('bt-tp').value);
        const sl = parseFloat(document.getElementById('bt-sl').value);
        const trail = parseFloat(document.getElementById('bt-trail').value);
        const hold = parseInt(document.getElementById('bt-hold').value);
        const mf = document.getElementById('bt-mf').checked;

        const data = await fetchJson('/api/strategies/backtest', {
            method: 'POST',
            body: JSON.stringify({
                ticker, days,
                take_profit_pct: tp,
                max_loss_pct: sl,
                trailing_stop_pct: trail,
                max_hold_days: hold,
                use_market_filter: mf,
            }),
        });
        if (!data) return;
        const results = data.results || [data];
        if (!results.length) {
            tbody.innerHTML = '<tr><td colspan="9" class="empty-state">No results</td></tr>';
            return;
        }

        // Show Buy & Hold baseline
        const bh = results[0].buy_and_hold_pct;
        const bhEl = document.getElementById('bh-baseline');
        const bhClass = bh >= 0 ? 'pnl-positive' : 'pnl-negative';
        bhEl.innerHTML = `Baseline <strong>Buy & Hold</strong>: <span class="${bhClass}">${bh >= 0 ? '+' : ''}${bh.toFixed(2)}%</span> — strategies must beat this to be worthwhile`;
        bhEl.style.display = '';

        results.sort((a, b) => b.total_return_pct - a.total_return_pct);
        tbody.innerHTML = results.map((r, i) => {
            const retClass = r.total_return_pct > 0 ? 'pnl-positive' : r.total_return_pct < 0 ? 'pnl-negative' : 'pnl-zero';
            const beatsBH = r.total_return_pct > bh;
            const rowStyle = i === 0 ? ' style="background:rgba(34,197,94,0.08)"' : '';
            const bhBadge = beatsBH ? ' <small style="color:var(--green)">BH</small>' : '';
            return `<tr${rowStyle}>
                <td><strong>${r.strategy_name}</strong>${bhBadge}</td>
                <td>${r.total_trades}</td>
                <td>${r.win_rate.toFixed(1)}%</td>
                <td class="${retClass}"><strong>${r.total_return_pct >= 0 ? '+' : ''}${r.total_return_pct.toFixed(2)}%</strong></td>
                <td>${r.avg_return_pct >= 0 ? '+' : ''}${r.avg_return_pct.toFixed(2)}%</td>
                <td class="pnl-negative">${r.max_drawdown_pct.toFixed(2)}%</td>
                <td>${r.sharpe_ratio.toFixed(2)}</td>
                <td>${r.profit_factor.toFixed(2)}</td>
                <td>${r.avg_hold_days.toFixed(1)}d</td>
            </tr>`;
        }).join('');
        toast(`Backtest: ${results.length} strategies vs B&H ${bh >= 0 ? '+' : ''}${bh.toFixed(1)}%`, 'success');
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="9" class="empty-state">Backtest failed. Check API keys.</td></tr>';
        toast('Backtest failed', 'error');
    }
}

// ── Formatting ──
function formatKRW(v) {
    if (v == null || isNaN(v)) return '-';
    const n = Number(v);
    return n >= 1000000
        ? n.toLocaleString('ko-KR', { maximumFractionDigits: 0 }) + ' KRW'
        : n.toLocaleString('ko-KR', { maximumFractionDigits: 2 }) + ' KRW';
}

function formatPnl(pnl) {
    if (pnl == null) return '-';
    const cls = pnl > 0 ? 'pnl-positive' : pnl < 0 ? 'pnl-negative' : 'pnl-zero';
    return `<span class="${cls}">${pnl >= 0 ? '+' : ''}${pnl.toFixed(2)}%</span>`;
}

function formatTime(ts) {
    if (!ts) return '-';
    const d = new Date(ts);
    if (isNaN(d.getTime())) return ts.substring(0, 16).replace('T', ' ');
    return d.toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function updateClock() {
    document.getElementById('clock').textContent = new Date().toLocaleString('ko-KR', {
        timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    }) + ' KST';
}

function toast(message, type = 'info') {
    const el = document.getElementById('toast');
    el.textContent = message; el.className = `toast ${type} show`;
    setTimeout(() => { el.className = 'toast'; }, 3000);
}
