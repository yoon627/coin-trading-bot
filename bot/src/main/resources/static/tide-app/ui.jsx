// Tide — Shared UI primitives for the live app
// Logo, Icon, Button, Card, Badge, Sidebar, TopBar, charts.

function TideLogo({ size = 22, withText = true, color = 'var(--tide-primary)' }) {
  return (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
        <path d="M2 14 Q 5 10, 8 14 T 14 14 T 20 14 T 23 14" stroke={color} strokeWidth="2.4" strokeLinecap="round" fill="none"/>
        <path d="M2 18 Q 5 15.5, 8 18 T 14 18 T 20 18 T 23 18" stroke={color} strokeWidth="2.4" strokeLinecap="round" fill="none" opacity="0.45"/>
      </svg>
      {withText && <span style={{ fontWeight: 700, fontSize: 16, letterSpacing: '-0.02em', color: 'var(--ink-900)' }}>Tide</span>}
    </div>
  );
}

const Icon = ({ d, size = 20, stroke = 'currentColor' }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    {Array.isArray(d) ? d.map((p, i) => <path key={i} d={p}/>) : <path d={d}/>}
  </svg>
);

const Icons = {
  dashboard: 'M3 13 L 12 4 L 21 13 M5 11 V 20 H 19 V 11',
  bot: ['M12 2 V 5','M5 8 H 19 V 18 H 5 Z','M9 13 H 9.01','M15 13 H 15.01','M9 17 H 15'],
  chart: 'M3 20 H 21 M6 16 V 10 M11 16 V 6 M16 16 V 12 M21 16 V 8',
  orders: ['M4 6 H 20','M4 12 H 20','M4 18 H 14'],
  backtest: ['M3 3 H 21 V 21 H 3 Z','M3 9 H 21','M9 9 V 21'],
  bell: ['M6 8 A 6 6 0 0 1 18 8 V 13 L 20 16 H 4 L 6 13 Z','M10 19 A 2 2 0 0 0 14 19'],
  wallet: ['M3 7 H 19 V 19 H 3 Z','M3 7 V 5 A 2 2 0 0 1 5 3 H 17','M16 13 H 19'],
  settings: ['M12 8 A 4 4 0 1 0 12 16 A 4 4 0 1 0 12 8','M19 12 H 22','M2 12 H 5','M12 2 V 5','M12 19 V 22'],
  search: ['M11 4 A 7 7 0 1 1 11 18 A 7 7 0 1 1 11 4','M16 16 L 21 21'],
  plus: ['M12 5 V 19','M5 12 H 19'],
  arrowUp: ['M12 19 V 5','M6 11 L 12 5 L 18 11'],
  arrowDown: ['M12 5 V 19','M6 13 L 12 19 L 18 13'],
  play: 'M7 5 L 19 12 L 7 19 Z',
  pause: ['M8 5 V 19','M16 5 V 19'],
  refresh: ['M21 12 A 9 9 0 1 1 12 3','M21 3 V 9 H 15'],
  logout: ['M9 21 H 5 V 3 H 9','M16 17 L 21 12 L 16 7','M21 12 H 9'],
};

function Button({ children, variant = 'primary', size = 'md', icon, full, onClick, style, disabled, type }) {
  const sizes = { sm: { h: 32, px: 12, fs: 13, r: 8 }, md: { h: 40, px: 16, fs: 14, r: 10 }, lg: { h: 52, px: 20, fs: 16, r: 12 } }[size];
  const variants = {
    primary: { bg: 'var(--tide-primary)', color: '#fff', border: 'transparent' },
    dark: { bg: 'var(--ink-900)', color: '#fff', border: 'transparent' },
    ghost: { bg: 'var(--ink-100)', color: 'var(--ink-900)', border: 'transparent' },
    soft: { bg: 'var(--tide-primary-soft)', color: 'var(--tide-primary-ink)', border: 'transparent' },
    outline: { bg: '#fff', color: 'var(--ink-900)', border: 'var(--ink-200)' },
    danger: { bg: 'var(--up-soft)', color: 'var(--up)', border: 'transparent' },
  }[variant];
  return (
    <button type={type || 'button'} onClick={onClick} disabled={disabled} style={{
      height: sizes.h, padding: `0 ${sizes.px}px`, fontSize: sizes.fs, fontWeight: 600,
      borderRadius: sizes.r, background: variants.bg, color: variants.color,
      border: `1px solid ${variants.border}`, cursor: disabled ? 'not-allowed' : 'pointer',
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 6,
      width: full ? '100%' : 'auto', opacity: disabled ? 0.55 : 1,
      fontFamily: 'inherit', transition: 'transform .08s, filter .12s', letterSpacing: '-0.01em', ...style,
    }}>
      {icon && <Icon d={Icons[icon]} size={sizes.fs + 2}/>}
      {children}
    </button>
  );
}

function Card({ children, padding = 20, style, onClick }) {
  return <div onClick={onClick} style={{
    background: '#fff', border: '1px solid var(--ink-200)', borderRadius: 'var(--r-lg)',
    padding, cursor: onClick ? 'pointer' : 'default', ...style,
  }}>{children}</div>;
}

function Badge({ children, tone = 'neutral', dot, style }) {
  const tones = {
    neutral: { bg: 'var(--ink-100)', color: 'var(--ink-700)' },
    primary: { bg: 'var(--tide-primary-soft)', color: 'var(--tide-primary-ink)' },
    up: { bg: 'var(--up-soft)', color: 'var(--up)' },
    down: { bg: 'var(--down-soft)', color: 'var(--down)' },
    warn: { bg: 'var(--warn-soft)', color: '#A35E00' },
    live: { bg: '#ECFDF5', color: '#047857' },
  }[tone];
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4, height: 22, padding: '0 8px', borderRadius: 999,
      background: tones.bg, color: tones.color, fontSize: 11.5, fontWeight: 600, letterSpacing: '-0.01em', ...style,
    }}>
      {dot && <span style={{ width: 6, height: 6, borderRadius: 999, background: tones.color }}/>}
      {children}
    </span>
  );
}

function Sidebar({ active, onSelect, user, onLogout }) {
  const items = [
    { id: 'dashboard', label: '대시보드', icon: 'dashboard' },
    { id: 'bot', label: '봇 / 전략', icon: 'bot' },
    { id: 'trade', label: '차트 매매', icon: 'chart' },
    { id: 'orders', label: '주문·내역', icon: 'orders' },
    { id: 'backtest', label: '백테스팅', icon: 'backtest' },
    { id: 'wallet', label: '지갑', icon: 'wallet' },
    { id: 'settings', label: '설정', icon: 'settings' },
  ];
  return (
    <aside style={{ width: 220, flexShrink: 0, borderRight: '1px solid var(--ink-200)', background: '#fff', padding: '20px 12px', display: 'flex', flexDirection: 'column', gap: 4, height: '100vh', position: 'sticky', top: 0 }}>
      <div style={{ padding: '4px 8px 18px 8px' }}><TideLogo/></div>
      {items.map(item => (
        <button key={item.id} onClick={() => onSelect(item.id)} style={{
          display: 'flex', alignItems: 'center', gap: 12, height: 40, padding: '0 12px',
          background: active === item.id ? 'var(--ink-100)' : 'transparent',
          color: active === item.id ? 'var(--ink-900)' : 'var(--ink-600)',
          border: 'none', borderRadius: 10, cursor: 'pointer',
          fontSize: 14, fontWeight: active === item.id ? 600 : 500, fontFamily: 'inherit',
        }}>
          <Icon d={Icons[item.icon]} size={18}/>
          <span>{item.label}</span>
        </button>
      ))}
      <div style={{ marginTop: 'auto', padding: 12, background: 'var(--ink-50)', borderRadius: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
          <div style={{ width: 32, height: 32, borderRadius: 999, background: 'linear-gradient(135deg,#00A6B6,#2D6FF7)', display:'flex',alignItems:'center',justifyContent:'center',color:'#fff',fontWeight:700,fontSize:13 }}>{(user?.username || '?')[0].toUpperCase()}</div>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--ink-900)' }}>{user?.username || '...'}</div>
            <div style={{ fontSize: 11, color: 'var(--ink-500)' }}>{user?.has_upbit_keys ? 'Upbit 연결됨' : 'API 키 필요'}</div>
          </div>
        </div>
        <button onClick={onLogout} style={{ width: '100%', height: 30, border: '1px solid var(--ink-200)', background: '#fff', borderRadius: 8, fontSize: 12, fontWeight: 600, cursor: 'pointer', color: 'var(--ink-600)', fontFamily: 'inherit', display:'flex',alignItems:'center',justifyContent:'center',gap:6 }}>
          <Icon d={Icons.logout} size={13}/>로그아웃
        </button>
      </div>
    </aside>
  );
}

function TopBar({ title, subtitle, actions }) {
  return (
    <div style={{ height: 64, padding: '0 28px', borderBottom: '1px solid var(--ink-200)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', background: '#fff' }}>
      <div>
        <div style={{ fontSize: 18, fontWeight: 700, letterSpacing: '-0.02em' }}>{title}</div>
        {subtitle && <div style={{ fontSize: 12, color: 'var(--ink-500)', marginTop: 1 }}>{subtitle}</div>}
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>{actions}</div>
    </div>
  );
}

function MiniChart({ data, color = 'var(--tide-primary)', width = 120, height = 40, fill = true }) {
  if (!data || data.length < 2) return <div style={{ width, height, background: 'var(--ink-50)', borderRadius: 4 }}/>;
  const min = Math.min(...data), max = Math.max(...data);
  const range = max - min || 1;
  const stepX = width / (data.length - 1);
  const pts = data.map((v, i) => [i * stepX, height - ((v - min) / range) * height * 0.85 - height * 0.075]);
  const path = pts.map((p, i) => (i === 0 ? 'M' : 'L') + p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' ');
  const id = 'g' + Math.random().toString(36).slice(2, 8);
  return (
    <svg width={width} height={height} style={{ display: 'block' }}>
      {fill && (<>
        <defs><linearGradient id={id} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={color} stopOpacity="0.18"/><stop offset="100%" stopColor={color} stopOpacity="0"/></linearGradient></defs>
        <path d={path + ` L ${width} ${height} L 0 ${height} Z`} fill={`url(#${id})`}/>
      </>)}
      <path d={path} stroke={color} strokeWidth="1.6" fill="none" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  );
}

function Empty({ icon = 'wallet', title, message, action }) {
  return (
    <div style={{ padding: '60px 20px', textAlign: 'center', color: 'var(--ink-500)' }}>
      <div style={{ width: 56, height: 56, borderRadius: 999, background: 'var(--ink-100)', margin: '0 auto 16px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Icon d={Icons[icon]} size={24} stroke="var(--ink-400)"/>
      </div>
      <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--ink-700)' }}>{title}</div>
      {message && <div style={{ fontSize: 13, marginTop: 6 }}>{message}</div>}
      {action && <div style={{ marginTop: 16 }}>{action}</div>}
    </div>
  );
}

function Toast({ message, tone = 'primary', onClose }) {
  React.useEffect(() => {
    if (!message) return;
    const t = setTimeout(onClose, 3000);
    return () => clearTimeout(t);
  }, [message, onClose]);
  if (!message) return null;
  const tones = { primary: 'var(--ink-900)', up: 'var(--up)', down: 'var(--down)', warn: '#A35E00' };
  return (
    <div style={{
      position: 'fixed', bottom: 24, left: '50%', transform: 'translateX(-50%)',
      background: tones[tone], color: '#fff', padding: '12px 20px', borderRadius: 10,
      fontSize: 13, fontWeight: 600, boxShadow: 'var(--shadow-lg)', zIndex: 1000,
    }}>{message}</div>
  );
}

window.TideLogo = TideLogo;
window.Icon = Icon; window.Icons = Icons;
window.Button = Button; window.Card = Card; window.Badge = Badge;
window.Sidebar = Sidebar; window.TopBar = TopBar;
window.MiniChart = MiniChart; window.Empty = Empty; window.Toast = Toast;
