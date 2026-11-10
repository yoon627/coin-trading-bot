-- 체결 알림 outbox. 체결 기록(trade_executions)과 같은 트랜잭션에 한 행을 넣고, 발송기가 보낸 뒤 결과를 적는다.
-- 커밋 직후 프로세스가 죽거나 Discord 가 실패해도 알림이 사라지지 않게 한다. 같은 체결은 유일키로 한 행뿐이다.
-- webhook 주소는 넣지 않는다 — 보낼 때 사용자 행에서 읽는다. payload 는 embed 입력만 담은 판 붙은 JSON 이다.
-- 체결 트랜잭션 안의 삽입이 실패하면 체결도 되돌아가므로 길이 제한 칼럼과 users FK 를 두지 않는다.
--
-- 테이블만 새로 만든다. 이 테이블을 모르는 V32 이미지는 Flyway 기본 설정(ignoreMigrationPatterns '*:future')으로 뜨고,
-- 옛 방식(커밋 뒤 바로 발송)으로 알린다. 다시 올리기 전 남은 PENDING 처리는 README V33 절을 따른다.
CREATE TABLE trade_notification_outbox (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trade_execution_id BIGINT NOT NULL REFERENCES trade_executions(id),
    user_id BIGINT NOT NULL,
    exchange TEXT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT uq_trade_notification_outbox_execution UNIQUE (trade_execution_id),
    CONSTRAINT ck_trade_notification_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'))
);

-- 보낸 행은 지우지 않으므로 발송기가 찾는 미발송만 색인한다.
CREATE INDEX ix_trade_notification_outbox_pending ON trade_notification_outbox (next_attempt_at, id) WHERE status = 'PENDING';
