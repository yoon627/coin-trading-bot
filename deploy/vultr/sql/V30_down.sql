-- V30(거래소 축)을 손으로 되돌린다. Flyway 는 이 파일을 쓰지 않는다(db/migration 밖에 둔다).
--
-- 경고: KIS_PAPER/KIS 행이 하나라도 있으면 실행하지 않는다. 옛 이미지는 거래소 축을 몰라 findByUserId 로 KIS 행까지
-- Upbit 상태로 읽고, 같은 종목의 KIS_PAPER·KIS 두 행이 있으면 옛 UNIQUE (user_id, ticker) 를 되살릴 수 없다.
-- KIS 행이 있으면 README 배포 절의 절차(backup.sh → KIS 행 삭제)를 먼저 한다. 아래 DO 블록이 남은 행을 보면 멈춘다.
--
-- review_reason 은 함께 사라진다. 옛 코드는 이 칸을 모르고 halt 는 남으므로, 남은 사유는 실행 전에 따로 적어 둔다:
--   SELECT user_id, ticker, review_reason FROM trading_states WHERE review_reason IS NOT NULL;
--
-- flyway_schema_history 의 V30 행: 스키마만 되돌리고 이 행을 남기면 다음에 V30 이 든 이미지를 올릴 때 Flyway 가
-- 적용된 것으로 보고 다시 돌리지 않아, 컬럼이 없는 채로 기동한다. 그래서 같은 트랜잭션에서 이 행을 지운다.
-- V30 이 마지막 적용 버전일 때만 쓴다. 그때는 V30 이 든 이미지를 다시 올리면 V30 이 처음부터 다시 적용된다.
-- V31 이후가 적용돼 있으면 V30 행만 빠진 이력은 Flyway validate 가 기동을 막으므로 아래 DO 블록이 멈춘다.
--
-- 실행: 서버 /opt/app 에서 앱을 멈추고(docker compose stop app) ./backup.sh 를 돌린 뒤, 이 파일을 /tmp 로 올려
--   docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V30_down.sql
-- 그다음 V30 이전 이미지로 올린다(APP_VERSION=<V30 이전 SHA> docker compose up -d --pull missing).

BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM trading_states WHERE exchange <> 'UPBIT')
        OR EXISTS (SELECT 1 FROM trade_records WHERE exchange <> 'UPBIT') THEN
        RAISE EXCEPTION 'V30_down: UPBIT 가 아닌 행이 남아 있다 — README 의 KIS 행 삭제 절차를 먼저 한다';
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE installed_rank > (SELECT installed_rank FROM flyway_schema_history WHERE version = '30')
    ) THEN
        RAISE EXCEPTION 'V30_down: V30 이후 마이그레이션이 적용돼 있다 — 이 파일은 V30 이 마지막일 때만 쓴다';
    END IF;
END $$;

DROP INDEX idx_trade_records_user_exchange_created;
ALTER TABLE trade_records DROP COLUMN exchange;

ALTER TABLE trading_states DROP CONSTRAINT ck_trading_states_exchange_ticker;
ALTER TABLE trading_states ADD CONSTRAINT uq_trading_states_user_ticker UNIQUE (user_id, ticker);
ALTER TABLE trading_states DROP CONSTRAINT uq_trading_states_user_exchange_ticker;
ALTER TABLE trading_states
    DROP COLUMN review_reason,
    DROP COLUMN exchange;

DELETE FROM flyway_schema_history WHERE version = '30';

COMMIT;
