-- per-(user, exchange, symbol) 주식 포지션 상태의 durable 스냅샷 (#64).
--
-- 보유수량·평단은 저장하지 않는다 — 재시작 시 getHoldings(거래소 잔고)가 진실이며, durable 값과 어긋나면
-- 유령 포지션이 된다(크립토 trading_states V14 와 같은 설계).
-- 저장 대상은 "거래소가 알려주지 않는 것"뿐이다:
--   peak_price     트레일링 스탑 기준 고점 — 잃으면 진입가로 리셋돼 트레일링이 발동하지 않는다
--   bought_date    당일 1회 진입 게이트의 근거 날짜 — bought_today 플래그만으로는 재시작 후
--                  그것이 오늘 것인지 어제 것인지 구분할 수 없다
--   entry_strategy 진입 전략 — 잃으면 chartExit 기준이 "그때 활성 전략"으로 바뀐다
CREATE TABLE stock_position_state (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES users(id),
    exchange       VARCHAR(10) NOT NULL DEFAULT 'KIS',
    symbol         VARCHAR(20) NOT NULL,
    peak_price     DOUBLE PRECISION NOT NULL DEFAULT 0,
    bought_date    DATE,
    entry_strategy VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_stock_position_state ON stock_position_state(user_id, exchange, symbol);
