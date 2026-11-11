-- V34(trading_states 봇 장부 칼럼)를 손으로 되돌린다. Flyway 는 이 파일을 쓰지 않는다(db/migration 밖에 둔다).
--
-- 경고: 저장된 봇 장부가 사라진다. 다시 올리면 모든 행이 장부를 저장하기 전 행이 되어, 봇 흔적이 있는 종목은 잔고 전체가
-- 봇 장부로 편입된다(소유자 코인 포함). V33 이미지로 앱만 롤백하는 데에는 이 파일이 필요 없다 — 칼럼을 그대로 두면 장부가 남는다.
--
-- flyway_schema_history 의 V34 행도 같은 트랜잭션에서 지운다. V34 가 마지막 적용 버전일 때만 쓴다.
--
-- 실행: 서버 /opt/app 에서 앱을 멈추고(docker compose stop app) 이 파일을 /tmp 로 올린 뒤, 백업이 성공했을 때만 돌린다
--   ./backup.sh && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V34_down.sql
-- 그다음 V34 이전 이미지로 올린다(APP_VERSION=<V34 이전 SHA> docker compose up -d --pull missing).

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '34') THEN
        RAISE EXCEPTION 'V34_down: V34 가 적용돼 있지 않다';
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE installed_rank > (SELECT installed_rank FROM flyway_schema_history WHERE version = '34')
    ) THEN
        RAISE EXCEPTION 'V34_down: V34 이후 마이그레이션이 적용돼 있다 — 이 파일은 V34 가 마지막일 때만 쓴다';
    END IF;
END $$;

ALTER TABLE trading_states
    DROP COLUMN book_volume,
    DROP COLUMN book_avg_price,
    DROP COLUMN book_written_at,
    DROP COLUMN pending_buy_book_volume,
    DROP COLUMN pending_buy_book_avg,
    DROP COLUMN adopt_baseline_volume,
    DROP COLUMN sell_clear_prior_volume,
    DROP COLUMN sell_clear_volume;

DELETE FROM flyway_schema_history WHERE version = '34';

COMMIT;
