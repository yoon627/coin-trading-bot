-- orders 를 trade_records·trade_executions 에 다시 맞춘다. V42·V43 의 따라잡기와 글자 그대로 같다(시험이 본다).
--
-- 언제: V42 아래 이미지(orders 를 모르거나 체결 시각을 now() 로 쓰는 이미지)로 손으로 롤백했다가 V43 이후 이미지로 다시 올린 뒤.
-- 자동 롤백은 하한(V42)이 막아 이 일이 생기지 않는다. V43 은 이미 적용돼 다시 돌지 않으므로 그 사이의 체결은 이 파일로 옮긴다.
-- 다시 돌려도 바뀌는 것이 없다. 끝에 V43 과 같은 NOTICE 를 남긴다 — 모두 0 인지 본다.
--
-- 시간대: 체결 시각 바꿈은 세션 시간대를 쓴다. 앱(JVM)과 같은 UTC 로 고정한다(운영 JVM 시간대가 바뀌면 여기도 바꾼다).
-- 실행: 서버 /opt/app 에서 이 파일을 /tmp 로 올린 뒤(앱은 돌아도 된다)
--   ./dump_local.sh before-orders-catchup && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/orders_catchup.sql

BEGIN;
SET LOCAL TIME ZONE 'UTC';

-- catchup:begin
WITH te AS (
    SELECT t.*,
           row_number() OVER (PARTITION BY user_id, exchange, market, side, price, volume, total_amount ORDER BY id) AS rn,
           (t.side = 'BUY' AND t.strategy IS NOT NULL AND lower(t.strategy) <> 'manual') AS snapshot
    FROM trade_executions t
    WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.trade_execution_id = t.id)
), tr AS (
    SELECT r.id, r.user_id, r.exchange, r.ticker, r.side, r.price, r.volume, r.total_amount, r.order_amount,
           row_number() OVER (PARTITION BY user_id, exchange, ticker, side, price, volume, total_amount ORDER BY id) AS rn
    FROM trade_records r
    WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.trade_record_id = r.id)
), pairs AS (
    SELECT te.*, tr.id AS tr_id, tr.order_amount AS tr_order_amount
    FROM te LEFT JOIN tr
      ON tr.user_id IS NOT DISTINCT FROM te.user_id AND tr.exchange = te.exchange AND tr.ticker = te.market
     AND tr.side = te.side AND tr.price = te.price AND tr.volume = te.volume AND tr.total_amount = te.total_amount AND tr.rn = te.rn
), fresh AS (
    SELECT p.* FROM pairs p
    WHERE p.exchange_order_id IS NULL
       OR NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id = p.user_id AND o.exchange = p.exchange AND o.exchange_order_id = p.exchange_order_id)
)
INSERT INTO orders (
    user_id, exchange, market, side, exchange_order_id, status, decision_price,
    filled_volume, filled_funds, avg_fill_price, fee, fee_basis, position_volume_after, book_avg_price,
    pnl_percent, pnl_amount, reason, strategy, fill_source, trade_execution_id, trade_record_id,
    created_at, updated_at, completed_at
)
SELECT f.user_id, f.exchange, f.market, f.side, f.exchange_order_id, 'FILLED',
       CASE WHEN f.side = 'SELL' OR NOT f.snapshot THEN f.price END,
       CASE WHEN NOT f.snapshot THEN f.volume END,
       CASE WHEN f.side = 'BUY' AND NOT f.snapshot THEN f.total_amount ELSE f.tr_order_amount END,
       CASE WHEN f.side = 'SELL' AND f.tr_order_amount IS NOT NULL AND f.volume > 0 THEN f.tr_order_amount / f.volume END,
       NULLIF(f.fee, 0), 'LEGACY',
       CASE WHEN f.snapshot THEN f.volume END,
       CASE WHEN f.snapshot THEN f.price END,
       f.pnl_percent, f.pnl_amount, f.reason, f.strategy,
       CASE WHEN f.side = 'SELL' THEN 'BACKFILL' WHEN f.snapshot THEN 'BACKFILL_SNAPSHOT' ELSE 'BACKFILL_MANUAL' END,
       f.id, f.tr_id, f.executed_at, f.executed_at, f.executed_at
FROM fresh f
ORDER BY f.id
ON CONFLICT DO NOTHING;

UPDATE orders o SET completed_at = tr.created_at
FROM trade_records tr
WHERE o.trade_record_id = tr.id AND o.status = 'FILLED' AND o.completed_at IS DISTINCT FROM tr.created_at;

UPDATE orders o SET requested_volume = te.volume
FROM trade_executions te
WHERE o.trade_execution_id = te.id AND o.side = 'SELL' AND o.fill_source = 'BALANCE' AND o.requested_volume IS DISTINCT FROM te.volume;

UPDATE orders o SET decision_price = te.price
FROM trade_executions te
WHERE o.trade_execution_id = te.id AND o.fill_source = 'BACKFILL_MANUAL' AND o.decision_price IS DISTINCT FROM te.price;

UPDATE trade_notification_outbox n SET order_id = o.id
FROM orders o
WHERE o.trade_execution_id = n.trade_execution_id AND n.order_id IS DISTINCT FROM o.id;
-- catchup:end

-- report:begin
DO $$
DECLARE
    unlinked_executions BIGINT;
    skipped_existing_order BIGINT;
    unlinked_records BIGINT;
    unlinked_notifications BIGINT;
    filled_without_time BIGINT;
    filled_without_record BIGINT;
    incomplete_rows BIGINT;
    time_ties BIGINT;
BEGIN
    SELECT count(*) INTO unlinked_executions FROM trade_executions t WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.trade_execution_id = t.id);
    -- 같은 거래소 주문번호의 orders 줄이 있어 따라잡기가 건너뛴 체결 — 화면에 없다(위 수에 들어 있다).
    SELECT count(*) INTO skipped_existing_order FROM trade_executions t
     WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.trade_execution_id = t.id)
       AND EXISTS (SELECT 1 FROM orders o WHERE o.user_id = t.user_id AND o.exchange = t.exchange AND o.exchange_order_id = t.exchange_order_id);
    SELECT count(*) INTO unlinked_records FROM trade_records r WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.trade_record_id = r.id);
    SELECT count(*) INTO unlinked_notifications FROM trade_notification_outbox WHERE order_id IS NULL;
    -- 화면은 체결 시각이 없는 줄을 맨 뒤에 둔다.
    SELECT count(*) INTO filled_without_time FROM orders WHERE status = 'FILLED' AND completed_at IS NULL;
    -- 옛 화면에 없던 줄(짝 기록이 없는 정정 체결 등)이 새 화면에 보인다.
    SELECT count(*) INTO filled_without_record FROM orders WHERE status = 'FILLED' AND trade_record_id IS NULL;
    -- 화면 값을 만들 칼럼이 비어 0 으로 보이는 줄(TradeHistoryRow.incomplete 와 같은 조건).
    SELECT count(*) INTO incomplete_rows FROM orders
     WHERE status = 'FILLED'
       AND (completed_at IS NULL
            OR (side = 'SELL' AND (decision_price IS NULL OR COALESCE(filled_volume, requested_volume) IS NULL))
            OR (side = 'BUY' AND fill_source = 'BACKFILL_MANUAL' AND (decision_price IS NULL OR filled_volume IS NULL OR filled_funds IS NULL))
            OR (side = 'BUY' AND fill_source IS DISTINCT FROM 'BACKFILL_MANUAL' AND (book_avg_price IS NULL OR position_volume_after IS NULL)));
    SELECT count(*) INTO time_ties FROM (
        SELECT 1 FROM orders WHERE status = 'FILLED' GROUP BY user_id, exchange, completed_at HAVING count(*) > 1
    ) s;
    RAISE NOTICE 'orders catchup: trade_executions not linked=% (skipped for an existing order number=%), trade_records not linked=%, notifications not linked=%, filled without time=%, filled without record=%, incomplete history rows=%, same-time groups=%',
        unlinked_executions, skipped_existing_order, unlinked_records, unlinked_notifications, filled_without_time, filled_without_record,
        incomplete_rows, time_ties;
END $$;
-- report:end

COMMIT;
