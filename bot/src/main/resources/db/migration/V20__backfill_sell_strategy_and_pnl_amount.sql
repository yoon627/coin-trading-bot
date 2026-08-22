-- 매도 기록의 진입 전략 귀속 복구 + 실현 손익(원) 저장.
--
-- PositionManager.buildSellRecord 가 TradeRecord 를 만들 때 strategy 인자를 넘기지 않아, 이 마이그레이션
-- 시점까지의 매도 기록은 전부 strategy=NULL 이다. /api/strategies/performance 가 그 손익을 통째로
-- 'unknown' 그룹에 넣어 왔다. 코드는 같은 릴리스에서 고치고, 여기서는 과거 행을 소급 복구한다.
--
-- ## 귀속 규칙: 포지션 구간(직전 SELL 이후) 내 **첫 번째 non-manual BUY**
--
-- 런타임이 남기는 값과 일치시킨 것이다. markBought 의 실제 분기는 이렇다:
--
--   entryStrategy = if (resuming) entryStrategy ?: strategy else strategy   // resuming = 진입 시 position
--
-- completeBuy 가 replace=true 로 부르므로 "추가매수 시 유지" 가지(`position && !replace`)는 타지 않지만,
-- else 안에서 resuming 이 참이면 **기존 entryStrategy 가 그대로 살아남는다**. 그 경로는 실재한다 —
-- 재시작 후 syncPosition 이 position=true 로 만든 뒤 reconcilePendingBuy(:207)나 BalanceRecovery(:241)가
-- completeBuy 를 부르는 경우다. 즉 한 포지션에 엔진 BUY 행이 여럿이면 살아남는 값은 **먼저 찍힌 쪽**이다.
--
-- manual 을 후보에서 빼는 이유는 다르다: 수동 매수(TradeExecutionService.executeBuy)는 TradingState 를
-- 아예 건드리지 않고 syncPosition 도 entryStrategy 를 세우지 않는다. 그래서 수동 매수 위에 엔진이
-- 매수하면 resuming=false 로 엔진 전략이 들어간다 — manual 은 애초에 entryStrategy 후보가 아니다.
-- 후보가 하나도 없으면 NULL 로 남긴다(런타임도 그 포지션에선 unknown 이다).
--
-- ⚠️ 원금(아래 pnl_amount)은 반대로 **마지막** BUY 를 본다. 전략은 최초 진입값이 유지되는 반면
-- avgBuyPrice 는 markBought 가 매번 덮어쓰기 때문이다. 두 기준이 다른 것은 의도다.
--
-- ## 이 규칙이 성립하는 근거와 한계
--
-- 사전 조회로 티커별 BUY/SELL 이 완전 교대함을 확인했다(직전 BUY 없는 SELL 0건). 이는 코드가 보장하는
-- 불변식이 아니라 그 시점 데이터의 성질이다 — 부분 매도(applySellFillOutcome 의 remaining>0 분기)와
-- 수동 executeSellVolume 은 이미 오늘도 가능하므로 미래에는 깨질 수 있다. 전제가 어긋나는 행은 상관
-- 서브쿼리가 NULL 을 돌려주어 건드리지 않고 넘어가고, 맨 아래 점검 블록이 그 수를 WARNING 으로 알린다.
--
-- 구체적으로 `BUY → 부분 SELL → 잔여 SELL` 이면 두 번째 SELL 의 윈도우 하한이 첫 SELL 이라 그 앞의 BUY 가
-- 배제되어 NULL 로 남는다. 런타임은 부분 체결 뒤에도 entryStrategy 를 유지하므로 같은 포지션인데 소급분만
-- 갈리는 셈이다. 대상 데이터에는 없어서(연속 SELL 0건) 손대지 않았다. 규칙을 "직전 SELL 에서 물려받기" 로
-- 넓히려면 그 SELL 이 같은 포지션인지 판단할 근거가 필요한데, 수량 누적을 추적하지 않는 한 SQL 로는
-- 알 수 없다 — 추측으로 넓히면 조용히 틀린 귀속을 만든다. NULL 로 두고 WARNING 으로 드러내는 편이 낫다.
--
-- 대상을 id 나 시각으로 **상한하지 않는다**. 이 마이그레이션이 도는 시점은 새 앱이 기동할 때이고, 그때
-- strategy 가 비어 있는 매도 행은 정의상 전부 구버전 코드가 쓴 것이다(새 코드는 채운다). 측정 시점의
-- max id 로 고정하면 측정과 배포 사이에 체결된 거래가 영구히 미보정으로 남는다 — 봇은 그 사이에도 돈다.
--
-- 페어링 순서와 tie-break 은 created_at 이 아니라 id 로 한다. created_at 은 마이크로초 동률이 가능하고,
-- 두 테이블이 서로 다른 시각을 담으며(도메인 객체 생성 시각 vs 엔티티 생성 시각), 타입도 다르다
-- (trade_records 는 TIMESTAMP, trade_executions 는 TIMESTAMPTZ — 같은 리터럴이 세션 TimeZone 에 따라
-- 다르게 해석된다). id 는 GENERATED ALWAYS AS IDENTITY 라 삽입 순서에 단조롭고 동률이 없다.
--
-- 두 테이블은 조인하지 않고 각자 자기 데이터로 같은 규칙을 돌린다. trade_records 에는
-- exchange_order_id 컬럼이 없고 trade_executions 도 일부가 NULL 이라 공통 키가 없으며, id 가 우연히
-- 나란한 것은 KIS 경로(trade_executions 단독 write)가 끼면 깨진다. 사전 조회로 두 테이블의 독립
-- 페어링 결과가 완전히 일치함을 확인했다(불일치 0건).

-- 실현 손익(원). pnl_percent 와 같은 net 기준이라 두 값을 함께 합산해도 어긋나지 않는다.
ALTER TABLE trade_records ADD COLUMN pnl_amount DOUBLE PRECISION;

-- 되돌릴 근거. backfill 은 NULL→값 덮어쓰기라 규칙이 틀렸을 때 원본을 복원할 방법이 없다.
-- 확인 후 불필요해지면 DROP 해도 되는 임시 테이블이다.
--
-- 조건은 아래 네 UPDATE 가 건드리는 행의 **합집합**이어야 한다. strategy 백필 대상(strategy IS NULL)만
-- 담으면, 이미 strategy 가 있는 수동 매도('manual')가 pnl_amount 백필만 받았을 때 원본이 남지 않는다.
CREATE TABLE trade_records_v20_backup AS
SELECT id, strategy, pnl_amount FROM trade_records
WHERE side = 'SELL'
  AND (strategy IS NULL OR (pnl_amount IS NULL AND pnl_percent IS NOT NULL));

CREATE TABLE trade_executions_v20_backup AS
SELECT id, strategy, pnl_amount FROM trade_executions
WHERE side = 'SELL' AND exchange = 'UPBIT'
  AND (strategy IS NULL OR (pnl_amount IS NULL AND pnl_percent IS NOT NULL));

-- user_id 는 IS NOT DISTINCT FROM — trade_records.user_id 는 nullable 이고 `NULL = NULL` 은 참이 아니다.
UPDATE trade_records s
SET strategy = (
    SELECT b.strategy FROM trade_records b
    WHERE b.ticker = s.ticker
      AND b.side = 'BUY'
      AND b.user_id IS NOT DISTINCT FROM s.user_id
      AND b.strategy IS NOT NULL
      AND b.strategy <> 'manual'
      AND b.id < s.id
      AND b.id > COALESCE((
          SELECT MAX(p.id) FROM trade_records p
          WHERE p.ticker = s.ticker AND p.side = 'SELL'
            AND p.user_id IS NOT DISTINCT FROM s.user_id AND p.id < s.id), 0)
    ORDER BY b.id ASC LIMIT 1)
WHERE s.side = 'SELL' AND s.strategy IS NULL;

-- 매수원금은 BUY 행의 total_amount 합이 아니라 **마지막 BUY 의 평단 × 매도수량**이다.
-- completeBuy 가 account.balanceDouble()(통화 전체 잔고)와 avgBuyPriceDouble()(거래소 평단)으로
-- 기록하기 때문에 엔진 BUY 행은 증분 leg 이 아니라 그 시점 포지션 누적값이다. 합산하면 이중계상된다.
-- 전략과 달리 manual 을 걸러내지 않는다 — 엔진 BUY 행은 평단이므로 그대로 쓸 수 있다. 다만 수동 BUY 는
-- total_amount/volume 이 평단이 아니라 그 주문의 틱 가격이므로(executeBuy 가 amount/currentPrice 로
-- 기록), 마지막 leg 이 manual 이면 이 원금은 틀린다. 대상 데이터에는 그런 행이 없다(수동 매수 2건은
-- 전부 포지션의 첫 leg). 나중에 규칙을 확장할 때 이 한계를 넘겨짚지 말 것.
UPDATE trade_records s
SET pnl_amount = s.pnl_percent / 100.0 * s.volume * (
    SELECT b.total_amount / NULLIF(b.volume, 0) FROM trade_records b
    WHERE b.ticker = s.ticker
      AND b.side = 'BUY'
      AND b.user_id IS NOT DISTINCT FROM s.user_id
      AND b.id < s.id
      AND b.id > COALESCE((
          SELECT MAX(p.id) FROM trade_records p
          WHERE p.ticker = s.ticker AND p.side = 'SELL'
            AND p.user_id IS NOT DISTINCT FROM s.user_id AND p.id < s.id), 0)
    ORDER BY b.id DESC LIMIT 1)
WHERE s.side = 'SELL' AND s.pnl_amount IS NULL AND s.pnl_percent IS NOT NULL;

-- trade_executions 도 같은 규칙. exchange 를 조건에 넣어 KIS 행은 건드리지 않는다(별도 결함이며 미해결).
-- user_id 는 NOT NULL REFERENCES users(id) 라 = 로 비교한다.
UPDATE trade_executions s
SET strategy = (
    SELECT b.strategy FROM trade_executions b
    WHERE b.market = s.market
      AND b.side = 'BUY'
      AND b.exchange = s.exchange
      AND b.user_id = s.user_id
      AND b.strategy IS NOT NULL
      AND b.strategy <> 'manual'
      AND b.id < s.id
      AND b.id > COALESCE((
          SELECT MAX(p.id) FROM trade_executions p
          WHERE p.market = s.market AND p.side = 'SELL' AND p.exchange = s.exchange
            AND p.user_id = s.user_id AND p.id < s.id), 0)
    ORDER BY b.id ASC LIMIT 1)
WHERE s.side = 'SELL' AND s.strategy IS NULL AND s.exchange = 'UPBIT';

UPDATE trade_executions s
SET pnl_amount = s.pnl_percent / 100.0 * s.volume * (
    SELECT b.total_amount / NULLIF(b.volume, 0) FROM trade_executions b
    WHERE b.market = s.market
      AND b.side = 'BUY'
      AND b.exchange = s.exchange
      AND b.user_id = s.user_id
      AND b.id < s.id
      AND b.id > COALESCE((
          SELECT MAX(p.id) FROM trade_executions p
          WHERE p.market = s.market AND p.side = 'SELL' AND p.exchange = s.exchange
            AND p.user_id = s.user_id AND p.id < s.id), 0)
    ORDER BY b.id DESC LIMIT 1)
WHERE s.side = 'SELL' AND s.pnl_amount IS NULL AND s.pnl_percent IS NOT NULL
  AND s.exchange = 'UPBIT';

-- fee 는 소급하지 않는다. 이 마이그레이션 이전 행은 0(미기록)으로 남는다.
--
-- 수수료율(trading.round-trip-fee-rate)은 TRADING_ROUND_TRIP_FEE_RATE 로 환경마다 다르게 줄 수 있고
-- 실제로 배포 compose 가 그 변수를 전달한다. SQL 에 비율을 상수로 박으면 기본값이 아닌 환경에서
-- 과거 행이 새 행과 다른 기준으로 기록되고, 원본이 0 이라 되돌릴 근거도 남지 않는다.
-- 0 은 "기록 안 됨" 으로 읽히지만 틀린 추정치는 맞는 값과 구분되지 않는다 — 후자가 더 나쁘다.
-- 현재 이 컬럼을 읽는 코드는 없다. 총 수수료를 집계할 일이 생기면 V20 이전 행을 제외해야 한다.

-- 백필은 전제가 어긋나면 조용히 넘어간다(상관 서브쿼리가 NULL). 그러면 배포 로그에도 /performance 에도
-- 신호가 없어 "고쳐졌다"고 오인하게 된다. 남은 미귀속 수를 세어 알린다.
-- EXCEPTION 이 아니라 WARNING 인 이유: 귀속 후보가 없는 행(수동 매수만으로 만든 포지션)은 정상적으로
-- NULL 로 남아야 하는데, 그걸로 기동을 막으면 deploy.sh 의 자동 롤백이 걸린다.
DO $$
DECLARE
    tr_left  int;
    te_left  int;
BEGIN
    SELECT count(*) INTO tr_left FROM trade_records    WHERE side = 'SELL' AND strategy IS NULL;
    SELECT count(*) INTO te_left FROM trade_executions WHERE side = 'SELL' AND strategy IS NULL AND exchange = 'UPBIT';
    IF tr_left > 0 OR te_left > 0 THEN
        RAISE WARNING 'V20 backfill: 미귀속 매도 잔존 — trade_records %건, trade_executions %건. '
                      '수동 매수만으로 만든 포지션이면 정상이고, 그 외면 페어링 전제가 깨진 것이다.',
                      tr_left, te_left;
    END IF;
END $$;
