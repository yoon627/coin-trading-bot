-- V40(users·trade_executions 의 매핑하지 않는 칼럼 삭제)을 손으로 되돌린다. Flyway 는 이 파일을 쓰지 않는다(db/migration 밖에 둔다).
--
-- V39 이미지로 롤백하는 데에는 이 파일이 필요 없다(그 이미지는 이 칼럼을 쓰지 않는다).
-- V39 이전 이미지로 내려가야 할 때만 쓴다. 칼럼은 원래 정의(V4·V8·V11)와 기본값으로 되살린다 — 기본값이 아니던 값은
-- 돌아오지 않는다(배포 전 덤프에만 있다). 그 아래로 더 내려가면 이어서 V39_down.sql 을 돌린다.
--
-- flyway_schema_history 의 V40 행도 같은 트랜잭션에서 지운다. 그래서 V40 이 든 이미지가 다시 뜨면 V40 이 또 적용돼
-- 되살린 칼럼을 다시 지운다 — 앱을 멈춘 뒤 돌리고, 그다음에는 V40 이전 이미지만 띄운다.
-- V40 이 마지막 적용 버전일 때만 쓴다.
--
-- 실행: 서버 /opt/app 에서 앱을 멈추고(docker compose stop app) 이 파일을 /tmp 로 올린 뒤, 서버 디스크 덤프가 성공했을 때만 돌린다
--   ./dump_local.sh before-V40-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V40_down.sql
-- 그다음 V40 이전 이미지로 올린다(APP_VERSION=<V40 이전 SHA> docker compose up -d --pull missing).

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '40') THEN
        RAISE EXCEPTION 'V40_down: V40 이 적용돼 있지 않다';
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE installed_rank > (SELECT installed_rank FROM flyway_schema_history WHERE version = '40')
    ) THEN
        RAISE EXCEPTION 'V40_down: V40 이후 마이그레이션이 적용돼 있다 — 이 파일은 V40 이 마지막일 때만 쓴다';
    END IF;
END $$;

ALTER TABLE users
    ADD COLUMN public_profile BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN public_strategy BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN admin BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE trade_executions
    ADD COLUMN order_type VARCHAR(10) NOT NULL DEFAULT 'MARKET',
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'EXECUTED';

DELETE FROM flyway_schema_history WHERE version = '40';

COMMIT;
