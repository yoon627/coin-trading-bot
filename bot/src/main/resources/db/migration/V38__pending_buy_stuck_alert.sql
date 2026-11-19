-- 막힌 매수 알림을 조회 실패 횟수 대신 경과 시간으로 판정한다(매도는 V20). 확인 조회가 실패하면 간격을 늘리므로
-- 횟수로는 알림이 조회 간격에 따라 늦어진다. reconcile_failure_count 는 더 쓰지 않지만 직전 이미지 롤백을 위해 둔다.
-- 이 칼럼들을 모르는 이전 이미지는 INSERT 에 기본값을 쓰고 UPDATE 에서 건드리지 않으므로 롤백 뒤에도 그대로 돈다.
-- 되돌리기는 deploy/vultr/sql/V38_down.sql.
ALTER TABLE trading_states
    ADD COLUMN pending_buy_since TIMESTAMPTZ,
    -- 알림은 pending 하나당 1회. durable 이라 재시작해도 중복 발화하지 않는다.
    ADD COLUMN pending_buy_alerted BOOLEAN NOT NULL DEFAULT FALSE;
