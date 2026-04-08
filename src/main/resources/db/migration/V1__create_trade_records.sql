CREATE TABLE trade_records (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    side VARCHAR(4) NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    volume DOUBLE PRECISION NOT NULL,
    total_amount DOUBLE PRECISION NOT NULL,
    pnl_percent DOUBLE PRECISION,
    reason VARCHAR(50),
    strategy VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_trade_records_ticker ON trade_records(ticker);
CREATE INDEX idx_trade_records_created_at ON trade_records(created_at);
