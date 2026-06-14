-- Per-user KIS (한국투자증권) credentials + account identifiers.
-- Mirrors the wired users.upbit_* pattern (user_exchange_keys V12 is an unwired vestige and
-- lacks KIS account-number columns). app_secret is encrypted at rest (SecretsCrypto, AES/GCM).
ALTER TABLE users ADD COLUMN kis_app_key      VARCHAR(512);
ALTER TABLE users ADD COLUMN kis_app_secret   VARCHAR(512);
ALTER TABLE users ADD COLUMN kis_cano         VARCHAR(8);    -- 종합계좌번호 (CANO)
ALTER TABLE users ADD COLUMN kis_acnt_prdt_cd VARCHAR(2);    -- 계좌상품코드 (ACNT_PRDT_CD)
-- Whether this account targets the KIS paper (모의투자) environment. Default true = safe.
ALTER TABLE users ADD COLUMN kis_paper        BOOLEAN NOT NULL DEFAULT true;
