---
title: 백테스트 엔진 — 구조와 라이브 정합의 한계
category: concept
created: 2026-07-28
updated: 2026-09-23
claim_state: current
verified: 2026-09-23 — 가격게이트 청산 봉 종가 신호 평가(#223): :bot:test 1062건(skip 32) 통과, 재생성 골든 509→558 거래(TP·hold0 간격1 46·SL 1·TRAILING·hold1 7), 가격게이트 뒤 간격 0봉 0건(두 모드; LEGACY 렌더 444→486, TIME_EXIT 뒤 최소 2봉 유지), 같은 봉 금지 가드는 변이 검사로 확인, cooldown 2 == legacy 동등성 유지 · 2026-09-23 — 기본값 LIVE_SAME_BAR 전환(#144): 전체 :bot:test 1061건(skip 32) 통과, 재생성한 default-golden.txt 에서 같은 봉 재진입 24건 전부 직전 TIME_EXIT 뒤(가격게이트 뒤 0건), 거래 444→509(+65 — TIME_EXIT 뒤 간격 0봉 24건·1봉 57건 신설, 나머지는 경로 이동; 가격게이트 뒤 최소 간격은 두 모드 모두 2봉), 명시적 LEGACY 결과는 옛 legacy-golden.txt 와 바이트 동일 · 2026-08-25 — ReentryMode 도입(커밋 25750aa·902d781) 후 simulateTrades 전문 재확인, legacy 기본값은 도입 직전 커밋과 trade 단위 동일함을 BacktestLegacyGoldenTest 로 대조
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
| `LIVE_SAME_BAR` (**기본**, 2026-09-23~) | `TIME_EXIT` 에 한해 청산 봉 시가에 재진입. 재진입 신호가 없으면 그 봉 종가로 통상 진입(`i+1` 체결)도 평가한다. `reentryCooldownBars` 로 N봉 지연 |
| `LEGACY_NEXT_BAR` | TIME_EXIT 뒤 2봉 공백. `LIVE_SAME_BAR` + 쿨다운 2봉과 trade 단위로 같다(`BacktestReentryEquivalenceTest`) |

비자명한 지점:

- **`TIME_EXIT` 에만 적용한다.** 가격게이트 청산(SL/TP/트레일링)은 `IntrabarExitModel` 이 실제 체결가가 아니라 **게이트 임계가**를 내고, 봉의 high/low 를 본 뒤 같은 봉에 사면 look-ahead 다. `TIME_EXIT` 만 청산가가 `bar.open` 이라 안전하다.
- **재진입 신호 window 는 봉 `i` 를 제외**한다(`subList(max(0, i-MIN_CANDLES), i)`). 공용 `window` 는 봉 `i` 를 포함하므로 그대로 재사용하면 봉 D 종가를 보고 봉 D 시가에 사는 셈이 된다.
- **재진입 포지션도 그 봉의 intrabar 게이트를 받는다.** 안 그러면 churn 포지션만 손절·익절 보호가 사라져 편향된다. Upbit 일봉 경계가 `T09:00:00` KST 라 봉 D 는 09:00→09:00 구간이고, 시가 재진입 포지션은 봉 D 전 구간을 실제로 겪으므로 이 평가가 옳다([[upbit-api]]).
- **봉당 재진입 1회** (라이브 `boughtToday` 등가).
- **기본값은 라이브 정합이다(#144).** 근거는 **D1 규약이 라이브보다 하루 늦게 대응한다**는 것 — 신호 봉 `t` 종가 → 체결 `t+1` 시가가 라이브의 `t` 일 장중 돌파 매수이고, TIME_EXIT(`t+2` 시가)가 라이브의 `t+1` 일 09:00 리셋이다. 라이브는 리셋 당일 돌파하면 다시 사므로 그 사건이 곧 "신호 `t+1` 종가 → `t+2` 시가" 청산 봉 재진입이다. `LEGACY_NEXT_BAR` 는 이 합법적 기회를 지운다.
  - **기각한 반론 — "라이브는 09:00 시가에 되살 수 없으니 LEGACY 가 맞다"**: 전제(시가 즉시 재매수 불가)는 맞다 — 목표가가 `당일시가 + k·전일레인지` 이고, 2026-09-08 전의 "공백 0" 재매수는 #209 결함이었다([[exit-gates]], [[reentry-premium-2026-09]] 재진입 중앙값 530분). 그러나 하루 지연 대응에서 청산 봉 시가 재진입은 "리셋 당일 시가 재매수"가 아니라 "리셋 당일 돌파 재매수"에 대응하므로 결론이 따라오지 않는다.
  - 재진입가를 돌파가가 아니라 시가로 놓는 오차([[reentry-premium-2026-09]] ≈0.8%p/TIME_EXIT)는 통상 진입과 체결 규약이 같아 LEGACY 로 바꿔도 줄지 않는다(통상 진입 쪽 오차의 크기는 잰 적이 없다). 재진입 관련 판정의 계기는 여전히 5분봉 `LiveSemanticsArm` 이다.
  - **가격게이트 청산(SL·TP·트레일링·차트) 뒤는 모드와 무관하다(#223, 2026-09-23~).** 같은 봉 재진입은 금지(청산가가 게이트 임계가라 봉 high/low 를 본 뒤 사는 look-ahead)하고, 청산 봉 종가 신호 → 다음 봉 시가 체결은 통상 경로가 평가한다 — 라이브는 `maxHoldDays=1` 에서 게이트 청산이 매수일에만 나 그날은 `boughtToday` 로 막히고 다음 거래일 돌파에 다시 사므로, 하루 지연 대응에서 그것이 간격 1봉이다. 같은 봉에 재진입한 포지션이 그 봉 게이트로 청산돼도 같은 규칙이다.
  - 한계 1: **보유상한 봉에서 시가로 판정된 게이트 청산**(골든의 TRAILING_STOP 전부가 hold=1)은 라이브에선 09:00 리셋 시점이라 대응 재매수가 청산 봉(같은 봉)인데 D1 은 다음 봉에 들어간다(하루 늦음). 같은 봉 재진입을 허용하지 않는 이유: 체결가가 시가가 아니라 게이트 임계가라 갭하락이면 "임계가에 팔고 더 낮은 시가에 되사는" 유령 이익이 생긴다 — 청산가 모델(M1 replay 공유)을 먼저 고쳐야 한다.
  - 한계 2: `maxHoldDays>1`·`holdLimitOnlyWhenProfitable`(손실 포지션이 리셋을 넘김)·`holdLimitSellFraction` 잔여처럼 매수일이 아닌 날 게이트 청산이 나면 라이브는 **그날** 다시 살 수 있지만 D1 에서는 청산 봉 재진입이 되어 모델링하지 않는다.
  - 2026-09-23 #223 전의 D1 수치(LEGACY 팔 포함)는 게이트 청산 뒤 2봉 공백에서 나온 것이다 — LEGACY 로도 그 전 엔진을 재현할 수 없고 재현은 커밋 `b7edad6` 에서 한다.
- `/backtest`(`StrategyController`)는 reentryMode 를 넘기지 않아 이 기본값을 쓴다. 기본값 결과는 `BacktestDefaultGoldenTest`(`default-golden.txt`)가 trade 단위로 가둔다 — 2026-09-23 전의 `M1ReplayBiasTest`·`KneeStrategyComparisonTest` 수치는 LEGACY 기본값에서 나온 것이다.

`holdLimitOnlyWhenProfitable` 은 보유상한 청산을 수익 중일 때만 내는 정책 노브다(#128 2안 측정용, 기본 off).

측정 결과와 그 한계는 [[reset-churn-measurement]].

## 리서치 전용 노브 (2026-09-04, 기본 off)

파라미터 탐색([[parameter-search-2026-09]])을 위해 추가한 것들이다. **전부 기본값에서 기존 동작과 완전히 같고**, 라이브·`/backtest` 에는 노출하지 않는다(`StrategyController` 가 `BacktestRequest` DTO 로 필드 단위 조립).

| 노브 | 의미 | 가드 |
|---|---|---|
| `marketFilterMaPeriod`(기본 50) | `useMarketFilter` 가 보는 MA 기간 | 엔진 window 가 50봉 고정이라 51 이상은 `init` 에서 거부 — 조용히 절삭되면 "기간을 바꿔도 같다"는 거짓 결론이 나온다 |
| `atrStopMultiplier` / `atrTakeProfitR` | 손절선 = 진입가 − k×ATR(14), 익절선 = 진입가 + R×손절폭 | ATR 은 **진입 시점에 고정**(추적형 아님). ATR 0 이면 진입 자체를 건너뛴다(손절선이 진입가와 겹쳐 즉시 청산되는 사고 방지) |
| `partialTakeProfitPct` / `partialTakeProfitFraction` | 1차 익절선에서 비중 f 청산, 잔여는 기존 게이트로 | 한 `BacktestTrade` 에 가중 합성해 담고 `partialFraction` 으로 표시 — 그 레코드는 `pnl ↔ 가격` 불변식을 만족하지 않는다 |

임계가 산출은 `IntrabarExitModel.exitLevels()` **한 곳**에서만 한다. 부르는 쪽마다 계산하면 D1 백테와 M1 replay 가 다른 정책으로 갈라지면서 컴파일도 테스트도 통과하기 때문이다 — 그래서 `M1ReplayEngine.replayExit` 는 ATR·부분익절 config 를 `require` 로 **거부**한다(`holdLimitOnlyWhenProfitable` 과 같은 이유·같은 방식).

동일봉 충돌 순서는 `트레일링 → 전량 손절 → 부분 익절 → 전량 익절 → CHART → TIME` 이다. 부분 익절을 손절보다 먼저 인정하면 high 가 익절선을·low 가 손절선을 함께 친 봉에서 pnl 이 직접 부풀려진다.

스윙·적립·단순보유를 한 표에 세우려면 이 엔진의 `totalReturnPct`(all-in 복리)·MDD(청산 시점만) 대신 고정 노셔널 Σ pnl%·봉단위 equity MDD 로 재계산해야 한다 — 그 하네스와 운영 8종 1년 결과는 [[yearly-strategy-comparison]].

## 라이브와 다른 점 — 결과 해석 시 주의

- **`useMarketFilter`(50일 MA 아래 매수 차단)는 백테 전용 opt-in** 이며 기본 off 다. 라이브 매수 경로에는 이 필터가 **없다**. 백테에서 켠 채 좋은 결과를 얻고 라이브가 같을 거라 기대하면 안 된다.
- 라이브는 pending reconcile, 잔고 부족, 최소주문금액(5,000원) 같은 현실 제약을 받지만 백테는 받지 않는다([[trading-engine-loop]]).
- **재진입 공백은 반대로 백테가 더 엄격했다(2026-09-23 전 기본 `LEGACY_NEXT_BAR`).** 라이브의 `boughtToday` 는 거래일 1회 제약이고 09:00 경계에서 풀리므로 리셋 당일 돌파하면 다시 사는데, 백테 루프는 `if (position) processExit else processEntry` 라 **청산한 봉에서 진입 평가를 아예 하지 않는다** — 청산 봉 `i` → 신호 `i+1` → 체결 `i+2` 로 **2봉 공백이 강제**됐다. 일봉 기준으로 백테가 라이브보다 restrictive 하다는 뜻이고, 이 때문에 "일일리셋 churn 의 비용"을 기본 설정으로는 잴 수 없었다(#128). 지금은 기본 `LIVE_SAME_BAR` 가 TIME_EXIT 봉에서 진입을 평가하고(#144), 가격게이트 청산 봉에서는 두 모드 모두 종가 신호로 다음 봉 진입을 평가한다(#223).
- 체결 가정이 "다음 봉 시가에 전량"이다. 호가·유동성·부분체결이 없다.

이 한계들 때문에 멀티종목·포지션 사이징이 필요한 전략(주식 퀀트 등)에는 사실상 신규 엔진이 필요하다 — 재사용은 신호 평가 루프 수준까지다. 전략 개선 파이프라인의 기대치는 [[strategy-evolution-expectations]] 참조.
