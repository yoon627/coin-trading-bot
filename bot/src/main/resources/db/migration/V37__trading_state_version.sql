-- trading_states 저장을 "읽은 버전 그대로일 때만"으로 바꾼다(#365). 메모리가 읽은 뒤 다른 쓰기가 들어온 행은
-- 덮지 않고 충돌로 실패한다. 앱이 @Version 으로 쓰고 UPDATE 마다 1 씩 올린다.
-- 기존 행은 0 에서 시작한다. 이 칼럼을 모르는 이전 이미지는 INSERT 에 기본값을 쓰고 UPDATE 에서 버전을 올리지 않으므로
-- 롤백 뒤에도 그대로 돈다. 되돌리기는 deploy/vultr/sql/V37_down.sql.
ALTER TABLE trading_states ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
