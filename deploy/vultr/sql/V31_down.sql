-- V31(증권사 키 테이블)을 손으로 되돌린다. Flyway 는 이 파일을 쓰지 않는다(db/migration 밖에 둔다).
--
-- 경고: 저장된 KIS 앱키·앱시크릿·계좌번호가 모두 함께 사라진다. 되돌린 뒤에는 사용자가 키를 다시 넣어야 한다.
-- V30 이미지로 롤백하는 데에는 이 파일이 필요 없다(V30 이미지는 적용된 V31 을 무시하고 뜬다). 테이블 자체를 없애야 할 때만 쓴다.
--
-- flyway_schema_history 의 V31 행: 테이블만 지우고 이 행을 남기면 다음에 V31 이 든 이미지를 올릴 때 Flyway 가
-- 적용된 것으로 보고 다시 만들지 않아, 테이블이 없는 채로 기동한다. 그래서 같은 트랜잭션에서 이 행을 지운다.
-- V31 이 마지막 적용 버전일 때만 쓴다. V32 이후가 적용돼 있으면 V31 행만 빠진 이력은 Flyway validate 가 기동을 막으므로
-- 아래 DO 블록이 멈춘다.
--
-- 실행: 서버 /opt/app 에서 앱을 멈추고(docker compose stop app) 이 파일을 /tmp 로 올린 뒤, 백업이 성공했을 때만 돌린다
--   ./backup.sh && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V31_down.sql
-- backup.sh 가 실패하면(버킷 접근 실패 시 덤프를 남기지 않는다) 진행하지 않는다.
-- 그다음 V31 이전 이미지로 올린다(APP_VERSION=<V31 이전 SHA> docker compose up -d --pull missing).

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '31') THEN
        RAISE EXCEPTION 'V31_down: V31 이 적용돼 있지 않다';
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
        WHERE installed_rank > (SELECT installed_rank FROM flyway_schema_history WHERE version = '31')
    ) THEN
        RAISE EXCEPTION 'V31_down: V31 이후 마이그레이션이 적용돼 있다 — 이 파일은 V31 이 마지막일 때만 쓴다';
    END IF;
END $$;

DROP TABLE user_broker_keys;

DELETE FROM flyway_schema_history WHERE version = '31';

COMMIT;
