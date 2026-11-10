-- 봇 장부(봇이 산 수량·평단)를 저장한다. V14·V23 은 보유를 저장하지 않고 재시작 때 거래소 잔고에서 되살렸는데,
-- 그러면 소유자가 앱에서 산 코인까지 봇 포지션이 되어 청산 때 팔린다. 이제 재시작·재동기화는 저장된 장부를 잔고만큼
-- 줄이기만 한다(늘리는 것은 봇 체결과 아래 편입 기준이 있을 때만).
--
-- book_written_at 은 장부를 쓸 때 updated_at 과 같은 값으로 쓴다. 이 칼럼을 모르는 V33 이미지가 행을 저장하면
-- updated_at 만 바뀌어 둘이 달라지고, 새 이미지는 그 장부를 낡은 것으로 보고 NULL 로 읽는다.
-- pending_buy_book_* 는 미해소 매수를 낼 때의 봇 장부다(재시작 뒤 체결을 그 위에만 더한다).
-- adopt_baseline_volume 은 손으로 정리한 미해소 매수의 주문 전 보유, sell_clear_* 는 손으로 정리한 미해소 매도의
-- 주문 전 free·주문 수량이다. 다음 동기화가 한 번 쓰고 비운다.
--
-- 칼럼만 더한다. V33 이미지로 앱만 롤백해도 뜨고(Flyway '*:future') 이 칼럼을 건드리지 않는다. V34_down 은 칼럼을
-- 없애야 할 때만 쓴다(장부가 사라진다).
ALTER TABLE trading_states
    ADD COLUMN book_volume DOUBLE PRECISION,
    ADD COLUMN book_avg_price DOUBLE PRECISION,
    ADD COLUMN book_written_at TIMESTAMPTZ,
    ADD COLUMN pending_buy_book_volume DOUBLE PRECISION,
    ADD COLUMN pending_buy_book_avg DOUBLE PRECISION,
    ADD COLUMN adopt_baseline_volume DOUBLE PRECISION,
    ADD COLUMN sell_clear_prior_volume DOUBLE PRECISION,
    ADD COLUMN sell_clear_volume DOUBLE PRECISION;
