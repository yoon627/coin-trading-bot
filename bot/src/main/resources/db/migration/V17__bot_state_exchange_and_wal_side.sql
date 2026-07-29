-- (1) bot_state 를 거래소별로 — 한 유저가 Upbit + KIS 봇을 동시에 돌릴 수 있게(plan D22).
-- 기존 user_id UNIQUE(인라인, Postgres 자동명 bot_state_user_id_key) 제거 후 (user_id, exchange) UNIQUE.
ALTER TABLE bot_state ADD COLUMN exchange VARCHAR(10) NOT NULL DEFAULT 'UPBIT';
ALTER TABLE bot_state DROP CONSTRAINT bot_state_user_id_key;
CREATE UNIQUE INDEX uq_bot_state_user_exchange ON bot_state(user_id, exchange);

-- (2) WAL 활성 불변식에 side 추가(plan D19/C2) — 미체결 매수(PLACED)가 같은 종목 손절매도를 막지 않도록.
--     매수 1 + 매도 1 동시 활성 허용. 같은 side 중복만 차단.
DROP INDEX uq_stock_order_intent_active;
CREATE UNIQUE INDEX uq_stock_order_intent_active
    ON stock_order_intent(user_id, exchange, account_no, symbol, side)
    WHERE status IN ('SUBMITTING', 'PLACED', 'PARTIAL', 'UNKNOWN', 'NEEDS_REVIEW');
