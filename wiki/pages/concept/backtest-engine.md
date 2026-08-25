---
title: 백테스트 엔진 — 구조와 라이브 정합의 한계
category: concept
created: 2026-07-28
updated: 2026-08-25
claim_state: current
verified: 2026-08-25 — ReentryMode 도입(커밋 25750aa·902d781) 후 simulateTrades 전문 재확인, legacy 기본값은 도입 직전 커밋과 trade 단위 동일함을 BacktestLegacyGoldenTest 로 대조
sources:
  - bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt
  - bot/src/main/kotlin/com/trading/bot/engine/IntrabarExitModel.kt
  - bot/src/main/kotlin/com/trading/bot/engine/M1ReplayEngine.kt
---

# 백테스트 엔진

`BacktestEngine.run(strategyName, candles, ticker, config)` — 일봉 시계열에 전략을 돌려 성과를 낸다.

## 구조 (실측)

- **단일 티커, 단일 포지션.** 상태는 `position: Boolean` + `buyPrice` 하나다. 종목 분산·부분 익절·동시 보유가 없다.
- **전액 복리(all-in).** `balance *= (1 + netPnl/100)` — 포지션 사이징 개념이 없다. 초기 잔고 1,000,000.
- **워밍업 50봉**(`MIN_CANDLES`). 시뮬레이션 루프는 `for (i in 50 until size)` 라 51번째 봉부터 신호를 낸다.
  > 정확히 50봉이면 루프가 한 번도 돌지 않아 `buildResult` 가 `chronological[50]` 을 읽고 터졌었다. 가드를 `size <= MIN_CANDLES` 로 고쳐(2026-08-23) 이제 **51봉 미만은 null** 을 반환한다 — 실질 최소 입력이 51봉이라는 사실은 그대로다.
- **look-ahead 방지**: 신호는 봉 `i` 종가까지의 window 로 판단하고, **체결은 다음 봉 `i+1` 시가**로 잡는다. (`reentryMode=LIVE_SAME_BAR` 의 보유상한 재진입만 예외 — 신호가 봉 `i-1` 종가, 체결이 봉 `i` 시가다. 아래 재진입 모델 참조.)
- **비용**: `feeRate × 2 × 100` 을 왕복으로 차감(`config.feeRate` 기본 0.0005). 슬리피지는 별도 모델이 없다.
- **종료 시 미청산 포지션**은 마지막 종가로 `"END"` 청산해 결과에 포함한다.

## 라이브와의 정합

청산 판정은 `IntrabarExitModel` 로 위임돼 **D1 백테와 M1 replay 가 같은 게이트식을 공유**한다. 트레일링 판정과 `maxHoldDays` 보정은 [[exit-gates]] 의 `ExitGates` 를 써서 라이브와 같은 코드다.

**단 평가 우선순위가 다르다** — 라이브는 손절→트레일링, 백테는 트레일링→손절이다. 봉 붕괴 모델에서 라이브 순서를 그대로 쓰면 트레일링 이익 거래가 손절로 오기록되기 때문에 의도적으로 다르게 뒀다. 그래서 **청산 사유 분포를 라이브와 1:1 비교하면 안 된다.**

정합을 위해 명시적으로 처리된 것들:

- **신호 파라미터 분리**: 전략이 신호에서 읽는 config 필드는 `kValue` 뿐이라, 라이브 baseline 에 `kValue` 만 덮어 신호 판단에 넘긴다. 이걸 안 하면 진입 파라미터를 바꿔가며 비교하는 백테가 무의미해진다.
- **트레일링 arm 팬텀 방지**: 이 봉의 high 를 반영하기 **전** peak 으로 arm 을 판정하고, peak 갱신은 다음 봉 판정용으로 미룬다.

## 재진입 모델 (`ReentryMode`)

위 divergence 를 없애려고 `BacktestConfig` 에 재진입 노브가 있다.

| 값 | 의미 |
|---|---|
| `LEGACY_NEXT_BAR` (**기본**) | 위의 2봉 공백. 기존 결과·기존 호출자 보존 |
| `LIVE_SAME_BAR` | `TIME_EXIT` 에 한해 청산 봉 시가에 재진입. `reentryCooldownBars` 로 N봉 지연 |

비자명한 지점:

- **`TIME_EXIT` 에만 적용한다.** 가격게이트 청산(SL/TP/트레일링)은 `IntrabarExitModel` 이 실제 체결가가 아니라 **게이트 임계가**를 내고, 봉의 high/low 를 본 뒤 같은 봉에 사면 look-ahead 다. `TIME_EXIT` 만 청산가가 `bar.open` 이라 안전하다.
- **재진입 신호 window 는 봉 `i` 를 제외**한다(`subList(max(0, i-MIN_CANDLES), i)`). 공용 `window` 는 봉 `i` 를 포함하므로 그대로 재사용하면 봉 D 종가를 보고 봉 D 시가에 사는 셈이 된다.
- **재진입 포지션도 그 봉의 intrabar 게이트를 받는다.** 안 그러면 churn 포지션만 손절·익절 보호가 사라져 편향된다. Upbit 일봉 경계가 `T09:00:00` KST 라 봉 D 는 09:00→09:00 구간이고, 시가 재진입 포지션은 봉 D 전 구간을 실제로 겪으므로 이 평가가 옳다([[upbit-api]]).
- **봉당 재진입 1회** (라이브 `boughtToday` 등가).
- **기본값을 바꾸면 안 된다** — `M1ReplayBiasTest`·`ParameterSweepTest`·`/backtest` 호출자가 `BacktestConfig()` 를 쓰므로 모집단이 조용히 달라진다. `BacktestLegacyGoldenTest` 가 trade 단위로 이를 가둔다.

`holdLimitOnlyWhenProfitable` 은 보유상한 청산을 수익 중일 때만 내는 정책 노브다(#128 2안 측정용, 기본 off).

측정 결과와 그 한계는 [[reset-churn-measurement]].

## 라이브와 다른 점 — 결과 해석 시 주의

- **`useMarketFilter`(50일 MA 아래 매수 차단)는 백테 전용 opt-in** 이며 기본 off 다. 라이브 매수 경로에는 이 필터가 **없다**. 백테에서 켠 채 좋은 결과를 얻고 라이브가 같을 거라 기대하면 안 된다.
- 라이브는 pending reconcile, 잔고 부족, 최소주문금액(5,000원) 같은 현실 제약을 받지만 백테는 받지 않는다([[trading-engine-loop]]).
- **재진입 공백은 반대로 백테가 더 엄격했다.** 라이브의 `boughtToday` 는 거래일 1회 제약이고 09:00 경계에서 풀리므로 리셋 매도 직후 재매수가 가능한데(공백 ~0), 백테 루프는 `if (position) processExit else processEntry` 라 **청산한 봉에서 진입 평가를 아예 하지 않는다** — 청산 봉 `i` → 신호 `i+1` → 체결 `i+2` 로 **2봉 공백이 강제**됐다. 일봉 기준으로 백테가 라이브보다 restrictive 하다는 뜻이고, 이 때문에 "일일리셋 churn 의 비용"을 기본 설정으로는 잴 수 없었다(#128).
- 체결 가정이 "다음 봉 시가에 전량"이다. 호가·유동성·부분체결이 없다.

이 한계들 때문에 멀티종목·포지션 사이징이 필요한 전략(주식 퀀트 등)에는 사실상 신규 엔진이 필요하다 — 재사용은 신호 평가 루프 수준까지다. 전략 개선 파이프라인의 기대치는 [[strategy-evolution-expectations]] 참조.
