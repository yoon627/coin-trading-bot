-- 코드가 더 쓰지 않는 테이블·컬럼을 지운다.
--
-- 테이블
--   market_tickers, market_candles  (V10) — 시세 DB 저장·보존 서비스는 2026-10-01 에 지웠다. 시세는 메모리(MarketDataStore)에만 있다.
--   strategy_signals                (V11) — 처음부터 쓰는 코드가 없었다.
--   user_exchange_keys              (V12) — 처음부터 미연결. 실제 거래소 키는 users 의 암호화 컬럼이다.
--   bot_configs                     (V12, V13 trade_mode) — 봇 설정 API·엔티티·repository 를 2026-10-01 에 지웠다.
--   kis_archive_*                   (V27) — KIS 제거 때 떠 둔 한 달 보관 아카이브(#203, 2026-10-16 보관 기간 종료).
-- 컬럼
--   trading_states.rungs_filled·last_action_price·flat_peak·pending_buy_trigger_price (V23)
--     — 2026-10-01 에 지운 적립 프로파일의 장부. TradingStateEntity 가 매핑하지 않는다(insert 는 DB 기본값).
--     같은 V23 의 pending_buy_prior_volume·pending_sell_trigger_price·pending_sell_prior_volume 은 스윙이 쓰므로 남긴다.
--
-- 롤백: 이 파일은 되돌릴 수 없다(destructive DDL). 자동 롤백 대상인 직전 이미지는 2026-10-01 이후 코드라
-- 위 테이블·컬럼을 쓰지 않으므로 롤백해도 기동한다. 데이터 복구는 배포 전에 뜬 pg_dump 로만 가능하다 —
-- 절차는 이 PR 설명과 wiki persistence-schema 의 V29 행.
--
-- CASCADE 를 쓰지 않는다: 예상 밖의 의존 객체가 있으면 DROP 이 실패하고, Postgres 의 트랜잭션 DDL 로
-- 이 마이그레이션 전체가 되돌려져 기동 실패 → 자동 롤백(V29 미적용)으로 끝난다. 조용히 더 지우는 것보다 낫다.

-- #203: 무엇을 버렸는지 기동 로그에 남긴다(행 수). 없는 테이블은 건너뛴다.
DO $$
DECLARE
    t TEXT;
    n BIGINT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'market_tickers', 'market_candles', 'strategy_signals', 'user_exchange_keys', 'bot_configs',
        'kis_archive_stock_order_intent', 'kis_archive_stock_position_state',
        'kis_archive_users_keys', 'kis_archive_trade_executions'
    ] LOOP
        IF to_regclass('public.' || t) IS NOT NULL THEN
            EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
            RAISE NOTICE 'V29 drop: % rows=%', t, n;
        END IF;
    END LOOP;
END $$;

DROP TABLE IF EXISTS market_tickers;
DROP TABLE IF EXISTS market_candles;
DROP TABLE IF EXISTS strategy_signals;
DROP TABLE IF EXISTS user_exchange_keys;
DROP TABLE IF EXISTS bot_configs;

DROP TABLE IF EXISTS kis_archive_stock_order_intent;
DROP TABLE IF EXISTS kis_archive_stock_position_state;
DROP TABLE IF EXISTS kis_archive_users_keys;
DROP TABLE IF EXISTS kis_archive_trade_executions;

ALTER TABLE trading_states
    DROP COLUMN IF EXISTS rungs_filled,
    DROP COLUMN IF EXISTS last_action_price,
    DROP COLUMN IF EXISTS flat_peak,
    DROP COLUMN IF EXISTS pending_buy_trigger_price;
