-- V45(리프레시 토큰 표)를 손으로 되돌린다. Flyway 는 이 파일을 쓰지 않는다(db/migration 밖에 둔다).
--
-- 직전 이미지로 롤백하는 데에는 이 파일이 필요 없다(Flyway 는 모르는 뒤 버전을 건너뛰고, 옛 이미지는 이 표를 모른다). V45 를 고친
-- 파일로 다시 돌려야 할 때만 쓴다. 잃는 것은 로그인 유지뿐이다 — 다음 접속에서 다시 로그인한다.
--
-- V45 가 마지막 적용 버전일 때만 쓴다. 실행: 서버 /opt/app 에서 앱을 멈추고(docker compose stop app) 이 파일을 /tmp 로 올린 뒤
--   ./dump_local.sh before-V45-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V45_down.sql

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '45') THEN
        RAISE EXCEPTION 'V45_down: V45 가 적용돼 있지 않다';
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE installed_rank > (SELECT installed_rank FROM flyway_schema_history WHERE version = '45')
    ) THEN
        RAISE EXCEPTION 'V45_down: V45 이후 마이그레이션이 적용돼 있다 — 이 파일은 V45 가 마지막일 때만 쓴다';
    END IF;
END $$;

DROP TABLE refresh_tokens;

DELETE FROM flyway_schema_history WHERE version = '45';

COMMIT;
