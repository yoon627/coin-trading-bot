// ── Auth ──
function authHeaders() {
    return { 'Content-Type': 'application/json' };
}

async function fetchJson(url, options = {}) {
    options.headers = { ...authHeaders(), ...options.headers };
    const res = await fetch(url, options);
    if (res.status === 401) { logout(); return null; }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
}

function logout() {
    localStorage.removeItem('username');
    fetch('/api/auth/logout', { method: 'POST' }).finally(() => {
        window.location.href = '/login.html';
    });
}

// ── Init ──
document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('user-info').textContent = localStorage.getItem('username') || '';
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
        const leaderboardNote = document.getElementById('leaderboard-note');
        if (!me.public_profile) {
            leaderboardNote.textContent = '현재 계정은 Public Profile이 꺼져 있어서 Leaderboard에 표시되지 않습니다. Settings에서 켜세요.';
            leaderboardNote.style.display = '';
        } else {
            leaderboardNote.textContent = '현재 계정은 Leaderboard 표시 대상입니다.';
            leaderboardNote.style.display = '';
        }
    } catch (_) {}
}

async function refreshAll() {
    await Promise.allSettled([refreshStatus(), refreshPortfolio(), refreshWatchlist(), refreshTrades(), refreshLeaderboard()]);
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
            badge.textContent = 'Bot Running'; badge.className = 'badge badge-running';
            btnStart.disabled = true; btnStop.disabled = false;
        } else {
            badge.textContent = 'Bot Stopped'; badge.className = 'badge badge-stopped';
            btnStart.disabled = false; btnStop.disabled = true;
        }
        document.getElementById('strategy-select').value = data.strategy;
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
        tbody.innerHTML = '<tr><td colspan="7" class="empty-state">No holdings</td></tr>';
        return;
    }
    tbody.innerHTML = holdings.map(h => {
        const pnlClass = h.pnl_percent > 0 ? 'pnl-positive' : h.pnl_percent < 0 ? 'pnl-negative' : 'pnl-zero';
        const sign = h.pnl_percent >= 0 ? '+' : '';
        const market = escapeHtml(h.market);
        return `<tr>
            <td><strong>${escapeHtml(h.currency)}</strong></td>
            <td>${h.balance.toFixed(8)}</td>
            <td>${formatKRW(h.avg_buy_price)}</td>
            <td>${formatKRW(h.current_price)}</td>
            <td>${formatKRW(h.eval_amount)}</td>
            <td class="${pnlClass}">${sign}${h.pnl_percent.toFixed(2)}%<br><small>${sign}${formatKRW(h.pnl_amount)}</small></td>
            <td><button class="btn btn-stop btn-sm-action" onclick="sellHolding('${market}')">Sell All</button></td>
        </tr>`;
    }).join('');
}

// ── Watchlist ──
async function refreshWatchlist() {
    try {
        const data = await fetchJson('/api/watchlist');
        if (!data) return;
        const tbody = document.getElementById('watchlist-body');
        const coins = data.coins || [];
        if (!coins.length) {
            tbody.innerHTML = '<tr><td colspan="6" class="empty-state">No data yet</td></tr>';
            return;
        }
        tbody.innerHTML = coins.map(c => {
            const ch24 = c.change_24h;
            const ch1h = c.change_1h;
            const cls24 = ch24 > 0 ? 'pnl-positive' : ch24 < 0 ? 'pnl-negative' : 'pnl-zero';
            const cls1h = ch1h != null ? (ch1h > 0 ? 'pnl-positive' : ch1h < 0 ? 'pnl-negative' : 'pnl-zero') : '';
            const vol = c.volume_24h >= 1e12 ? (c.volume_24h / 1e12).toFixed(1) + 'T'
                      : c.volume_24h >= 1e8 ? (c.volume_24h / 1e8).toFixed(1) + 'B'
                      : c.volume_24h >= 1e4 ? (c.volume_24h / 1e4).toFixed(0) + 'M' : formatKRW(c.volume_24h);
            return `<tr>
                <td><strong>${escapeHtml(c.currency)}</strong></td>
                <td>${formatKRW(c.price)}</td>
                <td class="${cls1h}">${ch1h != null ? (ch1h >= 0 ? '+' : '') + ch1h.toFixed(2) + '%' : '-'}</td>
                <td class="${cls24}">${ch24 >= 0 ? '+' : ''}${ch24.toFixed(2)}%</td>
                <td><small>${formatKRW(c.high_price)} / ${formatKRW(c.low_price)}</small></td>
                <td>${vol}</td>
            </tr>`;
        }).join('');
    } catch (_) {
        document.getElementById('watchlist-body').innerHTML =
            '<tr><td colspan="6" class="empty-state">Failed to load watchlist</td></tr>';
    }
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
                <td><strong>${escapeHtml(r.username)}</strong></td>
                <td>${r.total_trades}</td>
                <td>${r.win_rate.toFixed(1)}%</td>
                <td class="${pnlClass}"><strong>${r.total_pnl_pct >= 0 ? '+' : ''}${r.total_pnl_pct.toFixed(2)}%</strong></td>
                <td>${r.avg_pnl_pct >= 0 ? '+' : ''}${r.avg_pnl_pct.toFixed(2)}%</td>
                <td>${escapeHtml(r.strategy)}</td>
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
                <td>${escapeHtml(t.ticker)}</td>
                <td class="${sideClass}">${t.side}</td>
                <td>${formatKRW(t.price)}</td>
                <td>${formatKRW(t.total_amount)}</td>
                <td>${pnl}</td>
                <td>${escapeHtml(t.reason || t.strategy || '-')}</td>
            </tr>`;
        }).join('');
    } catch (_) {}
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

async function sellHolding(market) {
    if (!market) { toast('Market을 입력하세요', 'error'); return; }
    if (!confirm(`${market} 전량 매도하시겠습니까?`)) return;
    try {
        const data = await fetchJson('/api/trade/sell', { method: 'POST', body: JSON.stringify({ market, sell_all: true }) });
        if (data?.status === 'success') { toast(`${market} 매도 완료`, 'success'); refreshAll(); }
        else toast(data?.error || 'Failed', 'error');
    } catch (e) { toast('매도 실패: ' + e.message, 'error'); }
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

function toast(message, type = 'info') {
    const el = document.getElementById('toast');
    el.textContent = message; el.className = `toast ${type} show`;
    setTimeout(() => { el.className = 'toast'; }, 3000);
}

function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, ch => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;',
    }[ch]));
}
