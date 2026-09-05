---
title: live-semantics-arm — 진입까지 라이브 의미론으로 맞춘 팔로 후보를 재판정
status: done
started: 2026-09-05
updated: 2026-09-05
---

# Goal

[[exit-resolution-verdict-2026-09]] 이 남긴 최대 미해소 축을 닫는다. 그 작업은 **청산만** 라이브에 맞췄고
진입은 백테 의미론(신호 = 봉 종가, 체결 = 다음 봉 09:00 시가)이었다. 라이브는 10초마다 보다가
`현재가 > 당일시가 + 전일레인지×k` 를 넘는 **장중 그 순간** 사고, 그때 참조하는 일봉 window 에는
**형성 중인 오늘 봉이 이미 들어 있다**(`MarketDataStore.addCandle` 이 `openTime` upsert). 즉 백테와 라이브는
**거래 모집단 자체가 다르다**.

240분봉이 있으므로 이걸 look-ahead 없이 재현할 수 있다. 재현한 팔 위에서 라이브 현행 · 변형 A · 후보 E 를
다시 세운다. 음성 결론(바꿀 근거 없음)은 이미 안전하지만, **앞으로 어떤 양성 판정을 하려면 이 팔이 선행되어야 한다**.

라이브 파라미터는 바꾸지 않는다.

# Progress

- 2026-09-05 — worktree 생성(base `main@7e69b22`). 라이브 진입 경로 확인:
  `TradingEngine.runSwing` — `position || boughtToday` 게이트 → `shouldBuyNormalized(storeCandles, currentPrice, props)` → `buy(ticker, state, currentPrice, ...)`.
  `MarketDataStore.addCandle` 이 `openTime` 으로 upsert 하므로 당일 부분봉이 window 에 포함됨을 코드로 확인.
- 2026-09-05 — `LiveSemanticsArm` + 인과성 핀 구현. 미래 봉을 1.5배로 흔들어도 첫 진입 시각·체결가 불변.
- 2026-09-05 — 5창 × 3설정 측정. **거래 모집단이 실제로 달라졌다** — 1년 창 라이브 설정 125건 → **199건(+59%)**.
  **발견 없음**: 5창 중 p<0.05 는 후보당 최대 1개이고 A·E 가 이기는 창이 서로 다르다(부호가 창마다 뒤집힌다).
  부수: 백테가 **라이브 자신의 성적도 과대평가**한다(1년 D1 +21.02 → 이 팔 +13.47). 청산의 83%가 TIME_EXIT.

# Next

없음 — 닫혔다. 라이브 무변경.

# Decisions

## 1) look-ahead 를 피하는 window 구성

각 240분봉 시점에서 전략에 넘기는 일봉 window 는 **직전 봉까지로 누적한 당일 부분봉**을 쓴다.
이 봉의 종가·고가·저가를 그대로 쓰면 체결 시점 이후 정보가 MA·RSI 에 들어가 look-ahead 가 된다.
당일 시가(= 09:00 시가)는 봉 시작 시점에 이미 알려져 있으므로 `calculateTargetPrice` 의 입력으로 안전하다.
그 결과 이 팔은 라이브보다 **약간 보수적**이다(라이브는 tick 마다 갱신된 종가를 쓴다).

## 2) 체결가

돌파 봉의 `max(target, bar.open)`. 라이브는 target 을 넘는 첫 tick 가격에 사므로 그 하한이다.
봉이 target 위에서 열렸으면 시가가, 장중에 넘었으면 target 이 근사치다.

## 3) `boughtToday` 는 09:00 에 풀린다

`DailyResetManager.checkAndReset` 이 거래일 경계에서 `resetDaily` 를 부르므로, 09:00 청산 직후
같은 날 재매수가 가능하다(`LIVE_SAME_BAR` 와 같은 의미). 하루 1회 진입 제약은 그 안에서만 적용된다.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — 라이브 진입 경로(단일 소스)
- `bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataStore.kt` — 당일 부분봉 upsert
- `bot/src/test/resources/backtest/intraday240/` — 240분봉 5창
- `bot/src/test/kotlin/com/trading/bot/engine/IntradayFixtures.kt` · `DateBlockBootstrap.kt` — 재사용

# Blockers

없음.

# Acceptance

1. ✅ 진입·청산이 모두 240분봉에서 라이브 의미론으로 도는 하네스(`LiveSemanticsArm`)가 있고,
   미래 봉 교란 테스트가 look-ahead 부재를 고정한다(무조건 실행).
2. ✅ 라이브 현행 · 변형 A · 후보 E 를 5창에서 세우고 날짜블록 부트스트랩으로 판정했다.
3. ✅ wiki `query/exit-resolution-verdict-2026-09` §9 에 선행 팔들과 나란히 기록 —
   세 계기(D1 · 청산만 일중 · 진입까지 일중) 어디서도 일관되게 이기는 설정이 없다.
4. ✅ wiki 검증 3종 통과.
5. ✅ `./gradlew build` 통과.
6. ✅ 라이브 무변경 — `TradingProperties`·`deploy/` diff 0.

# Deferred

- **공유 잔고 시뮬레이션** — 이 팔도 마켓별 예산이 독립이라고 가정한다. 실제 계좌는 하나이고 상관 0.796 인 날에는
  동시 진입이 제약된다 — 후보의 "같은 날 여러 마켓 동시 진입" 우위가 과대평가된다. (중간)
- **1분봉** — 240분봉 돌파 체결가는 `max(target, 봉 시가)` 하한이다. 라이브는 target 근처에서 산다. (낮음)
