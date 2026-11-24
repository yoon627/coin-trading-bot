-- V46(사용자 매매 값 표)를 손으로 되돌린다. Flyway 는 이 파일을 쓰지 않는다(db/migration 밖에 둔다).
--
-- 직전 이미지로 롤백하는 데에는 이 파일이 필요 없다(Flyway 는 모르는 뒤 버전을 건너뛰고, 옛 이미지는 이 표를 모른다). V46 을 고친
-- 파일로 다시 돌려야 할 때만 쓴다. 잃는 것은 사용자가 화면에서 바꾼 매매 값뿐이다 — 새 매수는 env 값으로 돌고, 보유 포지션은
-- trading_states 에 덮인 청산 값을 그대로 쓴다.
--
-- V46 이 마지막 적용 버전일 때만 쓴다. 실행: 서버 /opt/app 에서 앱을 멈추고(docker compose stop app) 이 파일을 /tmp 로 올린 뒤
--   ./dump_local.sh before-V46-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V46_down.sql

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '46') THEN
        RAISE EXCEPTION 'V46_down: V46 이 적용돼 있지 않다';
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE installed_rank > (SELECT installed_rank FROM flyway_schema_history WHERE version = '46')
    ) THEN
        RAISE EXCEPTION 'V46_down: V46 이후 마이그레이션이 적용돼 있다 — 이 파일은 V46 이 마지막일 때만 쓴다';
    END IF;
END $$;

DROP TABLE user_trading_params;

DELETE FROM flyway_schema_history WHERE version = '46';

COMMIT;
