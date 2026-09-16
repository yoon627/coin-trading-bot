-- KIS(한국투자증권 국내주식) 자동매매 경로 제거(2026-09-16 결정). 코드·API·화면과 함께 스키마도 걷어낸다.
-- V15~V18·V22 파일은 Flyway 이력 검증 때문에 그대로 두고, 여기서 되돌린다.
--
-- 되돌림: 이 마이그레이션이 적용된 DB 에 V27 이 없는 이미지를 올리면 Flyway validate 로 기동이 실패하므로
-- PR revert 는 복구가 아니다. 그래서 지우기 전에 kis_archive_* 로 복사해 둔다. CREATE TABLE AS 는 identity·PK·
-- 인덱스를 보존하지 않으므로 복구(V28)는 V15/V18 DDL 로 재생성 → INSERT ... OVERRIDING SYSTEM VALUE → identity/
-- sequence 를 MAX(id) 로 재설정하는 순서다. 아카이브는 한 달 뒤 DROP 한다(#203).
CREATE TABLE kis_archive_stock_order_intent AS SELECT * FROM stock_order_intent;
CREATE TABLE kis_archive_stock_position_state AS SELECT * FROM stock_position_state;
CREATE TABLE kis_archive_users_keys AS
    SELECT id AS user_id, kis_app_key, kis_app_secret, kis_cano, kis_acnt_prdt_cd, kis_paper
    FROM users
    WHERE kis_app_key IS NOT NULL OR kis_cano IS NOT NULL; -- 키도 계좌도 없는 행(기본값뿐)은 복구 가치가 없다
CREATE TABLE kis_archive_trade_executions AS SELECT * FROM trade_executions WHERE exchange = 'KIS';

DROP TABLE stock_position_state;
DROP TABLE stock_order_intent;

ALTER TABLE users
    DROP COLUMN kis_app_key,
    DROP COLUMN kis_app_secret,
    DROP COLUMN kis_cano,
    DROP COLUMN kis_acnt_prdt_cd,
    DROP COLUMN kis_paper;

-- bot_state.exchange 컬럼과 (user_id, exchange) unique 는 Upbit 코드가 쓰므로 유지하고 KIS 행만 지운다.
DELETE FROM bot_state WHERE exchange = 'KIS';
-- POST /api/config 가 Exchange.valueOf 로 받은 임의 거래소를 저장했으므로 KIS 행이 있을 수 있다.
DELETE FROM bot_configs WHERE exchange = 'KIS';
-- StockOrderReconciler 가 exchange='KIS' 로 남긴 체결 감사 행 — 코드가 없어진 뒤 조회 화면에 섞이지 않게 제거(위에 아카이브).
DELETE FROM trade_executions WHERE exchange = 'KIS';
