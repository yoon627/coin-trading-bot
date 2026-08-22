-- 막힌 매도 알림을 재시작에 무관하게 만든다(#55).
--
-- 기존에는 연속 실패 횟수(sellReconcileFailureCount)를 메모리에만 셌다. pending sell 은 durable
-- 인데 카운터는 아니라 수명이 어긋났고, 배포·크래시가 반복되면 매번 0부터 세어 임계에 도달하지
-- 못했다 — 그 사이 processTicker 는 매도·매수 평가를 통째로 막으므로 보유 포지션이 손절 없이
-- 방치된다. 시작 시각을 durable 로 남기고 경과시간으로 판정하면 재시작 횟수와 무관해진다.
ALTER TABLE trading_states
    ADD COLUMN pending_sell_since TIMESTAMPTZ,
    -- 알림은 pending 하나당 1회. durable 이라 재시작해도 중복 발화하지 않는다.
    ADD COLUMN pending_sell_alerted BOOLEAN NOT NULL DEFAULT FALSE;
