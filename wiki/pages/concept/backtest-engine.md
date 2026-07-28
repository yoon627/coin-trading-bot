---
title: 백테스트 엔진 — 구조와 라이브 정합의 한계
category: concept
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — BacktestEngine.kt:53-171 정독 (단일 position 필드·balance 복리·fill 규칙 실측)
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
- **워밍업 50봉**(`MIN_CANDLES`). 그 이전 구간은 신호를 내지 않는다.
- **look-ahead 방지**: 신호는 봉 `i` 종가까지의 window 로 판단하고, **체결은 다음 봉 `i+1` 시가**로 잡는다.
- **비용**: `feeRate × 2 × 100` 을 왕복으로 차감(`config.feeRate` 기본 0.0005). 슬리피지는 별도 모델이 없다.
- **종료 시 미청산 포지션**은 마지막 종가로 `"END"` 청산해 결과에 포함한다.

## 라이브와의 정합

청산 판정은 `IntrabarExitModel` 로 위임돼 **D1 백테와 M1 replay 가 같은 게이트식을 공유**한다. 그리고 게이트식 자체는 [[exit-gates]] 의 `ExitGates` 를 쓴다 — 라이브(`PositionManager`)와 같은 코드다.

정합을 위해 명시적으로 처리된 것들:

- **신호 파라미터 분리**: 전략이 신호에서 읽는 config 필드는 `kValue` 뿐이라, 라이브 baseline 에 `kValue` 만 덮어 신호 판단에 넘긴다. 이걸 안 하면 진입 파라미터를 바꿔가며 비교하는 백테가 무의미해진다.
- **트레일링 arm 팬텀 방지**: 이 봉의 high 를 반영하기 **전** peak 으로 arm 을 판정하고, peak 갱신은 다음 봉 판정용으로 미룬다.

## 라이브와 다른 점 — 결과 해석 시 주의

- **`useMarketFilter`(50일 MA 아래 매수 차단)는 백테 전용 opt-in** 이며 기본 off 다. 라이브 매수 경로에는 이 필터가 **없다**. 백테에서 켠 채 좋은 결과를 얻고 라이브가 같을 거라 기대하면 안 된다.
- 라이브는 `boughtToday` 당일 1회 진입 제약, pending reconcile, 잔고 부족, 최소주문금액(5,000원) 같은 현실 제약을 받지만 백테는 받지 않는다([[trading-engine-loop]]).
- 체결 가정이 "다음 봉 시가에 전량"이다. 호가·유동성·부분체결이 없다.

이 한계들 때문에 멀티종목·포지션 사이징이 필요한 전략(주식 퀀트 등)에는 사실상 신규 엔진이 필요하다 — 재사용은 신호 평가 루프 수준까지다. 전략 개선 파이프라인의 기대치는 [[strategy-evolution-expectations]] 참조.
