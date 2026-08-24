---
title: daily-reset-counterfactual — 백테 재진입 모델 보정 후 #128 일일리셋 반사실 측정
status: in_progress
started: 2026-08-24
updated: 2026-08-24
---

# Goal

`BacktestEngine` 이 라이브의 "일일 리셋 후 0공백 재매수"를 재현하도록 재진입 모델을 보정하고,
그 위에서 반사실(재진입 쿨다운 0/1/N × `maxHoldDays`)을 실데이터 fixture 로 측정해
GitHub #128 개선안 결정 근거를 만든다. **라이브 전략 코드는 이번 범위가 아니다.**

# Progress

- 2026-08-24 Explore 완료. 라이브 메커니즘·백테 divergence 확정, 범위 2건 사용자 확정, draft plan 작성.

# Next

plan-reviewer + architecture-reviewer 검토 → 지적 반영 → TDD Red(재진입 갭 회귀 테스트).

# Decisions

1. **접근 = 백테 재진입 모델 보정 후 스윕** (사용자 선택 2026-08-24).
   이유: 현 `BacktestEngine.simulateTrades` 는 `if (position) processExit else processEntry` 라
   청산한 봉에서 진입 평가를 하지 않는다 → 청산 봉 `i` → 신호 `i+1` → 체결 `i+2` = **2봉 강제 공백**.
   라이브는 09:00 리셋 매도 후 ~10초 뒤 재매수(#128 KRW-SOL 0.0h) = **0공백**.
   즉 현 백테 baseline 은 이미 #128 1안(쿨다운)에 가까워, `maxHoldDays` 만 바꾸는 스윕으로는
   이슈가 지목한 비용을 **측정 자체가 불가능**하다.

2. **종점 = 반사실 결과까지** (사용자 선택 2026-08-24).
   라이브 변경(쿨다운 도입 / 리셋 대상 한정 / 리셋 제거)은 결과를 보고 별도 worktree 에서 결정·구현.

3. **재진입 모델 = same-bar 재진입, 체결가 = 청산가.**
   라이브는 청산 후 다음 tick(~10초)에 매수 평가를 돌리므로 재진입가 ≈ 청산가다.
   TIME_EXIT 은 청산가가 `bar.open` 이라 재진입도 `bar.open` — #128 이 관찰한 "같은 시각 재매수"와 정확히 일치.
   신호 window 는 기존 진입 규약 그대로 **직전 봉 종가까지**(라이브도 09:00 시점엔 당일 봉 미형성이라 동일).

4. **라이브의 "거래일 1회 진입" 제약을 모델에 유지.** `boughtToday` 는 매수 시 `true`,
   `resetDaily` 에서만 해제되므로 라이브는 거래일당 재진입 1회다. 백테 루프는 봉당 1회 진입이라 자연히 충족.

5. **`reentryCooldownBars` 기본값 = 0 (라이브 정합).**
   `BacktestConfig` 의 명시 계약이 "디폴트는 라이브와 정합"(`BacktestEngineTest.config defaults match live trading defaults`)이므로
   라이브가 0공백인 이상 기본값도 0이어야 한다.
   ⚠️ **이건 `/backtest` public API 의 동작 변경**이다 — 기존 백테 결과가 달라진다. README·wiki 동기화 필수.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt` — `simulateTrades` 루프가 변경 본체. `BacktestConfig` 에 필드 추가.
- `bot/src/main/kotlin/com/trading/bot/engine/IntrabarExitModel.kt` — 청산 판정. **변경 없음** 예상(청산가를 재진입가로 재사용만).
- `bot/src/main/kotlin/com/trading/bot/api/StrategyController.kt` — `/backtest` 요청 DTO·검증. 새 파라미터 노출 여부 결정 필요.
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt` — 실데이터 fixture 로더(BEAR 8마켓 / BULL 4마켓, paired, in/out-of-sample).
- `bot/src/test/kotlin/com/trading/bot/engine/ParameterSweepTest.kt` — 수동 스윕 하네스 패턴(`@EnabledIfEnvironmentVariable`, `build/reports/*.md`).
- `bot/src/test/kotlin/com/trading/bot/engine/M1ReplayBiasTest.kt` — 통계 보고 패턴(N·±CI 병기, 표본 미달 시 유보).
- `bot/src/main/kotlin/com/trading/bot/engine/DailyResetManager.kt` — 라이브 리셋 판정(참조 전용, 무변경).
- `wiki/pages/concept/backtest-engine.md` — 재진입 갭 서술 보정 대상.
- `wiki/pages/concept/exit-gates.md` — 라이브/백테 순서 차이 표에 재진입 갭 추가 대상.

# Acceptance

| # | 무엇이 충족되나 | 어떻게 검증 | 통과 기준 |
|---|---|---|---|
| A1 | 백테가 라이브 0공백 재진입을 재현한다 | 신규 단위테스트: TIME_EXIT 난 봉에서 신호 true 면 같은 봉 `open` 에 재진입 | 재진입 trade 의 `buyIndex` == 청산 trade 의 `sellIndex`, `buyPrice` == 청산가 |
| A2 | 쿨다운 N 이 실제로 N봉 막는다 | `reentryCooldownBars=1,2` 회귀테스트 | 재진입 `buyIndex` >= 청산 `sellIndex` + N |
| A3 | 기존 백테 동작 회귀 없음 | `./gradlew :bot:test` 전체 | 기존 테스트 전부 green (기본값 변경으로 깨지는 것은 의도된 갱신임을 명시) |
| A4 | 반사실이 실데이터로 측정된다 | 신규 스윕 테스트(`RUN_*=true` 수동) 를 BEAR/BULL fixture 에 실행 | `build/reports/` 에 쿨다운 0/1/2/3 × maxHoldDays 1/2/3/999 표 생성, 마켓별·국면별 분해 포함 |
| A5 | 결론이 표본 한계와 함께 보고된다 | 리포트 본문 | 마켓 N·국면별 부호 일치 여부 명시, 상관 0.49(실효 표본 ~2) 한계 재확인, 단정 금지 |
| A6 | 문서 동기화 | `wiki/verify.sh`·`check_links.py`·`smoke.sh` + README 확인 | wiki 3종 통과, `backtest-engine`·`exit-gates` 갱신, `/backtest` 계약 변경 시 README 반영 |

# Blockers

없음.

# Deferred

- `wiki/pages/concept/backtest-engine.md` 의 "라이브는 boughtToday 제약을 받지만 백테는 받지 않는다" 서술이
  불완전하다 — 일봉 기준으로는 백테가 **더** restrictive(2봉 공백 강제)다. (경미·문서) → A6 에서 함께 교정.
- 라이브는 TIME_EXIT 뿐 아니라 STOP_LOSS/TAKE_PROFIT/트레일링 청산 후에도 **같은 거래일 재진입**이 가능하다
  (`boughtToday` 는 09:00 에 이미 해제됨). #128 은 DAILY_RESET 만 다루지만 동일 churn 이 다른 사유에도
  존재할 수 있다. (중간·전략) → 이번 스윕 결과에 사유별 분해를 넣어 관찰만 하고, 판단은 별도 이슈.

# Review Disposition

(리뷰 후 기록)

# Workflow Findings

(없음)
