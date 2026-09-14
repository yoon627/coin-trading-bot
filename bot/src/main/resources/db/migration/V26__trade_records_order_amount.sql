-- "이 주문이 실제로 체결한 대금"을 기록한다 (#146).
--
-- trade_records 의 엔진 BUY 행은 volume·total_amount 가 포지션 스냅샷(총 보유량·전체 원가)이다 — 재시작 시
-- 거래소 잔고 복원과 이중계상되지 않게 하려는 의도(#20)라 그 의미는 그대로 둔다. 그러나 전략별 집계 SUM,
-- SPA 거래 목록, Discord 매수 알림은 그 값을 "이번 주문 금액"으로 읽어 부풀려진 숫자를 보여 왔다.
-- 매도 행의 total_amount 도 판단 tick 가격 × 수량 평가액이지 체결 대금이 아니다.
--
-- 주문 응답의 trades[].funds 합(수수료 미포함 체결가 × 체결량)을 여기 남기고 소비처가 이것을 우선 읽는다.
--
-- nullable 인 이유: 과거 행·주문 응답이 없는 잔고복원·수동 경로에서는 얻을 수 없고, 그때 추정하지 않는다
-- (0 이나 price×volume 을 넣으면 고치려던 스냅샷이 다시 들어간다). 과거 행은 소급하지 않는다 — 복원할 데이터가 없다.
-- 롤백: additive 컬럼이라 앱만 되돌리면 되고 DROP 하지 않는다(신규 데이터 유실).
ALTER TABLE trade_records
    ADD COLUMN order_amount DOUBLE PRECISION;
