-- 주문마다 한 줄인 orders 표를 만들고 옛 체결을 옮긴다.
--
-- 지금은 같은 체결을 trade_records(화면)와 trade_executions(중복 방지·대사)에 두 번 적고, 엔진 매수 줄의 가격·수량·총액은
-- 이 주문이 아니라 체결 뒤 보유 전체(스냅숏)다. orders 는 이 주문 하나의 체결 수량·대금·평균가와 체결 뒤 장부를 따로 적는다.
-- 이 버전에서는 체결(FILLED)만 쓰고 아무도 읽지 않는다. 진행 중 주문은 여전히 trading_states 의 pending_* 칼럼이다.
--
-- 체결 기록과 같은 트랜잭션에서 쓰이므로(TradeExecutionService) 실패할 이유를 두지 않는다: 바깥에서 온 값은 길이 제한 없는 TEXT,
-- FK 없음, CHECK 는 코드의 enum 값에만 건다(V33 과 같은 이유).
-- 금액은 다른 표와 같은 DOUBLE PRECISION 이다. fee 가 NULL 이면 모른다는 뜻이다(trade_executions 의 0 과 달리 실제 0 과 구분된다).
--
-- 롤백: 새 표만 더하므로 직전 이미지는 이 표를 모른 채 그대로 뜨고 쓴다. 그동안의 체결은 orders 에 남지 않는다 —
-- 다음 단위(읽는 쪽 전환)가 아래 옮기기 부분을 다시 돌려 메운다(trade_execution_id 로 이미 옮긴 줄은 건너뛴다).

CREATE TABLE orders (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    exchange TEXT NOT NULL,
    market TEXT NOT NULL,
    side VARCHAR(4) NOT NULL,
    -- 봇이 보내기 전에 정한 식별자(ctb-…). 주문 응답에 있을 때만 남는다.
    client_order_id TEXT,
    exchange_order_id TEXT,
    status VARCHAR(12) NOT NULL,
    -- 끝날 때 거래소가 준 상태 그대로(done·cancel 등).
    exchange_state TEXT,
    requested_funds DOUBLE PRECISION,
    requested_volume DOUBLE PRECISION,
    -- 주문을 결정한 tick 가격.
    decision_price DOUBLE PRECISION,
    -- 이 주문의 체결 수량·체결 대금(수수료 제외)·평균가.
    filled_volume DOUBLE PRECISION,
    filled_funds DOUBLE PRECISION,
    avg_fill_price DOUBLE PRECISION,
    fee DOUBLE PRECISION,
    fee_basis VARCHAR(12),
    -- 체결 뒤 봇 장부 수량과, 매수는 체결 뒤 평단·매도는 판 기준 평단.
    position_volume_after DOUBLE PRECISION,
    book_avg_price DOUBLE PRECISION,
    pnl_percent DOUBLE PRECISION,
    pnl_amount DOUBLE PRECISION,
    reason TEXT,
    strategy TEXT,
    -- 값이 어디서 왔나: ORDER 주문 응답, BALANCE 잔고로 복원, BACKFILL* 옛 기록에서 옮김.
    fill_source VARCHAR(20),
    note TEXT,
    -- 옛 표와의 연결. 옛 표를 지울 때 함께 지운다.
    trade_execution_id BIGINT,
    trade_record_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_orders_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'OPEN', 'FILLED', 'CANCELLED', 'REJECTED', 'NOT_PLACED')),
    CONSTRAINT ck_orders_fee_basis CHECK (fee_basis IN ('MEASURED', 'ESTIMATED', 'UNRECORDED', 'LEGACY')),
    CONSTRAINT ck_orders_fill_source CHECK (fill_source IN ('ORDER', 'BALANCE', 'BACKFILL', 'BACKFILL_SNAPSHOT', 'BACKFILL_MANUAL'))
);

-- 거래소마다 주문 번호 공간이 따로다(KIS 모의·실전).
CREATE UNIQUE INDEX uq_orders_client_order ON orders (user_id, exchange, client_order_id) WHERE client_order_id IS NOT NULL;
CREATE UNIQUE INDEX uq_orders_exchange_order ON orders (user_id, exchange, exchange_order_id) WHERE exchange_order_id IS NOT NULL;
CREATE UNIQUE INDEX uq_orders_trade_execution ON orders (trade_execution_id) WHERE trade_execution_id IS NOT NULL;
CREATE INDEX ix_orders_user_exchange_filled ON orders (user_id, exchange, completed_at DESC, id DESC) WHERE status = 'FILLED';
CREATE INDEX ix_orders_unresolved ON orders (user_id, created_at) WHERE status IN ('PENDING', 'OPEN');

-- 옛 체결을 trade_executions 기준으로 옮긴다. 두 표 사이에 공통 키가 없어(V21) 같은 트랜잭션에서 같은 값으로 적힌 줄을
-- 값과 id 순번으로 짝짓고 trade_records 에서는 주문 대금(order_amount)과 id 만 가져온다. V21 이 고친 strategy·pnl_amount 와
-- 서로 형식이 다른 시각은 짝 키에서 뺀다.
-- 엔진 매수(strategy 가 있고 manual 이 아님)는 가격·수량이 체결 뒤 보유 전체라 장부 칼럼에 두고 이 주문의 수량은 비운다
-- (차분으로는 먼지 흡수·부분 매도·소유자 코인을 가를 수 없다). 옛 수동 매수는 그 주문 하나라 체결 칼럼에 둔다.
-- backfill:begin
WITH tr AS (
    SELECT id, user_id, exchange, ticker, side, price, volume, total_amount, order_amount,
           row_number() OVER (PARTITION BY user_id, exchange, ticker, side, price, volume, total_amount ORDER BY id) AS rn
    FROM trade_records
), te AS (
    SELECT t.*,
           row_number() OVER (PARTITION BY user_id, exchange, market, side, price, volume, total_amount ORDER BY id) AS rn,
           (t.side = 'BUY' AND t.strategy IS NOT NULL AND lower(t.strategy) <> 'manual') AS snapshot
    FROM trade_executions t
), pairs AS (
    SELECT te.*, tr.id AS tr_id, tr.order_amount AS tr_order_amount
    FROM te LEFT JOIN tr
      ON tr.user_id IS NOT DISTINCT FROM te.user_id AND tr.exchange = te.exchange AND tr.ticker = te.market
     AND tr.side = te.side AND tr.price = te.price AND tr.volume = te.volume AND tr.total_amount = te.total_amount AND tr.rn = te.rn
)
INSERT INTO orders (
    user_id, exchange, market, side, exchange_order_id, status, decision_price,
    filled_volume, filled_funds, avg_fill_price, fee, fee_basis, position_volume_after, book_avg_price,
    pnl_percent, pnl_amount, reason, strategy, fill_source, trade_execution_id, trade_record_id,
    created_at, updated_at, completed_at
)
SELECT p.user_id, p.exchange, p.market, p.side, p.exchange_order_id, 'FILLED',
       -- 옛 매도 수량은 체결량이라 주문 수량(requested_volume)은 모른다.
       CASE WHEN p.side = 'SELL' THEN p.price END,
       CASE WHEN NOT p.snapshot THEN p.volume END,
       CASE WHEN p.side = 'BUY' AND NOT p.snapshot THEN p.total_amount ELSE p.tr_order_amount END,
       CASE WHEN p.side = 'SELL' AND p.tr_order_amount IS NOT NULL AND p.volume > 0 THEN p.tr_order_amount / p.volume END,
       -- 0 은 미기록이었다(V21).
       NULLIF(p.fee, 0), 'LEGACY',
       CASE WHEN p.snapshot THEN p.volume END,
       CASE WHEN p.snapshot THEN p.price END,
       p.pnl_percent, p.pnl_amount, p.reason, p.strategy,
       CASE WHEN p.side = 'SELL' THEN 'BACKFILL' WHEN p.snapshot THEN 'BACKFILL_SNAPSHOT' ELSE 'BACKFILL_MANUAL' END,
       p.id, p.tr_id, p.executed_at, p.executed_at, p.executed_at
FROM pairs p
WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.trade_execution_id = p.id)
ORDER BY p.id;
-- backfill:end

-- 무엇을 옮겼고 무엇이 짝을 못 찾았는지 기동 로그에 남긴다.
DO $$
DECLARE
    moved BIGINT;
    unpaired_executions BIGINT;
    unpaired_records BIGINT;
BEGIN
    SELECT count(*) INTO moved FROM orders;
    SELECT count(*) INTO unpaired_executions FROM orders WHERE trade_record_id IS NULL;
    SELECT count(*) INTO unpaired_records FROM trade_records r WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.trade_record_id = r.id);
    RAISE NOTICE 'V41 orders: moved=% (trade_executions without a trade_records pair=%), trade_records not moved=%',
        moved, unpaired_executions, unpaired_records;
END $$;
