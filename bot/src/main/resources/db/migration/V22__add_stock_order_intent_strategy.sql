-- 체결 기록의 전략·사유 귀속을 위한 주문 WAL 확장 (#130 — Upbit V21 의 KIS 대응).
--
-- 왜 상태 테이블이 아니라 주문 WAL 에 싣나:
--   StockOrderReconciler 는 @Scheduled(15s) 로 엔진 루프와 독립해 돈다. 매도 체결이 거래소 잔고에
--   반영되면 엔진의 syncFromHoldings 가 stock_position_state.entry_strategy 를 지우는데(청산 시
--   고점·진입메타를 끊지 않으면 다음 진입이 옛 고점을 물려받으므로 그 자체는 옳다), 체결 기록을
--   쓰는 시점에 그 상태를 조회하면 경합에서 전략을 잃는다 — 지금 고치려는 결함과 같은 증상이다.
--   주문 접수 시점의 값을 여기 박아두면 reconcile 이 언제 돌든 결과가 같다.
--
-- 둘 다 nullable: 이 마이그레이션 이전에 접수돼 아직 미체결인 주문은 값이 없고, 그건 정상이다.
-- reason 은 매도에만 있다 — 매수는 사유 개념이 없어 NULL 로 남는다.
ALTER TABLE stock_order_intent
    ADD COLUMN strategy VARCHAR(64),
    ADD COLUMN reason   VARCHAR(32);
