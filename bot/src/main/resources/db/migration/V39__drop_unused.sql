-- 코드가 더 쓰지 않는 테이블·컬럼을 지운다.
--
-- 테이블
--   trade_records_v21_backup, trade_executions_v21_backup (V21) — V21 백필 전 원래 값. 읽는 코드가 처음부터 없었다.
--   users_bak_20260602 — Flyway 밖에서 손으로 만든 users 사본(운영에만 있다).
--   manual_interventions (V35) — pending 수동 해제·halt 해제 기록. 그 API 를 없앴다(#372).
-- 컬럼
--   trading_states.halted·halt_reason·reconcile_failure_count (V14), review_reason (V30),
--   adopt_baseline_volume·sell_clear_prior_volume·sell_clear_volume (V34) — halt·사람 확인·pending 해제의 상태(#372).
--   TradingStateEntity 가 매핑하지 않는다.
--
-- 롤백: 자동 롤백 대상(마지막 정상 배포 이미지)은 #372 이후 코드라 위 테이블·컬럼을 쓰지 않으므로 V39 위에서 뜬다.
-- #372 이전 이미지는 V39 스키마에서 trading_states 를 쓰지 못한다 — 그 이미지로 내려가려면 앱을 멈추고
-- deploy/vultr/sql/V39_down.sql 을 먼저 돌린다(데이터는 되살아나지 않는다. 배포 전 덤프에만 있다).
--
-- CASCADE 를 쓰지 않는다: 예상 밖의 의존 객체가 있으면 DROP 이 실패하고, Postgres 의 트랜잭션 DDL 로
-- 이 마이그레이션 전체가 되돌려져 기동 실패 → 자동 롤백(V39 미적용)으로 끝난다.
-- 백업 테이블은 V39_down 이 되살리지 않으므로 V39 를 다시 적용할 수 있게 IF EXISTS 로 지운다.

-- 무엇을 버렸는지 기동 로그에 남긴다(행 수). 없는 테이블은 건너뛴다.
DO $$
DECLARE
    t TEXT;
    n BIGINT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'trade_records_v21_backup', 'trade_executions_v21_backup', 'users_bak_20260602', 'manual_interventions'
    ] LOOP
        IF to_regclass(t) IS NOT NULL THEN
            EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
            RAISE NOTICE 'V39 drop: % rows=%', t, n;
        END IF;
    END LOOP;
    SELECT count(*) INTO n FROM trading_states
    WHERE halted OR halt_reason IS NOT NULL OR review_reason IS NOT NULL
       OR adopt_baseline_volume IS NOT NULL OR sell_clear_prior_volume IS NOT NULL OR sell_clear_volume IS NOT NULL;
    RAISE NOTICE 'V39 drop: trading_states rows with halt/review/pending-clear values=%', n;
    -- V38 전 이미지가 세던 값이 그대로 남아 있을 수 있다(V38 부터 쓰지 않는다).
    SELECT count(*) INTO n FROM trading_states WHERE reconcile_failure_count <> 0;
    RAISE NOTICE 'V39 drop: trading_states rows with a stale reconcile_failure_count=%', n;
END $$;

DROP TABLE IF EXISTS trade_records_v21_backup;
DROP TABLE IF EXISTS trade_executions_v21_backup;
DROP TABLE IF EXISTS users_bak_20260602;
DROP TABLE manual_interventions;

ALTER TABLE trading_states
    DROP COLUMN halted,
    DROP COLUMN halt_reason,
    DROP COLUMN reconcile_failure_count,
    DROP COLUMN review_reason,
    DROP COLUMN adopt_baseline_volume,
    DROP COLUMN sell_clear_prior_volume,
    DROP COLUMN sell_clear_volume;
