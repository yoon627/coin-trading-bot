---
title: trailing-shadow — 변형 A 전향 검증의 설계·소요기간 실측·그림자 관측기
status: done
started: 2026-09-05
updated: 2026-09-05
---

# Goal

[[trailing-arm-finding-2026-09]] 이 사전고정으로 통과시킨 변형 A(`trailingStopPct 2.0→1.5`, `trailingArmPct 3.0→0`)의
**전향 검증**을 설계하고 계기를 넣는다. 승격은 사람 승인 사항이며([[strategy-evolution-expectations]])
전향 검증의 목적은 통계력이 아니라 **백테가 못 재는 것 제거**다.

라이브 파라미터는 바꾸지 않는다.

# Progress

- 2026-09-05 — worktree 생성(base `main@afd55a5`). 라이브 청산 경로 확인:
  `TradingEngine.decideSell` → `PositionManager.checkTrailingStop` → `ExitGates.isTrailingStopTriggered`.
  **게이트가 순수 함수**라 같은 tick 에서 후보 파라미터로 한 번 더 평가해도 부작용이 없다(peak 갱신은 설정 무관).
- 2026-09-05 — **승격 규약 확인**: [[strategy-evolution-expectations]] 는 "별도 티커 실돈 파일럿"을 이미 **폐기**했다
  (전량매도 구조상 같은 마켓에 두 포지션 불가 → 다른 마켓 배정은 마켓 효과가 됨). 그림자 평가는
  **같은 포지션의 청산만 두 번 평가**하므로 그 반론을 우회한다 — 이 설계의 유일한 존재 이유다.
- 2026-09-05 — **소요기간 실측**(`TrailingShadowPowerTest`, 12창 1,351 짝지은 거래):
  청산이 갈린 거래 **405건(30.0%)**, 갈린 거래의 차이 평균 **+0.359%p**·표준편차 2.102%p.
  80% power 필요 표본 **270건**, 라이브 환산 갈린 거래 **연 58건** → **필요 기간 약 4.7년**.
- 2026-09-05 — 사용자 판단으로 방향 확정(아래 Decisions 3) 후 구현: `ShadowExitProperties`(기본 off) ·
  `ShadowExitObserver` · V24 `shadow_exit_observation` · `TradingEngine`/`UserTradingManager` 배선 · 단위테스트 5건.
  **라이브 파라미터 무변경.**

# Next

없음 — PR #171 로 닫혔다. 관측기는 들어갔고 **기본 off** 다. 다음 액션은 사람이 `trading.shadow-exit.enabled=true` 로 켜는 것이며
배포 설정 변경이라 이 작업 범위 밖이다. 켠 뒤 판정은 아래 `# Acceptance` 4 의 사전고정을 따른다.

# Decisions

## 1) 전향 검증은 "그림자 청산 평가"여야 한다 (실돈 파일럿 아님)

A 는 **청산 게이트만** 바꾸므로 진입이 동일하다. 따라서 같은 포지션에서 라이브 청산과 A 청산을 **짝지어** 비교할 수 있고,
자본을 나누거나 다른 마켓에 배정할 필요가 없다. 규약이 폐기한 파일럿의 결함(마켓 효과)이 구조적으로 발생하지 않는다.

## 2) 그러나 이 경로로는 **수익 우위를 4.7년 안에 판정할 수 없다**

위 실측이 그렇다. 규약이 이미 예고한 지점이다 — *"더 빠른 성장을 원하면 루프를 정교화할 게 아니라
**표본을 늘리는 방향**(마켓 폭·투자 사이즈)을 바꿔야 하고, 그건 루프가 아니라 사람의 결정"*.

## 3) 방향 확정 (2026-09-05 사용자) — 모델 검증 + 안전 감시, 승격은 그 통과 후

수익 판정은 포기한다. 그림자는 **"모델이 말한 청산가가 실제 tick 에서 실현되는가"** 하나만 잰다.
이 스레드가 무너뜨린 것이 정확히 청산 모델이므로([[exit-resolution-verdict-2026-09]]), 그 축의 검증이 승격의 선행조건이다.

## 4) 무엇을 재는가 — 모델 과대추정폭 하나

백테는 트레일링 체결가를 `peak × (1 − trail/100)` 임계선으로 잡는다. 실제로 그 게이트를 발동시키는 tick 가격은
그 **이하**다(발동 조건이 `drop ≥ trail`). 그 차이가 모델 과대추정폭이고, 진입가 대비 %p 로 잰다.

⚠️ **실행 슬리피지(결정가 vs 실체결가)는 이 관측에 없다.** 라이브 기록이 요청값 기준이라(#105) 신뢰할 수 없기
때문이며, 따라서 여기서 나오는 값은 전체 마찰의 **하한**이다.

# Key Files

- `common/src/main/kotlin/com/trading/common/config/ShadowExitProperties.kt` — 기본 off·파라미터
- `bot/src/main/kotlin/com/trading/bot/engine/ShadowExitObserver.kt` — 관측기(계산·기록 전용)
- `bot/src/main/resources/db/migration/V24__create_shadow_exit_observation.sql` — 관측 테이블
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — `runSwing` 배선(라이브 판정 뒤)
- `bot/src/test/kotlin/com/trading/bot/engine/TrailingShadowPowerTest.kt` — 소요기간 실측. `RUN_SHADOW_POWER=true`

# Blockers

없음.

# Acceptance

**4 는 관측을 켜기 전에 커밋하는 사전고정이다. 데이터를 본 뒤 고치지 않는다.**

1. ✅ 관측기가 라이브 매매 경로에 **계산·기록만** 얹는다 — 순수 게이트 재평가, 모든 예외 흡수,
   저장소 없으면 켜지지 않음, 기본 off.
2. ✅ 포지션당 관측 1건(첫 발동만) — 이후는 두 팔이 갈라져 짝지은 비교가 아니다. 테스트로 고정.
3. ✅ `modeled ≥ observed` 불변식을 테스트가 가둔다 — 깨지면 "모델 과대추정폭" 해석 자체가 무너진다.
4. **판정(관측 시작 후)**: `overshoot = (modeled_exit_price − observed_tick_price) / entry_price × 100` (%p).
   - **통과**: **N ≥ 30** 이고 `mean(overshoot) + 1.96·SE < 0.18%p`.
     0.18 은 A 의 실측 우위(갈린 거래당 +0.359%p)의 **절반**이다 — 모델 오차가 우위의 절반도 못 먹어야 통과다.
   - **중단·재검토**: **N ≥ 15** 에서 `mean(overshoot) > 0.359%p` — 모델 오차가 우위 전부를 먹는다.
   - 그 사이면 **판정 유보**, 관측 계속.
5. ✅ V24 + wiki `concept/persistence-schema` 동기화, `./gradlew build` 통과(실행 976 / skip 19 / 실패 0), wiki 검증 3종 통과.
6. ✅ 라이브 파라미터 무변경 — `TradingProperties`·`deploy/` diff 0.

조회:
```sql
SELECT count(*) AS n,
       avg((modeled_exit_price - observed_tick_price) / entry_price * 100) AS mean_overshoot_pp,
       stddev((modeled_exit_price - observed_tick_price) / entry_price * 100) AS sd_pp
FROM shadow_exit_observation
WHERE trailing_stop_pct = 1.5 AND trailing_arm_pct = 0;
```

# Deferred

- **실행 슬리피지 측정** — 결정가 vs 실체결가. #105 계열 정합이 선행돼야 한다. (높음)
- **관측 켜기** — 배포 설정(`trading.shadow-exit.enabled=true`). 사람이 결정한다. (사용자)
- **마켓 폭 확대** — 8 → 20+ 면 수익 판정 기간이 4.7년 → 약 1.9년. 유니버스 확대는 별개의 리스크 결정이다. (중간)
- **TDD 순서 미준수** — 관측기는 구현 후 테스트를 붙였다(§7 위반). 기록으로 남긴다.
