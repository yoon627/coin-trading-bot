-- 화면(/api/trades·/roundtrips)과 매일 대조가 orders 를 읽기 시작하는 배포의 마이그레이션. V42 의 따라잡기를 한 번 더 돌린다.
-- V42 뒤에 orders 를 모르거나 시각을 now() 로 쓰는 이미지가 돌았다면(손 롤백) 그 체결을 옮기고 값을 맞춘다.
-- catchup 부분은 V42·deploy/vultr/sql/orders_catchup.sql 과 글자 그대로 같다(시험이 본다). 다시 돌려도 바뀌는 것이 없다.
-- 표 구조는 바꾸지 않는다. 되돌리기는 deploy/vultr/sql/V43_down.sql(기록만 지운다).

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

-- 화면이 orders 를 읽기 전에 볼 것을 기동 로그에 남긴다. 배포 전에 모두 0 이어야 한다(not linked 의 옛 기록 몫은 V42 와 같다).
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
    RAISE NOTICE 'V43 orders: trade_executions not linked=% (skipped for an existing order number=%), trade_records not linked=%, notifications not linked=%, filled without time=%, filled without record=%, incomplete history rows=%, same-time groups=%',
        unlinked_executions, skipped_existing_order, unlinked_records, unlinked_notifications, filled_without_time, filled_without_record,
        incomplete_rows, time_ties;
END $$;
-- report:end
