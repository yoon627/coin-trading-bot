-- 거래 상태와 거래 기록에 거래소 축을 넣는다. 기존 행은 모두 UPBIT 다.
-- 모의(KIS_PAPER)와 실전(KIS)은 다른 값이라 같은 종목이라도 행이 섞이지 않는다. trade_executions 는 V11 부터 exchange 가 있다.
-- DEFAULT 상수 컬럼 추가는 PostgreSQL 11+ 에서 테이블을 다시 쓰지 않는다.
--
-- 롤백: 옛 이미지는 거래소 축을 몰라 KIS 행이 생긴 뒤에는 그 행까지 Upbit 상태로 읽는다. 그래서 V30 이후에는
-- V30 이전 이미지로 롤백하지 않는다. 불가피하면 README 배포 절의 절차와 deploy/vultr/sql/V30_down.sql 을 쓴다.

ALTER TABLE trading_states
    ADD COLUMN exchange VARCHAR(10) NOT NULL DEFAULT 'UPBIT',
    -- 사람이 거래소에서 확인해야 하는 주문의 사유. 있으면 halt 해제를 거절하고 pending 해제가 함께 지운다.
    ADD COLUMN review_reason VARCHAR(200);

ALTER TABLE trading_states
    ADD CONSTRAINT uq_trading_states_user_exchange_ticker UNIQUE (user_id, exchange, ticker);
ALTER TABLE trading_states
    DROP CONSTRAINT uq_trading_states_user_ticker;

-- UPBIT 행은 'KRX-' 로 시작하지 않고 KIS_PAPER/KIS 행은 'KRX-' 로 시작한다. Upbit 의 비 KRW 마켓은 막지 않는다.
ALTER TABLE trading_states
    ADD CONSTRAINT ck_trading_states_exchange_ticker CHECK ((exchange <> 'UPBIT') = (ticker LIKE 'KRX-%'));

-- 이력 행이라 CHECK 를 걸지 않는다.
ALTER TABLE trade_records
    ADD COLUMN exchange VARCHAR(10) NOT NULL DEFAULT 'UPBIT';

CREATE INDEX idx_trade_records_user_exchange_created ON trade_records(user_id, exchange, created_at DESC);
