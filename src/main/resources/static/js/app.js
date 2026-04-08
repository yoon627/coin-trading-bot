const API = '';
let refreshInterval;

// ── Init ──
document.addEventListener('DOMContentLoaded', () => {
    updateClock();
    setInterval(updateClock, 1000);
    refreshAll();
    refreshInterval = setInterval(refreshAll, 5000);
});

// ── API Calls ──
async function fetchJson(url, options) {
    const res = await fetch(API + url, options);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
}

async function refreshAll() {
    await Promise.allSettled([
        refreshStatus(),
        refreshAccount(),
        refreshTrades(),
    ]);
    document.getElementById('last-updated').textContent =
        'Updated: ' + new Date().toLocaleTimeString('ko-KR');
}

// ── Bot Status ──
async function refreshStatus() {
    try {
        const data = await fetchJson('/api/bot/status');
        const badge = document.getElementById('status-badge');
        const btnStart = document.getElementById('btn-start');
        const btnStop = document.getElementById('btn-stop');
        const strategySelect = document.getElementById('strategy-select');

        if (data.running) {
            badge.textContent = 'RUNNING';
            badge.className = 'badge badge-running';
            btnStart.disabled = true;
            btnStop.disabled = false;
        } else {
            badge.textContent = 'STOPPED';
            badge.className = 'badge badge-stopped';
            btnStart.disabled = false;
            btnStop.disabled = true;
        }

        strategySelect.value = data.strategy;
        renderPositions(data.positions || []);
    } catch (e) {
        console.error('Status fetch failed:', e);
    }
}

// ── Account ──
async function refreshAccount() {
    try {
        const accounts = await fetchJson('/api/account');
        const krw = accounts.find(a => a.currency === 'KRW');
        const krwBalance = krw ? parseFloat(krw.balance) : 0;

        let totalAssets = krwBalance;
        accounts.forEach(a => {
            if (a.currency !== 'KRW' && a.unit_currency === 'KRW') {
                totalAssets += parseFloat(a.balance) * parseFloat(a.avg_buy_price);
            }
        });

        document.getElementById('krw-balance').textContent = formatKRW(krwBalance);
        document.getElementById('total-assets').textContent = formatKRW(totalAssets);
    } catch (e) {
        document.getElementById('krw-balance').textContent = '-';
        document.getElementById('total-assets').textContent = '-';
    }
}

// ── Trades ──
async function refreshTrades() {
    try {
        const data = await fetchJson('/api/trades?limit=50');
        const tbody = document.getElementById('trades-body');

        if (!data.records || data.records.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="empty-state">No trades yet</td></tr>';
            return;
        }

        tbody.innerHTML = data.records.map(t => {
            const sideClass = t.side === 'BUY' ? 'side-buy' : 'side-sell';
            const pnl = t.pnl_percent != null ? formatPnl(t.pnl_percent) : '-';
            const time = formatTime(t.created_at);
            return `<tr>
                <td>${time}</td>
                <td>${t.ticker}</td>
                <td class="${sideClass}">${t.side}</td>
                <td>${formatKRW(t.price)}</td>
                <td>${formatKRW(t.total_amount)}</td>
                <td>${pnl}</td>
                <td>${t.reason || t.strategy || '-'}</td>
            </tr>`;
        }).join('');
    } catch (e) {
        console.error('Trades fetch failed:', e);
    }
}

// ── Positions ──
function renderPositions(positions) {
    const container = document.getElementById('positions-container');

    const active = positions.filter(p => p.position);
    if (active.length === 0) {
        container.innerHTML = '<p class="empty-state">No open positions</p>';
        return;
    }

    container.innerHTML = active.map(p => {
        const pnlClass = p.avgBuyPrice > 0 ? 'pnl-zero' : 'pnl-zero';
        return `<div class="position-card">
            <div class="position-header">
                <span class="position-ticker">${p.ticker}</span>
                <span class="position-pnl ${pnlClass}">
                    Avg: ${formatKRW(p.avgBuyPrice)}
                </span>
            </div>
            <div class="position-details">
                <span>Volume: ${p.holdVolume.toFixed(8)}</span>
                <span>Today: ${p.boughtToday ? 'Yes' : 'No'}</span>
            </div>
        </div>`;
    }).join('');
}

// ── Actions ──
async function startBot() {
    try {
        await fetchJson('/api/bot/start', { method: 'POST' });
        toast('Bot started', 'success');
        await refreshStatus();
    } catch (e) {
        toast('Failed to start bot', 'error');
    }
}

async function stopBot() {
    try {
        await fetchJson('/api/bot/stop', { method: 'POST' });
        toast('Bot stopped', 'info');
        await refreshStatus();
    } catch (e) {
        toast('Failed to stop bot', 'error');
    }
}

async function changeStrategy() {
    const strategy = document.getElementById('strategy-select').value;
    try {
        const data = await fetchJson('/api/bot/strategy', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ strategy }),
        });
        if (data.status === 'changed') {
            toast(`Strategy: ${strategy}`, 'success');
        } else {
            toast(data.message || 'Failed', 'error');
        }
    } catch (e) {
        toast('Failed to change strategy', 'error');
    }
}

// ── Formatting ──
function formatKRW(value) {
    if (value == null || isNaN(value)) return '-';
    const num = Number(value);
    if (num >= 1_000_000) {
        return num.toLocaleString('ko-KR', { maximumFractionDigits: 0 }) + ' KRW';
    }
    return num.toLocaleString('ko-KR', { maximumFractionDigits: 2 }) + ' KRW';
}

function formatPnl(pnl) {
    if (pnl == null) return '-';
    const sign = pnl >= 0 ? '+' : '';
    const cls = pnl > 0 ? 'pnl-positive' : pnl < 0 ? 'pnl-negative' : 'pnl-zero';
    return `<span class="${cls}">${sign}${pnl.toFixed(2)}%</span>`;
}

function formatTime(ts) {
    if (!ts) return '-';
    const d = new Date(ts);
    if (isNaN(d.getTime())) return ts.substring(0, 16).replace('T', ' ');
    return d.toLocaleString('ko-KR', {
        month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit',
    });
}

function updateClock() {
    const now = new Date();
    document.getElementById('clock').textContent =
        now.toLocaleString('ko-KR', {
            timeZone: 'Asia/Seoul',
            month: '2-digit', day: '2-digit',
            hour: '2-digit', minute: '2-digit', second: '2-digit',
        }) + ' KST';
}

// ── Toast ──
function toast(message, type = 'info') {
    const el = document.getElementById('toast');
    el.textContent = message;
    el.className = `toast ${type} show`;
    setTimeout(() => { el.className = 'toast'; }, 3000);
}
