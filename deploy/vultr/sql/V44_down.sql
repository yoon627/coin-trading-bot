-- V44(알림 대기열 trade_execution_id 의 NOT NULL 풀기)를 손으로 되돌린다. Flyway 는 이 파일을 쓰지 않는다(db/migration 밖에 둔다).
--
-- 직전 이미지(26bb5ad4)로 롤백하는 데에는 이 파일이 필요 없다(Flyway 는 모르는 뒤 버전을 건너뛴다). V44 를 고친 파일로 다시 돌려야
-- 할 때만 쓴다. V44 뒤의 이미지가 쓴 알림 줄은 trade_execution_id 가 없어 NOT NULL 을 다시 걸 수 없으므로, 그런 줄이 있으면 수를 대며 멈춘다.
--
-- V44 가 마지막 적용 버전일 때만 쓴다. 실행: 서버 /opt/app 에서 앱을 멈추고(docker compose stop app) 이 파일을 /tmp 로 올린 뒤
--   ./dump_local.sh before-V44-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V44_down.sql

BEGIN;

DO $$
DECLARE
    without_execution BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '44') THEN
        RAISE EXCEPTION 'V44_down: V44 가 적용돼 있지 않다';
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE installed_rank > (SELECT installed_rank FROM flyway_schema_history WHERE version = '44')
    ) THEN
        RAISE EXCEPTION 'V44_down: V44 이후 마이그레이션이 적용돼 있다 — 이 파일은 V44 가 마지막일 때만 쓴다';
    END IF;
    SELECT count(*) INTO without_execution FROM trade_notification_outbox WHERE trade_execution_id IS NULL;
    IF without_execution > 0 THEN
        RAISE EXCEPTION 'V44_down: trade_execution_id 없는 알림 줄 % 개가 있어 NOT NULL 을 걸 수 없다', without_execution;
    END IF;
END $$;

ALTER TABLE trade_notification_outbox ALTER COLUMN trade_execution_id SET NOT NULL;

DELETE FROM flyway_schema_history WHERE version = '44';

COMMIT;
