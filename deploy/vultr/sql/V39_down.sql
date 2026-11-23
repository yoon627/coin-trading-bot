-- V39(쓰지 않는 테이블·trading_states 칼럼 삭제)를 손으로 되돌린다. Flyway 는 이 파일을 쓰지 않는다(db/migration 밖에 둔다).
--
-- #372 이후 이미지로 롤백하는 데에는 이 파일이 필요 없다(그 이미지는 지운 칼럼을 쓰지 않는다).
-- #372 이전 이미지로 내려가야 할 때만 쓴다. 칼럼은 원래 정의와 기본값으로, manual_interventions 는 빈 테이블로 되살린다 —
-- 지운 값은 돌아오지 않는다(배포 전 덤프에만 있다). V21 백업 테이블과 users_bak_20260602 는 되살리지 않는다(쓰는 코드가 없다).
--
-- flyway_schema_history 의 V39 행도 같은 트랜잭션에서 지운다. 그래서 V39 가 든 이미지가 다시 뜨면 V39 가 또 적용돼
-- 되살린 칼럼을 다시 지운다 — 앱을 멈춘 뒤 돌리고, 그다음에는 V39 이전 이미지만 띄운다.
-- V39 가 마지막 적용 버전일 때만 쓴다.
--
-- 실행: 서버 /opt/app 에서 앱을 멈추고(docker compose stop app) 이 파일을 /tmp 로 올린 뒤, 백업이 성공했을 때만 돌린다
--   ./backup.sh && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V39_down.sql
-- 그다음 V39 이전 이미지로 올린다(APP_VERSION=<V39 이전 SHA> docker compose up -d --pull missing).

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '39') THEN
        RAISE EXCEPTION 'V39_down: V39 이 적용돼 있지 않다';
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE installed_rank > (SELECT installed_rank FROM flyway_schema_history WHERE version = '39')
    ) THEN
        RAISE EXCEPTION 'V39_down: V39 이후 마이그레이션이 적용돼 있다 — 이 파일은 V39 가 마지막일 때만 쓴다';
    END IF;
END $$;

-- 정의는 V14·V30·V34·V35 와 같다(그 버전의 down 이 이어서 지울 수 있게).
ALTER TABLE trading_states
    ADD COLUMN halted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN halt_reason VARCHAR(200),
    ADD COLUMN reconcile_failure_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN review_reason VARCHAR(200),
    ADD COLUMN adopt_baseline_volume DOUBLE PRECISION,
    ADD COLUMN sell_clear_prior_volume DOUBLE PRECISION,
    ADD COLUMN sell_clear_volume DOUBLE PRECISION;

CREATE TABLE manual_interventions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    exchange TEXT NOT NULL,
    ticker TEXT NOT NULL,
    action TEXT NOT NULL,
    detail TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_manual_interventions_user_created ON manual_interventions (user_id, created_at);

DELETE FROM flyway_schema_history WHERE version = '39';

COMMIT;
