-- 사람이 상태를 바꾼 기록(pending 수동 해제·halt 해제). trading_states 는 덮어쓰고 앱 로그는 배포마다 컨테이너와 함께
-- 사라지므로, 지운 값을 상태 저장과 같은 트랜잭션에 한 행으로 남긴다. 추가 전용이다(고치거나 지우지 않는다).
-- detail 은 action 별 JSON 이다: 바꾸기 전 trading_states 행 전체와 지운 필드.
-- 상태 변경 트랜잭션 안의 삽입이 실패하면 해제도 되돌아가므로 길이 제한 칼럼·CHECK·users FK 를 두지 않는다(V33 과 같은 이유).
--
-- 테이블만 새로 만든다. 이 테이블을 모르는 V34 이미지는 Flyway 기본 설정(ignoreMigrationPatterns '*:future')으로 뜨고,
-- 해제를 기록 없이 한다.
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
