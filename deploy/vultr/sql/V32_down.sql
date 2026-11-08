-- V32(bot_state 신규 매수 중지 칼럼)를 손으로 되돌린다. Flyway 는 이 파일을 쓰지 않는다(db/migration 밖에 둔다).
--
-- 경고: 켜 둔 신규 매수 중지가 함께 사라진다. V31 이미지로 롤백하는 데에는 이 파일이 필요 없다(V31 이미지는 적용된 V32 를
-- 무시하고 뜬다). 칼럼 자체를 없애야 할 때만 쓴다.
--
-- flyway_schema_history 의 V32 행도 같은 트랜잭션에서 지운다. 남기면 다음에 V32 가 든 이미지를 올릴 때 칼럼 없이 기동한다.
-- V32 가 마지막 적용 버전일 때만 쓴다.
--
-- 실행: 서버 /opt/app 에서 앱을 멈추고(docker compose stop app) 이 파일을 /tmp 로 올린 뒤, 백업이 성공했을 때만 돌린다
--   ./backup.sh && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V32_down.sql
-- 그다음 V32 이전 이미지로 올린다(APP_VERSION=<V32 이전 SHA> docker compose up -d --pull missing).

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '32') THEN
        RAISE EXCEPTION 'V32_down: V32 가 적용돼 있지 않다';
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE installed_rank > (SELECT installed_rank FROM flyway_schema_history WHERE version = '32')
    ) THEN
        RAISE EXCEPTION 'V32_down: V32 이후 마이그레이션이 적용돼 있다 — 이 파일은 V32 가 마지막일 때만 쓴다';
    END IF;
END $$;

ALTER TABLE bot_state DROP COLUMN entries_paused_changed_at;
ALTER TABLE bot_state DROP COLUMN entries_paused;

DELETE FROM flyway_schema_history WHERE version = '32';

COMMIT;
