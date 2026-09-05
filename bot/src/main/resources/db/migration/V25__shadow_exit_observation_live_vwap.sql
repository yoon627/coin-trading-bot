-- 그림자 관측에 **실행 슬리피지**를 더한다.
--
-- V24 는 모델 과대추정폭(모델 청산가 vs 발동 tick)만 쟀다. 남은 절반은 tick 가격과 **실제 체결가**의 차이이며,
-- 그건 백테에 아예 없는 항목이다(wiki `query/exit-resolution-verdict-2026-09` 한계).
-- Upbit 개별 주문 조회의 `trades` 배열에서 `Σfunds / Σvolume` 으로 얻는다(최상위 체결금액 합계 필드가 없다).
--
-- nullable 인 이유: 접수 직후 응답·조회 실패·수동 경로에서는 체결 내역을 못 얻는다. 그때 **추정하지 않는다** —
-- 값이 없으면 그 관측은 슬리피지 분모에서 빠져야 하고, 0 을 넣으면 "마찰 없음"으로 오독된다.
ALTER TABLE shadow_exit_observation
    ADD COLUMN live_exit_vwap DOUBLE PRECISION;
