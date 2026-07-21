-- per-ticker 거래 상태 durable 영속화 (#19 halt 상한, #20 pending 주문 영속).
-- position/avg_buy_price/hold_volume 은 저장하지 않는다 — 재시작 시 syncPosition 이 거래소 잔고에서 복원.
CREATE TABLE trading_states (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    ticker VARCHAR(20) NOT NULL,
    pending_buy_uuid VARCHAR(100),
    pending_buy_strategy VARCHAR(50),
    pending_sell_uuid VARCHAR(100),
    pending_sell_reason VARCHAR(20),
    entry_strategy VARCHAR(50),
    buy_date DATE,
    bought_today BOOLEAN NOT NULL DEFAULT FALSE,
    peak_price DOUBLE PRECISION NOT NULL DEFAULT 0,
    exit_params_json VARCHAR(500),
    halted BOOLEAN NOT NULL DEFAULT FALSE,
    halt_reason VARCHAR(200),
    reconcile_failure_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_trading_states_user_ticker UNIQUE (user_id, ticker)
);
CREATE INDEX idx_trading_states_user ON trading_states(user_id);

-- #20 재시작 reconcile 멱등성: 같은 주문 uuid 를 재시작 후 다시 reconcile 해도 trade_executions 이중 기록 방지.
-- exchange_order_id 가 NULL 인 과거/수동 기록은 제약에서 제외(부분 unique).
CREATE UNIQUE INDEX uq_trade_executions_order_id
    ON trade_executions (user_id, exchange_order_id)
    WHERE exchange_order_id IS NOT NULL;

-- dead code 정리: positions 테이블은 프로덕션에서 사용되지 않는다(멀티거래소 시절 잔재). 역참조 FK 없음.
DROP TABLE IF EXISTS positions;
