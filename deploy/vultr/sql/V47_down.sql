-- V47(옛 거래 표·연결 칼럼 삭제)를 손으로 되돌린다. Flyway 는 이 파일을 쓰지 않는다(db/migration 밖에 둔다).
--
-- V44 이후 이미지로 롤백하는 데에는 이 파일이 필요 없다(그 이미지는 지운 표·칼럼을 쓰지 않는다). V47 을 고친 파일로 다시 돌리거나
-- 더 아래 버전의 down 을 이어 돌려야 할 때만 쓴다. 표·칼럼은 V46 에서의 정의 그대로 빈 채로 되살린다 — 지운 줄과 연결 값은
-- 돌아오지 않는다(머지 전 아카이브 덤프에만 있다. README V47 절). V44 이전 이미지로 내려가는 것은 지원하지 않는다.
--
-- flyway_schema_history 의 V47 행도 같은 트랜잭션에서 지운다. 그래서 V47 이 든 이미지가 다시 뜨면 V47 이 또 적용돼
-- 되살린 빈 표를 다시 지운다 — 앱을 멈춘 뒤 돌린다. V47 이 마지막 적용 버전일 때만 쓴다.
--
-- 실행: 서버 /opt/app 에서 앱을 멈추고(docker compose stop app) 이 파일을 /tmp 로 올린 뒤
--   ./dump_local.sh before-V47-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V47_down.sql

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '47') THEN
        RAISE EXCEPTION 'V47_down: V47 이 적용돼 있지 않다';
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE installed_rank > (SELECT installed_rank FROM flyway_schema_history WHERE version = '47')
    ) THEN
        RAISE EXCEPTION 'V47_down: V47 이후 마이그레이션이 적용돼 있다 — 이 파일은 V47 이 마지막일 때만 쓴다';
    END IF;
END $$;

-- 정의는 V46 스키마와 같다(V1·V9·V11·V14·V30·V33·V40·V41·V44 가 만든 끝 모양 — 그 버전의 down 이 이어서 고칠 수 있게).
CREATE TABLE trade_records (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    side VARCHAR(4) NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    volume DOUBLE PRECISION NOT NULL,
    total_amount DOUBLE PRECISION NOT NULL,
    pnl_percent DOUBLE PRECISION,
    reason VARCHAR(50),
    strategy VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id BIGINT,
    pnl_amount DOUBLE PRECISION,
    order_amount DOUBLE PRECISION,
    exchange VARCHAR(10) NOT NULL DEFAULT 'UPBIT'
);
CREATE INDEX idx_trade_records_created_at ON trade_records (created_at);
CREATE INDEX idx_trade_records_side_pnl ON trade_records (side, pnl_percent) WHERE pnl_percent IS NOT NULL;
CREATE INDEX idx_trade_records_ticker ON trade_records (ticker);
CREATE INDEX idx_trade_records_user_created ON trade_records (user_id, created_at DESC);
CREATE INDEX idx_trade_records_user_exchange_created ON trade_records (user_id, exchange, created_at DESC);

CREATE TABLE trade_executions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    exchange VARCHAR(10) NOT NULL,
    market VARCHAR(20) NOT NULL,
    side VARCHAR(4) NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    volume DOUBLE PRECISION NOT NULL,
    total_amount DOUBLE PRECISION NOT NULL,
    fee DOUBLE PRECISION DEFAULT 0,
    pnl_percent DOUBLE PRECISION,
    pnl_amount DOUBLE PRECISION,
    reason VARCHAR(50),
    strategy VARCHAR(50),
    exchange_order_id VARCHAR(100),
    executed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_trade_executions_exchange ON trade_executions (exchange, market, executed_at DESC);
CREATE INDEX idx_trade_executions_user ON trade_executions (user_id, executed_at DESC);
CREATE UNIQUE INDEX uq_trade_executions_order_id ON trade_executions (user_id, exchange_order_id) WHERE exchange_order_id IS NOT NULL;

ALTER TABLE trade_notification_outbox ADD COLUMN trade_execution_id BIGINT REFERENCES trade_executions(id);
ALTER TABLE trade_notification_outbox ADD CONSTRAINT uq_trade_notification_outbox_execution UNIQUE (trade_execution_id);

ALTER TABLE orders ADD COLUMN trade_execution_id BIGINT, ADD COLUMN trade_record_id BIGINT;
CREATE UNIQUE INDEX uq_orders_trade_execution ON orders (trade_execution_id) WHERE trade_execution_id IS NOT NULL;

DELETE FROM flyway_schema_history WHERE version = '47';

COMMIT;
