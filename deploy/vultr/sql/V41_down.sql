-- V41(orders 표)을 손으로 되돌린다. Flyway 는 이 파일을 쓰지 않는다(db/migration 밖에 둔다).
--
-- V40 이미지로 롤백하는 데에는 이 파일이 필요 없다(그 이미지는 이 표를 모르고 뜬다). 표 자체를 없애야 할 때만 쓴다.
-- 옛 체결은 trade_records·trade_executions 에 그대로 있다. V41 이후 이미지가 쓴 체결도 그 두 표에 함께 남는다.
--
-- flyway_schema_history 의 V41 행도 같은 트랜잭션에서 지운다. 그래서 V41 이 든 이미지가 다시 뜨면 표를 다시 만들고 다시 옮긴다 —
-- 앱을 멈춘 뒤 돌리고, 그다음에는 V41 이전 이미지만 띄운다. V41 이 마지막 적용 버전일 때만 쓴다.
--
-- 실행: 서버 /opt/app 에서 앱을 멈추고(docker compose stop app) 이 파일을 /tmp 로 올린 뒤, 서버 디스크 덤프가 성공했을 때만 돌린다
--   ./dump_local.sh before-V41-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V41_down.sql
-- 그다음 V41 이전 이미지로 올린다(APP_VERSION=<V41 이전 SHA> docker compose up -d --pull missing).

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '41') THEN
        RAISE EXCEPTION 'V41_down: V41 이 적용돼 있지 않다';
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE installed_rank > (SELECT installed_rank FROM flyway_schema_history WHERE version = '41')
    ) THEN
        RAISE EXCEPTION 'V41_down: V41 이후 마이그레이션이 적용돼 있다 — 이 파일은 V41 이 마지막일 때만 쓴다';
    END IF;
END $$;

DROP TABLE orders;

DELETE FROM flyway_schema_history WHERE version = '41';

COMMIT;
