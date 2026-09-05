-- 변형 A(트레일링 1.5%/arm 0)의 **모델 검증**용 관측 기록.
--
-- 왜 필요한가: wiki `query/trailing-arm-finding-2026-09` 이 미관측 7국면에서 A 를 사전고정으로 통과시켰지만,
-- 그 근거는 240분봉 replay 다. 이 스레드가 무너뜨린 것이 정확히 **청산 모델**이므로(일봉은 경로 의존 게이트를
-- 라이브와 다르게 잰다), 승격 전에 "모델이 말한 청산가가 실제 10초 tick 에서 실현되는가"를 실물로 확인해야 한다.
--
-- 수익 우위 판정용이 아니다 — 그건 현재 거래 빈도로 약 4.7년이 걸린다(`TrailingShadowPowerTest` 실측).
-- 여기서 재는 것은 **모델 과대추정폭** 하나다: 모델 청산가(peak × (1−trail/100)) vs 실제로 그 게이트를
-- 발동시킨 tick 가격. 라이브는 이 기록으로 아무 동작도 바꾸지 않는다(계산·기록 전용).
CREATE TABLE shadow_exit_observation (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES users(id),
    ticker              VARCHAR(20) NOT NULL,
    -- 관측 대상 파라미터. 나중에 다른 후보를 얹어도 어느 설정의 관측인지 남는다.
    trailing_stop_pct   DOUBLE PRECISION NOT NULL,
    trailing_arm_pct    DOUBLE PRECISION NOT NULL,
    entry_price         DOUBLE PRECISION NOT NULL,
    -- 그림자 게이트가 처음 발동한 시점의 값들.
    peak_price          DOUBLE PRECISION NOT NULL,
    -- 모델이 체결됐다고 보는 가격 = peak × (1 − trailing_stop_pct/100). 백테가 쓰는 값이다.
    modeled_exit_price  DOUBLE PRECISION NOT NULL,
    -- 실제로 그 게이트를 발동시킨 tick 가격. modeled 이하이고, 그 차이가 모델 과대추정폭이다.
    observed_tick_price DOUBLE PRECISION NOT NULL,
    fired_at            TIMESTAMPTZ NOT NULL,
    -- 라이브가 실제로 청산한 시점·사유. 그림자 발동 이후 두 팔이 갈라지므로 맥락으로만 쓴다.
    live_exit_price     DOUBLE PRECISION,
    live_exit_reason    VARCHAR(32),
    live_exit_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 관측은 포지션당 1건이라 양이 적다(라이브 환산 연 수십 건). 조회는 파라미터·기간 단위로 한다.
CREATE INDEX idx_shadow_exit_observation_fired
    ON shadow_exit_observation (trailing_stop_pct, trailing_arm_pct, fired_at);
