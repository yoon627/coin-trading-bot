-- 적립 프로파일 사다리 장부. position/avg_buy_price/hold_volume 은 여전히 거래소에서 복원하고(V14 설계),
-- 여기는 분할 단위(rungs)·기준가·무포지션 고점·주문 시점 트리거가만 둔다.
-- 컬럼 추가만 — 되돌릴 때는 DROP 이 아니라 TRADING_ACCUMULATE_TICKERS 를 비워 프로파일을 끈다(forward-off).
ALTER TABLE trading_states
    ADD COLUMN rungs_filled INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_action_price DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN flat_peak DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN pending_buy_trigger_price DOUBLE PRECISION,
    ADD COLUMN pending_buy_prior_volume DOUBLE PRECISION,
    ADD COLUMN pending_sell_trigger_price DOUBLE PRECISION;
