-- 옛 거래 표 trade_records·trade_executions 와 그 표를 가리키던 칼럼(trade_notification_outbox.trade_execution_id,
-- orders.trade_execution_id·trade_record_id)을 지운다(db-tidy 2-3b). V44 이미지부터 체결은 orders 에만 쓰고 이 표·칼럼은
-- 아무도 쓰지도 읽지도 않는다. 옛 줄은 모두 V41~V43 이 orders 로 옮겼다(trade_execution_id·trade_record_id 로 이어져 있다).
--
-- 롤백: 자동 롤백 대상(마지막 정상 배포 이미지)은 V44 이후 코드라 이 표·칼럼을 쓰지 않으므로 V47 위에서 뜬다(deploy.sh 의 롤백 하한이
-- V44 다). V44 이전 이미지는 체결 기록에서 옛 표에 쓰다 실패한다 — 그 아래로는 내려가지 않는다(앞으로 고친다). 구조만 되살리는
-- deploy/vultr/sql/V47_down.sql 이 있고, 지운 줄은 머지 전에 따로 뜬 아카이브 덤프에만 있다(README V47 절).
--
-- 지키는 것: 어느 orders 줄과도 이어지지 않은 옛 줄이 있으면 멈춘다 — 누군가 V44 이전 이미지로 손 롤백해 옮겨지지 않은 체결이
-- 생긴 것이다(이어진 줄은 orders 에 있으니 지워도 잃지 않는다). 실패하면 트랜잭션 DDL 로 전체가 되돌려져 기동 실패로 끝난다
-- (롤백 호환 검사를 통과한 배포면 자동 롤백, 아니면 수동 개입).
-- CASCADE 를 쓰지 않는다: 예상 밖의 의존 객체가 있으면 DROP 이 실패해 같은 길로 끝난다.
-- 잠금을 오래 기다리지 않는다(매일 덤프가 모든 표에 공유 잠금을 잡는다). 기다리다 실패하면 같은 길로 끝나고 다시 배포하면 된다.

SET LOCAL lock_timeout = '10s';

DO $$
DECLARE
    executions BIGINT;
    records BIGINT;
    unlinked_executions BIGINT;
    unlinked_records BIGINT;
    linked_notifications BIGINT;
BEGIN
    SELECT count(*) INTO executions FROM trade_executions;
    SELECT count(*) INTO records FROM trade_records;
    SELECT count(*) INTO unlinked_executions FROM trade_executions t
     WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.trade_execution_id = t.id);
    SELECT count(*) INTO unlinked_records FROM trade_records r
     WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.trade_record_id = r.id);
    SELECT count(*) INTO linked_notifications FROM trade_notification_outbox WHERE trade_execution_id IS NOT NULL;
    RAISE NOTICE 'V47 drop: trade_executions rows=%, trade_records rows=%, notifications linked to trade_executions=%',
        executions, records, linked_notifications;
    IF unlinked_executions > 0 OR unlinked_records > 0 THEN
        RAISE EXCEPTION 'V47: 옛 표에 orders 와 이어지지 않은 줄이 있다(trade_executions=%, trade_records=%) — '
            'V44 이전 이미지가 쓴 체결이다. README V47 절대로 옮기거나 정리한 뒤 다시 배포한다', unlinked_executions, unlinked_records;
    END IF;
END $$;

-- 외래 키를 가진 쪽부터: 알림 줄의 칼럼(외래 키·UNIQUE 도 함께 지워진다) → orders 의 연결 칼럼(uq_orders_trade_execution 도) → 표.
ALTER TABLE trade_notification_outbox DROP COLUMN trade_execution_id;
ALTER TABLE orders DROP COLUMN trade_execution_id, DROP COLUMN trade_record_id;
DROP TABLE trade_executions;
DROP TABLE trade_records;
