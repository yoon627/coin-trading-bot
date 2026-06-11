---
title: chart-exit-strategy — 전략별 청산 신호(shouldSell override) #5
status: done
started: 2026-06-03
updated: 2026-06-03
---

# Goal

청산 차트화 backlog#5: 평균회귀 계열 3전략(RsiBounce/BollingerBounce/MeanReversion)의 chartExit
청산 신호를 default 데드크로스(5/20)에서 전략별 `shouldSell` override 로 정교화.
MacdCross(완료, MACD 하향교차)에 이어 나머지를 정의. backward-compat: `chartExitEnabled` 기본 off 유지.

# Progress

- 2026-06-03: dlc 착수(structural 예비판정). worktree chart-exit-strategy(27b8eee 기반) 생성 + trading-logic 정리 완료.
  Explore 완료(인터페이스·3전략·MacdCross 패턴·Indicators·호출부 BacktestEngine/TradingEngine). 청산 철학 방향 = 사용자 결정 대기.
- 2026-06-03: Candle/Ohlc(`close`=tradePrice 매핑)·테스트 패턴(bot/strategy, `Candle(market,tradePrice)` 빌더) 확인.
  사용자 **'평균·중립 회귀(candles 종가)'** 선택 → 3전략 청산식 확정(Decisions/Design). 규모 medium 재판정(override 추가, public API 불변, arch 변경 없음 → arch-reviewer skip). plan-reviewer 대기.
- 2026-06-03: **plan-reviewer(Claude+codex 0.136.0) CONDITIONAL GO.** blocker 3(B1 미완성봉시점/B2 RSI50미트리거/B3 takeProfit흡수) + major M1(BB.middle==MA20 산술동일). 신호 산술정합성·size가드·rollback·backward-compat 안전 확정. 구현 착수 전 Decisions 명시 또는 보정 필요 → Blockers. 사용자 결정 대기.
- 2026-06-03: /compact 후 재개(court 토큰 오류 다발=긴 컨텍스트 추정, 압축으로 완화). sync 점검: 코드 0줄(git clean, origin/main==HEAD), Key Files 전부 실재. plan-reviewer disposition(B1·B2·B3 수용 / M1 수용 / M4 반영) Blockers 에 확정 기록 → 사용자 확인 후 TDD Red.
- 2026-06-03: 사용자 disposition 승인 → TDD Red(9 테스트, true 3개 default 데드크로스로 실패 확인) → 3전략 shouldSell override 구현 → 전체 `./gradlew test` BUILD SUCCESSFUL(신규 9 + 회귀). M4 재판정: 기존 `BacktestEngineTest.triggers CHART_EXIT when enabled` 가 chartExitEnabled→shouldSell 가상디스패치 배선을 이미 커버 → 신규 override 는 동일 경로(Kotlin 가상디스패치 언어보장) + 단위테스트로 로직 검증 → 별도 50+캔들 통합 생략(중복·ROI). code-reviewer 진행 중.
- 2026-06-03: code-reviewer(Claude 단독; codex Windows sandbox 에러로 미산출) NEEDS DISCUSSION — Major 2 + Minor 2. Major1(RSI 비대칭) 재검증 후 수용(누적Wilder 정의상 정확 + 기존관례 + 운영 N≥21), Major2(테스트 극단값)·Minor 2건 fix. 보강 테스트 3개(RSI 중간값 교차 + BB/MR prev-위 분기) 추가 → 재검증 BUILD SUCCESSFUL. → code-simplifier.
- 2026-06-03: code-simplifier 단순화 0건(MacdCross/GoldenCross 패턴 일치, 죽은코드·불필요변수 없음; 테스트 빌더 중복은 의도적 독립성으로 제안만). 최종검증 `./gradlew compileKotlin test` BUILD SUCCESSFUL(전체 회귀 포함). 구현·리뷰·검증 완료(미커밋), 사용자 push/PR 결정 대기.
- 2026-06-03: 커밋 5c594b6 → push → **PR #23 squash 머지(merge commit b98cfcb). #5 완료.**

# Next

(없음 — PR #23 머지로 #5 완료. 후속: prod 활성화 전 백테스트 효과·조기청산 빈도 검증 / B 숏 트랙은 별도 plan.)

# Decisions

- (Explore 확정) 3전략 모두 현재 `shouldSell` override 0 → default 데드크로스(5/20) 상속. MacdCross 만 override(하향교차).
- (Explore 확정) 청산 호출부 시점:
  - 백테스트 `BacktestEngine.processExit:119` — `shouldSell(window, currentPrice=봉i종가, config)`.
    `currentPrice == window[0].close`, look-ahead 없음 → 백테스트 내 일관.
  - 라이브 `TradingEngine.evaluateChartExit:235/242` — `shouldSellNormalized(storeD1, currentPrice=실시간ticker)`.
    `candles[0]`=미완성 오늘봉 → currentPrice(실시간) vs 미완성봉 지표 시점 불일치.
- (분석) `currentPrice` 직접 비교 청산(BB upper, MA20 복귀)은 라이브에서 미완성봉+실시간가 whipsaw 위험.
  candles 종가 기반 교차(MacdCross 패턴, currentPrice 미사용)가 백테스트=라이브 일관 → **candles-only 권장**.
- **청산 신호 방향 확정(사용자 선택: 평균·중립 회귀, candles 종가 기반, currentPrice 미사용)**:
  - RsiBounce: `prevRsi>=50 && curRsi<50` (RSI 50 중립선 하향교차 = 반등 모멘텀 소진).
  - BollingerBounce: `candles[1].close<prevBb.middle && candles[0].close>=bb.middle` (종가 middle 상향복귀).
  - MeanReversion: `candles[1].close<prevMa20 && candles[0].close>=ma20` (종가 MA20 상향복귀).
  - 셋 다 MacdCross 와 동일 candles-only 교차 패턴(prev=drop(1)/cur 비교) → 라이브=백테스트 일관(whipsaw 회피).
  - **쟁점**: `bb.middle`(BB SMA20) == `calculateMa(,20)` 수치 동일 → Bollinger·MeanReversion 청산식 사실상 같음.
    의도적(각 전략 자기 지표 맥락 유지)이나 중복 — plan-reviewer 판단 대상.
- 참고: 백업 done plan `.claude/plans/2026-06-01-trading-logic/` #Backlog #2 Design 의 대칭반대안은 plan-review 가
  "약한반등 미트리거(RSI70)·비대칭(BB upper)·노이즈(MA20)·시점불일치"로 부적합 판정 → 본 plan 에서 재설계.

# Design (전략별 shouldSell override)

전부 candles 종가 기반(currentPrice 파라미터 미사용), prev=drop(1)/cur 교차. 가드는 기존 shouldBuy 와 동일.

- **RsiBounce** (가드 `size<16` = RSI14+drop1): `curRsi=calculateRsi(candles,14)`, `prevRsi=calculateRsi(candles.drop(1),14)` → `prevRsi>=50.0 && curRsi<50.0`.
- **BollingerBounce** (가드 `size<21` = BB20+drop1): `bb/prevBb=calculateBollingerBands(..,20,2.0)?:return false` → `candles[1].close<prevBb.middle && candles[0].close>=bb.middle`.
- **MeanReversion** (가드 `size<21` = MA20+drop1): `ma20=calculateMa(candles,20)`, `prevMa20=calculateMa(candles.drop(1),20)`, `if(ma20<=0||prevMa20<=0) return false` → `candles[1].close<prevMa20 && candles[0].close>=ma20`.

# Test Strategy (TDD, bot/src/test/.../strategy/)

- RsiBounceTest +shouldSell: RSI50 하향교차 true / 상향·미교차 false / size<16 false. **결정적 시나리오**(MacdCrossTest 의 조건부 assert 약점 지양).
- BollingerBounceTest +shouldSell: 종가 middle 상향복귀 true / 미복귀(아래 유지) false / size<21 false.
- MeanReversionTest +shouldSell: 종가 MA20 상향복귀 true / 미복귀 false / size<21 false.
- 회귀: TradingStrategyDefaultSellTest(default 데드크로스 유지 전략) + BacktestEngineTest + TradingEngineTest 통과.

# Impact / Rollback

- public API 변경 없음(shouldSell override 추가, 시그니처 불변). 7전략 중 4개 override(Macd+신규3), 3개 default(Golden/Combined/Volatility).
- `chartExitEnabled=false` 기본 → 영향 0. 호출부(BacktestEngine.processExit, TradingEngine.evaluateChartExit) 무변경(가상 디스패치).
- Rollback: override 제거 → default 데드크로스 복귀. 전략별 독립.

# Key Files

- `common/strategy/TradingStrategy.kt:33-49` — shouldSell default(checkDeadCross 5/20), shouldSellNormalized 위임.
- `common/strategy/{RsiBounce,BollingerBounce,MeanReversion}.kt` — override 대상(현재 shouldBuy 만 정의).
- `common/strategy/MacdCross.kt:33-45` — override 참고 패턴(candles-only 교차, currentPrice 미사용).
- `common/strategy/Indicators.kt` — calculateRsi/Ma/BollingerBands(List<Ohlc>), candles[0]=최신, 내부 reversed.
- `bot/engine/BacktestEngine.kt:101-131` processExit — shouldSell 호출(currentPrice=봉종가, 우선순위 stopLoss>trailing>takeProfit>chartExit>TIME_EXIT).
- `bot/engine/TradingEngine.kt:195-243` decideSell/evaluateChartExit — shouldSellNormalized(currentPrice=실시간, 우선순위 동일).

# Blockers

plan-reviewer(CONDITIONAL GO) 제기 항목 = "구현 전 Decisions 명시로 해소" 요구. **처리 방침(disposition) 확정 — 사용자 최종 확인 후 구현 착수**:
- **B1 미완성봉 시점** → 수용. 라이브 `candles[0]`=미완성 오늘봉으로 평가하나 candles-only 교차라 currentPrice whipsaw 는 회피. 봉 미완성 자체는 남음(확정봉 보정 안 함 — 복잡도 회피). 게이트: prod 활성화 전 백테스트 효과 검증 필수.
  - 보강(code-review Minor): 미완성봉 종가가 장중 middle/MA 를 살짝 넘으면 즉시 청산 체결(매도 비가역) 가능 — BB/MR 은 단일 종가 조건이라 더 민감. 수용 유지(whipsaw 회피 우선), 백테스트 검증 시 조기청산 빈도를 관찰 항목으로 둠.
- **B2 RSI50 미트리거** → 수용. 약반등(RSI 50 복귀 없이 하락)은 청산 미발동. shouldSell=강반등 모멘텀 소진 청산이 역할, 약반등 손실 보호는 stopLoss 담당(역할 분담).
- **B3 takeProfit 흡수** → 수용. 청산 우선순위 stopLoss>trailing>takeProfit>chartExit 라 takeProfit 선발동 가능. 이익 보호 우선이 정상.
- **M1 BB.middle==MA20 동일** → 수용. BollingerBounce·MeanReversion 청산식 수치 동일. 진입은 차별(BB 밴드폭 vs MA 이탈%), 청산만 동일 — 각 전략 자기 지표 맥락 유지. 코드 주석 명시.
- **M4 통합 테스트** → 재판정: 기존 `BacktestEngineTest.triggers CHART_EXIT when enabled` 가 chartExitEnabled→shouldSell 가상디스패치 배선을 이미 커버 → 신규 override 는 동일 경로(Kotlin 가상디스패치 언어보장) + 단위테스트로 로직 검증 → 별도 50+캔들 통합 생략(중복·ROI).

# Review Disposition (code-reviewer 2026-06-03)

- **Major1 RsiBounce RSI prev/cur 비대칭** → **수용**(false-positive 성격). `calculateRsi` 가 전체구간 누적 Wilder 라 `candles` vs `drop(1)` 은 가장 오래된 봉을 공유 → prev=cur 의 직전 스텝 = 정의상 정확한 "직전 RSI". 리뷰의 "고정윈도 16% 불일치"는 다른 정의와의 비교. shouldBuy·GoldenCross·MacdCross 동일 관례 + 운영 N≥21(백필 200) 라 warm-up 경계 비실효. `RsiBounce.kt` 주석 명시.
- **Major2 테스트 prevRsi=100 극단** → **fix**. `should sell on RSI 50 cross from mid-range`(지그재그 상승 후 급락, prevRsi 50~75·curRsi<50 사전조건 단언) 추가.
- **Minor BB/MR should-not-sell AND 첫 조건 미검증** → **fix**. `should not sell when prev close already above middle/MA20` 각 추가.
- **Minor B1 미완성봉 매도 비가역성** → **fix(문서)**. 위 Blockers B1 에 보강.
- **M1 BB.middle==MA20** → 수용 재확인(리뷰도 타당 판정).
- Codex 병행: Windows sandbox spawn + rg path 에러로 미산출 → Claude code-reviewer 단독(§9 fallback).
