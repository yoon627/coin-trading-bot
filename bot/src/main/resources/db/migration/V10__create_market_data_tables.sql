-- Market ticker history (real-time price snapshots from Kafka)
CREATE TABLE market_tickers (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exchange VARCHAR(10) NOT NULL,
    market VARCHAR(20) NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    bid_price DOUBLE PRECISION,
    ask_price DOUBLE PRECISION,
    volume_24h DOUBLE PRECISION,
    quote_volume_24h DOUBLE PRECISION,
    change_rate_24h DOUBLE PRECISION,
    high_price_24h DOUBLE PRECISION,
    low_price_24h DOUBLE PRECISION,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_market_tickers_lookup ON market_tickers(exchange, market, recorded_at DESC);

-- OHLCV candle data (multi-timeframe: 1m, 5m, 15m, 1h, 4h, 1d, 1w, 1M)
CREATE TABLE market_candles (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exchange VARCHAR(10) NOT NULL,
    market VARCHAR(20) NOT NULL,
    interval_minutes INT NOT NULL,
    open_price DOUBLE PRECISION NOT NULL,
    high_price DOUBLE PRECISION NOT NULL,
    low_price DOUBLE PRECISION NOT NULL,
    close_price DOUBLE PRECISION NOT NULL,
    volume DOUBLE PRECISION NOT NULL,
    quote_volume DOUBLE PRECISION DEFAULT 0,
    open_time TIMESTAMPTZ NOT NULL,
    close_time TIMESTAMPTZ NOT NULL,
    UNIQUE(exchange, market, interval_minutes, open_time)
);
CREATE INDEX idx_market_candles_lookup ON market_candles(exchange, market, interval_minutes, open_time DESC);
