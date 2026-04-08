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
        if (!me) return;
        if (!me.has_upbit_keys) {
            document.getElementById('keys-banner').style.display = '';
        }
        document.getElementById('chk-public-profile').checked = me.public_profile || false;
        document.getElementById('chk-public-strategy').checked = me.public_strategy || false;
    } catch (_) {}
}

async function refreshAll() {
    await Promise.allSettled([refreshStatus(), refreshPortfolio(), refreshTrades(), refreshLeaderboard()]);
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

// ── Leaderboard ──
async function refreshLeaderboard() {
    try {
        const data = await fetch('/api/leaderboard').then(r => r.json());
        const tbody = document.getElementById('leaderboard-body');
        const rankings = data.rankings || [];
        if (!rankings.length) {
            tbody.innerHTML = '<tr><td colspan="8" class="empty-state">No public users yet</td></tr>';
            return;
        }
        tbody.innerHTML = rankings.map((r, i) => {
            const pnlClass = r.total_pnl_pct > 0 ? 'pnl-positive' : r.total_pnl_pct < 0 ? 'pnl-negative' : 'pnl-zero';
            const rank = i === 0 ? '<strong style="color:var(--yellow);">1</strong>' : i + 1;
            const statusBadge = r.bot_running
                ? '<span class="badge badge-running" style="font-size:0.65rem;">ON</span>'
                : '<span class="badge badge-stopped" style="font-size:0.65rem;">OFF</span>';
            return `<tr>
                <td>${rank}</td>
                <td><strong>${r.username}</strong></td>
                <td>${r.total_trades}</td>
                <td>${r.win_rate.toFixed(1)}%</td>
                <td class="${pnlClass}"><strong>${r.total_pnl_pct >= 0 ? '+' : ''}${r.total_pnl_pct.toFixed(2)}%</strong></td>
                <td>${r.avg_pnl_pct >= 0 ? '+' : ''}${r.avg_pnl_pct.toFixed(2)}%</td>
                <td>${r.strategy}</td>
                <td>${statusBadge}</td>
            </tr>`;
        }).join('');
    } catch (_) {
        document.getElementById('leaderboard-body').innerHTML =
            '<tr><td colspan="8" class="empty-state">Failed to load leaderboard</td></tr>';
    }
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

async function saveSettings() {
    try {
        const data = await fetchJson('/api/user/settings', {
            method: 'POST',
            body: JSON.stringify({
                public_profile: document.getElementById('chk-public-profile').checked,
                public_strategy: document.getElementById('chk-public-strategy').checked,
            }),
        });
        if (data) toast('Settings saved', 'success');
    } catch (e) { toast('Failed to save settings', 'error'); }
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
