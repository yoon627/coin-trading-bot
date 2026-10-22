-- 증권사 키를 사용자·거래소(KIS_PAPER 모의, KIS 실전)별 한 행으로 둔다. 세 값 모두 SecretsCrypto 암호문('enc:' + base64)이다.
-- 길이 상한은 암호문 기준이다: 평문 n 바이트는 4 + 4 * ceil((n + 28) / 3) 자가 된다(IV 12 + 태그 16).
-- 그래서 평문 상한은 app_key 353, app_secret 737, account_no 158 바이트다.
--
-- 새 테이블만 만든다. 이 테이블을 모르는 V30 이미지는 Flyway 기본 설정(ignoreMigrationPatterns '*:future')으로
-- 적용된 V31 을 무시하고 그대로 뜬다. 테이블을 지워야 할 때만 deploy/vultr/sql/V31_down.sql 을 쓴다.

CREATE TABLE user_broker_keys (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    exchange VARCHAR(10) NOT NULL,
    app_key VARCHAR(512) NOT NULL,
    app_secret VARCHAR(1024) NOT NULL,
    account_no VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_broker_keys_user_exchange UNIQUE (user_id, exchange),
    CONSTRAINT ck_user_broker_keys_exchange CHECK (exchange IN ('KIS_PAPER', 'KIS'))
);
