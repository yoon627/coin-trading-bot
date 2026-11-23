-- 체결 기록이 orders 에만 남게 되면서(trade_executions 쓰기 멈춤) 체결 알림 대기열 줄은 옛 체결 표를 가리키지 않는다.
-- NOT NULL 만 푼다. 외래 키·UNIQUE·칼럼은 옛 표를 지울 때 함께 지운다 — 직전 이미지(26bb5ad4)는 여전히 이 칼럼에 넣는다.
-- 롤백: 직전 이미지는 그대로 뜨고 쓴다. 다만 이 이미지가 쓴 PENDING 줄(trade_execution_id NULL)이 남아 있으면 그 이미지의 알림
-- 발송기가 그 줄을 읽지 못한다 — README 의 V44 절 runbook.

ALTER TABLE trade_notification_outbox ALTER COLUMN trade_execution_id DROP NOT NULL;
