-- Discord ERROR 알림(개별·분당 상한 요약) outbox. 로그 appender 가 메모리 큐에 넣고, 발송 루프가 이 표에 넣은 뒤 보내고
-- 결과를 적는다. Discord 장애·재시작·종료 중 알림이 사라지지 않게 한다. DB 에 넣지 못한 알림은 바로 보낸다.
-- webhook 주소는 넣지 않는다 — 보낼 때 설정에서 읽는다. payload 는 Discord embed JSON 이다.
-- 끝난 행(SENT·FAILED·SKIPPED)은 발송 루프가 7일 뒤 지운다.
--
-- 테이블만 새로 만든다. 이 테이블을 모르는 V35 이미지는 Flyway 기본 설정(ignoreMigrationPatterns '*:future')으로 뜨고,
-- 옛 방식(바로 발송)으로 알린다. 다시 올리기 전 남은 PENDING 처리는 README V36 절을 따른다.
CREATE TABLE error_alert_outbox (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payload TEXT NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT ck_error_alert_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'))
);

CREATE INDEX ix_error_alert_outbox_pending ON error_alert_outbox (next_attempt_at, id) WHERE status = 'PENDING';
