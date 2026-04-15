-- Enhanced trade executions with exchange awareness
CREATE TABLE trade_executions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    exchange VARCHAR(10) NOT NULL,
    market VARCHAR(20) NOT NULL,
    side VARCHAR(4) NOT NULL,
    order_type VARCHAR(10) NOT NULL DEFAULT 'MARKET',
    price DOUBLE PRECISION NOT NULL,
    volume DOUBLE PRECISION NOT NULL,
    total_amount DOUBLE PRECISION NOT NULL,
    fee DOUBLE PRECISION DEFAULT 0,
    pnl_percent DOUBLE PRECISION,
    pnl_amount DOUBLE PRECISION,
    reason VARCHAR(50),
    strategy VARCHAR(50),
    exchange_order_id VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'EXECUTED',
    executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_trade_executions_user ON trade_executions(user_id, executed_at DESC);
CREATE INDEX idx_trade_executions_exchange ON trade_executions(exchange, market, executed_at DESC);

-- Active positions
CREATE TABLE positions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    exchange VARCHAR(10) NOT NULL,
    market VARCHAR(20) NOT NULL,
    avg_buy_price DOUBLE PRECISION NOT NULL,
    volume DOUBLE PRECISION NOT NULL,
    peak_price DOUBLE PRECISION NOT NULL DEFAULT 0,
    strategy VARCHAR(50),
    opened_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, exchange, market)
);

-- Strategy signal logs for analysis
CREATE TABLE strategy_signals (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT,
    exchange VARCHAR(10) NOT NULL,
    market VARCHAR(20) NOT NULL,
    strategy VARCHAR(50) NOT NULL,
    signal_type VARCHAR(10) NOT NULL,
    confidence DOUBLE PRECISION,
    indicators JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_strategy_signals_lookup ON strategy_signals(exchange, market, created_at DESC);
