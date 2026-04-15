CREATE TABLE price_snapshots (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    high_price DOUBLE PRECISION NOT NULL,
    low_price DOUBLE PRECISION NOT NULL,
    trade_volume DOUBLE PRECISION NOT NULL,
    acc_trade_price_24h DOUBLE PRECISION NOT NULL,
    signed_change_rate DOUBLE PRECISION NOT NULL,
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_price_snapshots_ticker ON price_snapshots(ticker);
CREATE INDEX idx_price_snapshots_captured_at ON price_snapshots(captured_at);
CREATE INDEX idx_price_snapshots_ticker_captured ON price_snapshots(ticker, captured_at);
