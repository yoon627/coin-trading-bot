-- 주문 전에 남기는 클라이언트 identifier(#227). 응답(uuid)을 받기 전에 끊긴 주문을 재시작 뒤 거래소에서 찾는 근거다.
-- Upbit identifier 는 최대 64자. 컬럼 추가만이라 옛 이미지는 이 컬럼을 무시한다 — 단 옛 코드는 identifier 만 있는
-- pending 을 모르고 다시 주문하므로, 롤백은 "새 이미지 정지 → 이 컬럼이 채워진 행 0 확인 → 옛 이미지 기동" 순서로 한다.
-- 도는 중에 확인하면 그 사이 새 행이 생길 수 있다. 남은 행은 Upbit 주문 내역으로 확인해 주문이 있으면 그 uuid 를
-- pending_*_uuid 로 옮기고 identifier 를 비우고, 없으면 identifier 만 비운다. 절차는 wiki persistence-schema.
ALTER TABLE trading_states
    ADD COLUMN pending_buy_identifier VARCHAR(64),
    ADD COLUMN pending_sell_identifier VARCHAR(64);
