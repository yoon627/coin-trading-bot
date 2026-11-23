-- 화면·대사가 orders 를 읽게 바꾸기(다음 단계) 전에 orders 가 trade_records 와 같은 값을 내도록 맞춘다.
--
-- 1) 체결 알림 대기열을 orders 에 잇는다(order_id). trade_execution_id 는 옛 표를 지울 때까지 그대로 둔다.
-- 2) 따라잡기: orders 를 모르는 이미지가 쓴 체결(롤백 동안·손으로 넣은 정정)을 V41 과 같은 규칙으로 옮긴다. 같은 거래소 주문번호의
--    orders 줄이 이미 있으면 건너뛰고 그 수를 NOTICE 로 남긴다. 체결 시각은 화면 기록(trade_records.created_at)과 같게,
--    잔고로 확정한 매도의 수량(requested_volume)과 옛 수동 매수의 가격(decision_price)은 trade_executions 에서 채운다.
--    timestamp → timestamptz 바꿈은 세션 시간대를 쓴다(앱·Flyway 의 드라이버가 JVM 시간대를 보낸다 — trade_records 를 쓴 시간대).
-- 따라잡기 부분은 다시 돌려도 바뀌는 것이 없다. 화면을 orders 로 바꾸는 마이그레이션이 바꾸기 직전에 한 번 더 돌린다.
--
-- 롤백: 새 칼럼만 더하므로 직전 이미지는 그대로 뜨고 쓴다(그 이미지는 order_id 를 넣지 않고 체결 시각을 now() 로 쓴다 —
-- 다음 따라잡기가 맞춘다).

ALTER TABLE trade_notification_outbox ADD COLUMN order_id BIGINT REFERENCES orders (id);
CREATE UNIQUE INDEX uq_trade_notification_outbox_order ON trade_notification_outbox (order_id) WHERE order_id IS NOT NULL;

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

-- 화면을 orders 로 바꾸기 전에 볼 것을 기동 로그에 남긴다.
DO $$
DECLARE
    unlinked_executions BIGINT;
    skipped_existing_order BIGINT;
    unlinked_records BIGINT;
    unlinked_notifications BIGINT;
    off_invariant BIGINT;
    time_ties BIGINT;
BEGIN
    SELECT count(*) INTO unlinked_executions FROM trade_executions t WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.trade_execution_id = t.id);
    -- 위 따라잡기가 같은 거래소 주문번호의 orders 줄이 있어 건너뛴 체결(위 수에 들어 있다).
    SELECT count(*) INTO skipped_existing_order FROM trade_executions t
     WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.trade_execution_id = t.id)
       AND EXISTS (SELECT 1 FROM orders o WHERE o.user_id = t.user_id AND o.exchange = t.exchange AND o.exchange_order_id = t.exchange_order_id);
    SELECT count(*) INTO unlinked_records FROM trade_records r WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.trade_record_id = r.id);
    SELECT count(*) INTO unlinked_notifications FROM trade_notification_outbox WHERE order_id IS NULL;
    -- 화면 값은 총액 = 가격 × 수량(매도·엔진 매수), 옛 수동 매수는 주문 대금 없음을 전제로 orders 에서 다시 만든다.
    SELECT count(*) INTO off_invariant FROM orders o
      JOIN trade_executions te ON te.id = o.trade_execution_id
      LEFT JOIN trade_records tr ON tr.id = o.trade_record_id
     WHERE (o.fill_source <> 'BACKFILL_MANUAL' AND te.total_amount IS DISTINCT FROM te.price * te.volume)
        OR (o.fill_source = 'BACKFILL_MANUAL' AND tr.order_amount IS NOT NULL);
    SELECT count(*) INTO time_ties FROM (
        SELECT 1 FROM orders WHERE status = 'FILLED' GROUP BY user_id, exchange, completed_at HAVING count(*) > 1
    ) s;
    RAISE NOTICE 'V42 orders: trade_executions not linked=% (skipped for an existing order number=%), trade_records not linked=%, notifications not linked=%, rows off the history invariant=%, same-time groups=%',
        unlinked_executions, skipped_existing_order, unlinked_records, unlinked_notifications, off_invariant, time_ties;
END $$;
