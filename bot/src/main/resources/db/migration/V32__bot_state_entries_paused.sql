-- 사용자·거래소별 신규 매수 중지. 켜져 있으면 엔진은 BUY 주문만 내지 않고 손절·청산 매도는 그대로 낸다.
-- entries_paused_changed_at 은 마지막으로 값이 바뀐 시각이다(한 번도 바꾸지 않았으면 NULL).
--
-- 칼럼만 더하므로 이 칼럼을 모르는 V31 이미지도 Flyway 기본 설정(ignoreMigrationPatterns '*:future')으로 그대로 뜬다.
-- 다만 그 이미지는 플래그를 읽지 않아 매수가 다시 나가므로, 앱을 V31 이미지로 롤백하면 봇을 정지한다.
ALTER TABLE bot_state ADD COLUMN entries_paused BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE bot_state ADD COLUMN entries_paused_changed_at TIMESTAMPTZ NULL;
