---
title: 매매 루프 — processTicker 의 게이트 순서
category: concept
created: 2026-07-28
updated: 2026-09-23
claim_state: current
verified: 2026-09-17 — `buy()` 의 귀속 불명 lock 가드는 `PositionManagerExtendedTest` 2건(#121)으로 확인 · 2026-09-16 — `sell` 의 M4(귀속 불명 locked → phantom 정리 + unsynced)는 `PositionManagerExtendedTest` 2건(#122)으로 확인 · 2026-09-08 — 경계 stale-window 가드(`hasCurrentDayCandle`)를 `TradingEngineTest` 재현 테스트(가드 전 Red → 후 Green)로 확인, 원인은 `MarketDataIngestionService`(M1 60초 폴링)·`CandleAggregator`(D1 = UTC 자정 정렬) 전문. 같은 날 청산 파라미터 선언 검사 2종을 실측(`preflight_exit_params` 를 실제 `deploy/vultr/.env` + 결손/빈값 케이스로 실행, `ExitParamsDeclarationCheckTest` 통과). 이전 확인분: 2026-09-02 — processTicker 의 프로파일 dispatch(runSwing/runAccumulate)·applyTickers·refreshUniverse 를 TradingEngine.kt 전문으로 확인, TradingEngineAccumulateTest·TradingEngineUniverseTest 통과. 이전 확인분: 2026-08-23 — TradingProperties.kt 전 필드 대조(takeProfitPct 5.0·trailingArmPct 3.0 로 교정), BacktestEngine.run 가드 off-by-one 수정 확인. 같은 날 #56 로 확장된 `unsynced` 트리거를 PositionManager.syncPosition 실측 + :bot:test 실행. 21 은 게이트가 아니라 store/REST 소스 선택자임을 확인하고 전략 minCandles 계약(#109) 반영
sources:
  - bot/src/main/kotlin/com/trading/bot/config/ExitParamsDeclarationCheck.kt
  - deploy/vultr/deploy.sh
  - bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt
  - bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt
  - bot/src/main/kotlin/com/trading/bot/engine/TradeExecutionService.kt
  - common/src/main/kotlin/com/trading/common/config/TradingProperties.kt
---

# 매매 루프

이 페이지는 Upbit `TradingEngine`의 `processTicker` 순서를 설명한다(2026-09-16 KIS 경로 제거 후 유일한 매매 루프).

`TradingEngine.runLoop()` 이 `intervalSeconds`(기본 10초)마다 활성 ticker 를 순회하며 `processTicker` 를 호출한다. 루프 진입 전 각 ticker 에 `syncPosition` 을 한 번 돌려 거래소 실잔고와 맞춘다.

## processTicker 의 순서 (TradingEngine.kt:231-308)

이 순서 자체가 안전장치다. 임의로 바꾸면 이중 매수·이중 매도가 열린다.

1. **가격 획득** — `MarketDataStore` 우선, **30초** 넘게 묵은 값이면 버리고 REST(`getTicker`) 폴백. 얼어붙은 가격으로 매매하지 않기 위함.
2. **`unsynced` 재동기화** — 부팅 시 `syncPosition` 이 실패했거나, 조회는 됐지만 **우리 주문으로 설명되지 않는 `locked` 잔고**가 있어 그 코인이 우리 포지션인지 정하지 못한 경우 여기서 재시도. 어느 쪽이든 해소될 때까지 `buy()` 초입 가드가 신규 진입을 막는다([[upbit-api]] 의 locked 상한 규칙). 기동 뒤 **런타임에** 생긴 lock(출금 신청·수동 주문)은 이 재동기화가 돌지 않아 못 보므로, `buy()` 가 사이징을 위해 조회하는 잔고에서 같은 판정을 한 번 더 한다(추가 호출 없음) — 걸리면 그 tick 은 매수하지 않고 `unsynced` 로 이 재동기화에 넘긴다(2026-09-17, #121). 적립 `buyRung` 은 보유 중 추가 단이라 이 판정을 하지 않는다.
3. **`pendingPersistFailed` 재기록** — pending durable 기록 실패로 매수가 막힌 상태를 푸는 유일한 경로.
4. **미해소 매수 reconcile** — `pendingBuyUuid` 가 있으면 먼저 확정. 확정되면 그 tick 은 거기서 끝난다(막 산 포지션에 같은 tick 손절 평가 금지). 미해소면 이 tick 의 매수·매도 평가를 통째로 skip.
5. **미해소 매도 reconcile** — 같은 구조의 매도판.
6. **보유 중이면 청산 평가** — `updatePeakPrice`(오를 때만 durable flush) → `decideSell` → `sell`. `sell` 은 거래소 free 잔고를 판다. free 가 0 이면 phantom 이다: `locked` 도 0 이면 `markSold`(진입 메타·사다리 장부까지 정리), `locked` 가 남아 있으면 그것은 우리 매도 주문의 것이 아니므로(우리 주문은 5번의 `pendingSellUuid` 가드가 먼저 걸러낸다) `releaseHoldings`(보유만 내리고 진입 메타·장부는 유지) + `unsynced` 로 2번 재동기화에 넘긴다(2026-09-16, #122). 락이 풀려 코인이 돌아오면 재편입돼 보유상한·트레일링이 이어지고, 여전히 불명이면 매수만 차단된다. 이전에는 locked 가 있으면 보류해 "정리는 sell() 몫"인 `syncPosition` 과 서로 미뤄 유령 포지션이 영구히 남았다. 역방향 위험: 코인이 실제로 사라진 뒤 사용자가 거래소에서 같은 티커를 새로 사면 남은 메타가 그 편입분에 붙는다(감수 — 봇 자신의 신규 진입은 주문 시점 `clearEntryMeta` 로 닫힌다).
7. **당일 1회 가드** — `position || boughtToday` 면 매수 평가 자체를 생략.
8. **매수 평가** — D1 캔들이 store 에 전략이 요구하는 만큼(`max(MIN_DAILY_CANDLES, strategy.minCandles)`) store 에 있으면 store, 아니면 REST 60개로 폴백해 [[swing-strategies]] 의 `shouldBuy` 판정.
   **두 경로 모두 최신 D1 이 오늘 거래일 봉일 때만 평가한다**(2026-09-08, `isCurrentDay`). 09:00 직후 새 날 첫 1분봉이
   폴링되기까지(약 60~120초 — 60초 주기 + 마켓 간 150ms 간격, 한 라운드 실패 시 2주기, [[marketdata-pipeline]]) store 의 최신 D1 은 어제 봉이고,
   REST 일봉도 그날 첫 체결 전엔 어제 봉이 [0] 이며 `DailyCandleCache` 는 60초 TTL 이다. 그 window 로 판정하면 "당일시가" 가 어제 시가가 되어
   어제 매수를 만든 신호가 그대로 참이고 방금 보유상한으로 판 포지션을 같은 가격에 되산다(#128 의 0.0h 재매수).
   경계 뒤 5분(grace) 안에 오늘 봉이 없으면 그 tick 은 건너뛴다(REST 가 빈 목록이면 종전대로 전략 가드에 맡긴다).
   **5분이 지나도 store 가 어제 봉이면 수집 정지로 보고 WARN(소스·티커당 1분 1회) 뒤 REST 로 간다** — 캔들 폴링 코루틴은 워치독 밖이라
   store 가 "개수는 충분하지만 낡은" 채로 하루 종일 매수를 막는 일이 없어야 한다. 백테·라이브 의미론 팔은 당일 부분봉을 당일 시가에서 시작하므로 이 churn 을 재지 않는다.

## 프로파일 분기 (2026-09-02)

1~5 는 두 프로파일 공용 preamble 이고, 그 뒤 `profileOf(ticker)` 가 `runSwing`(6~8 그대로) 과 `runAccumulate` 를 가른다. `trading.accumulate.tickers` 에 든 티커만 ACCUMULATE 이며 기본은 비어 있다. 적립 경로는 손절·트레일링·익절·차트·일일리셋을 **하나도 호출하지 않고** `AccumulateLadder` 판정만 따른다 — 상세는 [[accumulate-ladder]]. 트레일링 고점 flush(preamble 의 `updatePeakPrice`)도 SWING 만 탄다.

활성 티커 집합은 `start()` 가 `적립 티커 ∪ 요청 목록` 으로 만들고, `trading.universe.auto` 가 켜져 있으면 기동 시·09:00 경계에 `refreshUniverse()` → `applyTickers()` 가 알트 목록을 거래대금 상위로 교체한다(보유·pending 티커 잔류, 알트 몫은 20 까지(적립·보유는 예외)). 사용자 목록 `bot_state.tickers` 에는 파생 집합을 되쓰지 않는다.

스윙 `buy()` 는 적립이 아직 투입하지 않은 예산(`reservedKrw`)을 뺀 잔고로 사이징한다 — 알트가 적립 현금을 선점하지 못하게.

## 청산 사유 우선순위 (decideSell, :322-334)

```
STOP_LOSS  >  TRAILING_STOP  >  TAKE_PROFIT  >  CHART_EXIT  >  DAILY_RESET
```

`when` 의 short-circuit 이라 가격 안전망이 걸리면 차트 청산은 **평가조차 하지 않는다**(캔들 조회 비용 회피). 각 게이트의 판정식은 [[exit-gates]] 참조.

## 기본 리스크 파라미터 (TradingProperties.kt)

| 항목 | 코드 기본값 | 운영값 |
|---|---|---|
| `takeProfitPct` | 5.0 (+5% 익절) | 같음 |
| `maxLossPct` | 5.0 (−5% 손절) | 같음 |
| `trailingStopPct` / `trailingArmPct` | 2.0 / 3.0 | **1.5 / 0** (2026-09-06~) |
| `maxHoldDays` | 1 (KST 09:00 경계) |
| `chartExitEnabled` | **false** (기본 off) |
| `intervalSeconds` | 10 |
| `investRatio` / `maxInvestAmount` | 0.1 / 100,000 KRW |
| `reconcileHaltThreshold` | 20 |

> **이 차이가 조용히 사라지지 않게 하는 장치** (#179): 배포는 `deploy/vultr/deploy.sh` 의
> `preflight_exit_params` 가 막는다 — `TRADING_AUTO_START=true` 인데 청산 6개 키가 렌더된 `.env` 에
> 없으면 **업로드 전에** 배포를 중단한다. 앱은 `ExitParamsDeclarationCheck` 가 기동·수동 기동 시
> 실효값을 로그하고 미선언이면 ERROR(→ Discord)를 낸다. **기동이나 거래를 막지는 않는다** —
> 막으면 보유 포지션의 손절·트레일링이 아예 평가되지 않는 공백이 생기고, 그 손해가 파라미터 차이보다 크다.
> 둘 다 값이 아니라 *선언 여부*만 본다.

> [!conflict] 이 표는 두 번 어긋난 적이 있다 — 과거 `PROJECT_ANALYSIS.md` 가 정반대로(손절 −3%/익절 +5%) 적었고, #75(리스크 기본값 단일화) 이후에는 이 페이지가 `takeProfitPct` 2.0 · `trailingArmPct` 0.0 인 옛 값을 들고 있었다(2026-08-23 교정).
> **문서가 아니라 `TradingProperties.kt` 가 근거다.** 기본값이 바뀌면 이 표를 같은 커밋에서 고친다.
>
> ⚠️ **2026-09-06 부터 코드 기본값과 운영값이 갈린다.** 트레일링만 env 오버라이드로 **1.5 / arm 0** 을 쓴다
> (`TRADING_TRAILING_STOP_PCT`·`TRADING_TRAILING_ARM_PCT`). 미관측 7국면 사전고정 판정을 통과한 승격이며
> 근거·한계는 [[trailing-arm-finding-2026-09]]. **운영값의 근거는 코드가 아니라 그 env 다** —
> 확인은 `docker compose exec app printenv | grep TRADING_TRAILING`.
> 코드 기본값을 옮기지 않은 이유: `BacktestConfig` 기본값·`default-golden.txt` 핀·기본 생성자를 쓰는 테스트 76곳이
> 2.0/3.0 을 전제한다. 이전은 골든 재생성을 동반한 별도 작업이다.

## 주문 유실 방지 구조

주문은 비멱등이라 자동 재시도하지 않는다. 대신 `placeOrder` 성공 직후 uuid 를 `pendingBuyUuid`/`pendingSellUuid` 로 잡고 **durable 로 먼저 기록**한 뒤(`NonCancellable` 안에서) 체결을 확인한다. 확인이 실패해도 uuid 가 남아 다음 tick 의 reconcile 이 이어받는다. 이 상태는 `trading_states` 테이블에 영속된다([[persistence-schema]]).

`getOrder` 와 잔고조회가 **둘 다** 실패하는 상황이 `reconcileHaltThreshold`(20회) 연속되면 해당 ticker 를 `halted` 로 두고 신규 진입만 막는다 — 매도·reconcile 은 계속 돌아야 잡힌 포지션이 갇히지 않는다.

## 체결·감사 원자 커밋

체결이 확정되면 `PositionManager`가 전이 결과를 `TradingState.copy()`에 먼저 적용하고, `TradeExecutionService.commitFill` 안에서 `trading_states` upsert와 `trade_records`·`trade_executions` 저장을 한 `TransactionalOperator` 트랜잭션으로 커밋한다. 트랜잭션 성공 뒤에만 원본 메모리 상태를 적용하고 Discord를 알리므로, 감사 저장 실패 시 원본 pending이 남아 다음 tick reconcile의 재시도 근거가 유지된다.

`TradingEngine`은 `processTicker`의 게이트·순서만 조정하며 체결 기록을 별도로 저장하지 않는다. 주문 접수 직후의 pending durable 기록과 즉시 체결 후처리는 `NonCancellable` 구간에서 완주하고, 이후 tick의 reconcile도 같은 `commitFill` 원자 커밋을 사용한다. Discord 알림은 커밋 이후 외부 IO라 실패해도 이미 커밋된 거래 기록을 롤백하지 않는다.
