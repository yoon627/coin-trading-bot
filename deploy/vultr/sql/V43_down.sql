-- V43(따라잡기만 하는 마이그레이션)을 손으로 되돌린다. Flyway 는 이 파일을 쓰지 않는다(db/migration 밖에 둔다).
--
-- V43 은 표 구조를 바꾸지 않아 V42 이미지(dd70e9c7)로 롤백하는 데에는 이 파일이 필요 없다(Flyway 는 모르는 뒤 버전을 건너뛴다).
-- 기록을 지워야 V43 을 고친 파일로 다시 돌릴 수 있을 때만 쓴다. 따라잡은 줄과 맞춘 값은 되돌리지 않는다 — 옛 표에서 다시 만들 수 있다.
--
-- V43 이 마지막 적용 버전일 때만 쓴다. 실행: 서버 /opt/app 에서 앱을 멈추고(docker compose stop app) 이 파일을 /tmp 로 올린 뒤
--   ./dump_local.sh before-V43-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V43_down.sql

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '43') THEN
        RAISE EXCEPTION 'V43_down: V43 이 적용돼 있지 않다';
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE installed_rank > (SELECT installed_rank FROM flyway_schema_history WHERE version = '43')
    ) THEN
        RAISE EXCEPTION 'V43_down: V43 이후 마이그레이션이 적용돼 있다 — 이 파일은 V43 이 마지막일 때만 쓴다';
    END IF;
END $$;

DELETE FROM flyway_schema_history WHERE version = '43';

COMMIT;
