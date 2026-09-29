---
title: 매매 루프 — processTicker 의 게이트 순서
category: concept
created: 2026-07-28
updated: 2026-09-29
claim_state: current
verified: 2026-09-29 — 봇 제어 상태 저장 실패의 503·저장 순서·미저장 정지 집합(복원 skip·시작 시 제거·종료 저장)·복원 lock 안 재조회는 `UserTradingManagerTest` 23건·`TradingControllerTest` 5건·`ReloadFailureResponseWebFluxTest` 1건으로 확인, 가드 6종(복원 skip·시작 시 제거·같은 행 쓰기 생략·정지의 NonCancellable·종료 저장의 lock 안 재확인·등록 전략만 캐시)은 각각 변이로 실패를 확인 · 2026-09-28 — reconcile 로 늦게 확정된 스윙 전량 청산이 그림자 관측에 판단가·판단 시각으로 보고되고 부분 체결·적립은 보고되지 않음을 `TradingEngineTest`·`TradingEngineAccumulateTest`·`ShadowExitObserverTest` 로 확인, 변이 6종 검출 · 2026-09-28 — reconcile 에서 주문 결과를 받은 뒤의 실패(체결 커밋·계좌 재조회)가 잔고 추정·halt 카운트로 가지 않음(매도는 pending 유지 + 경과 알림, 매수 잔고복원은 기본 경로처럼 전파)은 `TradeAuditAtomicityTest` 6건으로 확인, 좁힌 try 3종과 매도 기록 단계 catch 는 각각 변이로 실패를 확인 · 2026-09-28 — 팔 수 없는 dust 처리(매수 게이트·실잔고 재확인·흡수 새 진입·매도 가드·거래소 거절 WARN·목록 밖 해제·사이징 하한)는 `PositionManagerDustTest` 14건·`TradingEngineTest` 3건·`TradingEngineUniverseTest`·`PositionManagerAccumulateTest`(적립 불변) 로 확인, 가드 14종은 각각 변이로 실패를 확인 · 2026-09-28 — 응답을 못 받은 주문의 identifier 확정(선기록 순서·미접수 확정 조건·흔적 시 보류·매도 선기록 실패 시 전송·취소 중 uuid 기록·재시작 뒤 identifier 확정·빈 uuid 응답)은 `PositionManagerUnknownOrderTest` 26건(확정 규칙의 halt 카운트·해제 순서·잔고 조회 시점·60초 경계·조회 실패를 넘는 흔적 유지 특성 7건 포함), identifier-only pending 의 게이트·잔류(`applyTickers`·`start`)는 `TradingEngineTest`·`TradingEngineUniverseTest` 로 확인. 연속 끊김·흔적 고정·halt 리셋·identifier 게이트는 각각 변이로 실패를 확인 · 2026-09-27 — 잔류·비-auto 정리(durable 메타 비우기 포함)·재기동 입력(`resume`)·화면용 분류·실행 중 409 는 `TradingEngineUniverseTest`(#226 묶음)·`UserTradingManagerTest`·`TradingControllerTest` 로 확인, 정리 순서·진입 게이트·사다리 제외·메타 비우기·직전 states 폐기는 각각 뮤테이션으로 실패를 확인, SPA·409·already_running 은 로컬 앱+브라우저로 관찰 · 2026-09-17 — `buy()` 의 귀속 불명 lock 가드는 `PositionManagerExtendedTest` 2건(#121)으로 확인 · 2026-09-16 — `sell` 의 M4(귀속 불명 locked → phantom 정리 + unsynced)는 `PositionManagerExtendedTest` 2건(#122)으로 확인 · 2026-09-08 — 경계 stale-window 가드(`hasCurrentDayCandle`)를 `TradingEngineTest` 재현 테스트(가드 전 Red → 후 Green)로 확인, 원인은 `MarketDataIngestionService`(M1 60초 폴링)·`CandleAggregator`(D1 = UTC 자정 정렬) 전문. 같은 날 청산 파라미터 선언 검사 2종을 실측(`preflight_exit_params` 를 실제 `deploy/vultr/.env` + 결손/빈값 케이스로 실행, `ExitParamsDeclarationCheckTest` 통과). 이전 확인분: 2026-09-02 — processTicker 의 프로파일 dispatch(runSwing/runAccumulate)·applyTickers·refreshUniverse 를 TradingEngine.kt 전문으로 확인, TradingEngineAccumulateTest·TradingEngineUniverseTest 통과. 이전 확인분: 2026-08-23 — TradingProperties.kt 전 필드 대조(takeProfitPct 5.0·trailingArmPct 3.0 로 교정), BacktestEngine.run 가드 off-by-one 수정 확인. 같은 날 #56 로 확장된 `unsynced` 트리거를 PositionManager.syncPosition 실측 + :bot:test 실행. 21 은 게이트가 아니라 store/REST 소스 선택자임을 확인하고 전략 minCandles 계약(#109) 반영
sources:
  - bot/src/main/kotlin/com/trading/bot/config/ExitParamsDeclarationCheck.kt
  - deploy/vultr/deploy.sh
  - bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt
  - bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt
  - bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt
  - bot/src/main/kotlin/com/trading/bot/engine/UnknownOrderResolver.kt
  - bot/src/main/kotlin/com/trading/bot/engine/BalanceInterpretation.kt
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
4. **미해소 매수 reconcile** — `pendingBuyUuid`(응답을 못 받은 주문이면 `pendingBuyIdentifier`)가 있으면 먼저 확정. 확정되면 그 tick 은 거기서 끝난다(막 산 포지션에 같은 tick 손절 평가 금지). 미해소면 이 tick 의 매수·매도 평가를 통째로 skip.
5. **미해소 매도 reconcile** — 같은 구조의 매도판. 확정 기록의 price·pnl 은 확정 tick 이 아니라 주문을 결정한 tick 가격이다(주문 때 `pending_sell_trigger_price` 에 남긴다, #235). 스윙 포지션이 전량 청산되면 그림자 관측에도 보고한다(판단가·판단 시각 — 즉시 체결 경로는 보고 시각이라 체결 확인·기록 커밋·알림만큼 늦다). 부분 체결은 잔량이 팔리는 확정에서 보고하며 그때의 가격·사유·시각은 잔량을 판 주문의 것이다. 잔량이 dust 면 다음 tick 에 관측 상태를 버려 그 포지션은 관측되지 않는다.
6. **보유 중이면 청산 평가** — `updatePeakPrice`(오를 때만 durable flush) → `decideSell` → `sell`. `sell` 은 거래소 free 잔고를 판다. free 가 0 이면 phantom 이다: `locked` 도 0 이면 `markSold`(진입 메타·사다리 장부까지 정리), `locked` 가 남아 있으면 그것은 우리 매도 주문의 것이 아니므로(우리 주문은 5번의 `pendingSellUuid` 가드가 먼저 걸러낸다) `releaseHoldings`(보유만 내리고 진입 메타·장부는 유지) + `unsynced` 로 2번 재동기화에 넘긴다(2026-09-16, #122). 락이 풀려 코인이 돌아오면 재편입돼 보유상한·트레일링이 이어지고, 여전히 불명이면 매수만 차단된다. 이전에는 locked 가 있으면 보류해 "정리는 sell() 몫"인 `syncPosition` 과 서로 미뤄 유령 포지션이 영구히 남았다. 역방향 위험: 코인이 실제로 사라진 뒤 사용자가 거래소에서 같은 티커를 새로 사면 남은 메타가 그 편입분에 붙는다(감수 — 봇 자신의 신규 진입은 주문 시점 `clearEntryMeta` 로 닫힌다).
7. **당일 1회 가드** — (팔 수 있는) 보유 중이거나 `boughtToday` 면 매수 평가 자체를 생략. 보유가 팔 수 없는 **dust** 면 막지 않는다(아래 "팔 수 없는 보유").
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

활성 티커 집합은 `start()` 가 `적립 티커 ∪ 사용자 목록 ∪ 잔류` 로 만들고, `trading.universe.auto` 가 켜져 있으면 기동 시·09:00 경계에 `refreshUniverse()` → `applyTickers()` 가 알트 목록을 거래대금 상위로 교체한다(보유·pending 티커 잔류, 알트 몫은 20 까지(적립·보유는 예외)). 사용자 목록 `bot_state.tickers` 에는 파생 집합을 되쓰지 않는다.

- **잔류(#226, auto 무관)**: 사용자 목록·적립 밖이어도 진입 흔적(pending buy/sell uuid·`entryStrategy`·`buyDate`)이 있는 durable 행 — 엔진이 산 스윙 포지션·미해소 주문 — 은 활성에 싣고 청산될 때까지 관리한다. 신규 진입은 `swingUniverse`(비-auto = 사용자 목록, auto = 마지막 선정)가 막는다. 사다리 행(`rungsFilled>0`·단 매수 `pendingBuyTriggerPrice`·`entryStrategy=accumulate`)은 적립 설정에서 빠졌으면 싣지 않고 WARN — 실으면 스윙 청산(손절·09:00 보유상한)이 붙는다. 싣는 잔류 목록도 기동 WARN 으로 남는다.
- **비-auto 정리**: 기동 동기화 직후와 09:00 flush 뒤에 청산 대기(`isExitOnly` — 진입 허용 집합 밖 스윙 티커) 중 더 지킬 것이 없는(보유·unsynced·pending 없음) 티커를 활성·`states` 에서 빼고, 그 행의 durable 진입 메타도 비운다 — 엔진 밖에서 팔려 메타만 남은 행이 매 기동 실리고, 그 위에 사람이 다시 산 코인이 옛 `buyDate` 로 보유상한 매도되는 것을 막는다. unsynced 가 아니라 귀속 불명 락이 없으므로 #122 가 메타를 남기는 이유(락이 풀리면 코인이 돌아옴)는 해당하지 않는다. `persistState` 는 실패를 삼키므로 던지지 않는다. auto 는 `applyTickers` 가 같은 일을 한다. 남는 틈: 엔진이 멈춘 사이 팔고 **다시 산** 경우는 기동 때 잔류로 실려 옛 `buyDate` 로 관리된다(잔류 WARN 이 `buyDate` 를 함께 찍는다).
- **재기동 입력**: `start` 는 입력 상태를 먼저 싣고 나머지 키를 빼서 직전 실행의 `states` 를 버린다(순서는 정확성과 무관하다 — 같은 엔진을 다시 start 할 때(주로 `resume()`) 잠금 없이 읽는 `getStates()`(상태 API)에서 전후 모두 활성인 티커가 잠깐 사라지지 않게 할 뿐이다). 재기동하는 쪽(reload)은 엔진의 사용자 목록(`getUserTickers()`)을 그대로 넘기고 — 활성 집합을 넘기면 잔류가 진입 대상으로 승격된다 — 같은 엔진으로 복귀할 때는 `resume()`(직전 사용자 목록 + states ∪ 잔고 미확인 dormant)을 쓴다. 실행 중 엔진에 다른 목록으로 `startBot` 이 오면 부작용 없이 409, 같거나 없으면 `already_running`.
- **봇 제어의 상태 저장(#228)**: 재시작 복원은 `bot_state.running` 만 본다 — 제어 API 가 저장 실패를 성공으로 답하면 멈춘 봇이 다음 재시작 때 다시 돌거나 도는 봇이 복원되지 않는다. 그래서 정지·시작·전략 변경·halt 해제는 저장이 실패하면 `BotControlPersistFailedException` → 503 + 안내 문구다. 행동 전에 막을 수 있는 것은 저장을 먼저 한다(시작은 `engine.start` 바로 앞, 전략은 메모리·엔진보다 앞 — 실패면 아무것도 바뀌지 않는다). 정지는 반대로 저장이 실패해도 엔진을 멈춘다 — 엔진을 멈추고 맵에서 뺀 뒤 **저장 전에** 그 사용자를 in-memory `unpersistedStops` 에 넣고(여기까지 `NonCancellable` — 요청이 끊겨도 표시가 남는다), 저장(10s 상한, 취소 가능 — 걸린 DB 호출이 사용자 lock 을 무기한 쥐지 않게)이 성공해야 뺀다. 이 집합이 DB 행보다 우선한다(엔진 없음): 복원(`restoreOne`, lock 안에서 행을 다시 읽는다)은 건너뛰고, 시작이 저장을 마치면(행이 같아 쓰기를 생략해도) 빠지며, 정상 종료(`SmartLifecycle.stop`)가 엔진 정지와 동시에 lock 안에서 집합을 다시 확인하고 한 번 더 저장한다. 엔진이 없어도 행이 running 이면 정지가 내린다(복원 실패로 엔진 없이 DB 만 running 인 경우). 백그라운드 재시도는 두지 않았다 — 걸린 DB 호출이 사용자 lock 을 쥐고 종료 예산을 먹는 새 실패가 생기고, 배포 재시작(정상 종료)은 종료 저장이 덮는다. 남는 틈: 저장이 확인되지 않은 채 정상 종료 전에 프로세스가 죽거나, 종료 저장도 실패·시간 초과하면(쓰기 장애가 재시작까지 이어지는 경우 — 같은 장애라 두 실패는 독립이 아니다) 재시작 때 봇이 다시 뜬다. 종료 저장 실패는 ERROR 로 남고 Discord 전송은 fire-and-forget 이라 종료와 경합한다(전송 시도까지만 보장). 복원은 등록되지 않은 전략 이름을 캐시하지 않는다(끝내 실패하면 그 이름이 상태 API 로 나가 시작이 400 이 된다). 등록되지 않은 전략 이름의 시작은 400(`UNKNOWN_STRATEGY_CODE`).
- **화면용 분류**: 활성 집합 = 적립 ∪ 진입 허용(`getEntryTickers` — 비-auto 는 사용자 목록, auto 는 선정 알트) ∪ 청산 대기(`getExitOnlyTickers`). 진입 게이트·정리 단계·분류가 같은 `isExitOnly` 를 쓴다.

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

## 팔 수 없는 보유 — dust (2026-09-28, #234)

Upbit 는 주문 금액이 5,000원 미만이면 매수도 매도도 받지 않는다([[upbit-api]]). 그 금액 아래인 스윙 보유는 팔 수 없는 **dust** 다 — 수동 부분매도 잔량, 부분체결 잔량, 손실로 5,000원 아래가 된 소액 포지션. 이전에는 잔고가 0 보다 크면 보유로 봐서 그 티커의 진입이 영영 막히고, 청산 사유가 걸릴 때마다 거래소 거절 ERROR 가 반복됐다.

- **판정은 현재가 기준** — `holdVolume × 현재가 < 5,000원`(`isBelowMinOrder`, 정확히 5,000원은 팔 수 있다). 매 tick 판정하므로 가격이 회복하면 자동으로 dust 가 아니게 된다. 수량 미상(0)은 dust 로 보지 않는다.
- **청산 평가는 그대로 돈다** — `holdVolume` 은 마지막 동기화 값이라 낡았을 수 있어, 여기서 끄면 실제로 팔 수 있는 포지션이 손절을 잃는다. 주문 여부는 `placeSell` 이 **실잔고(free)** 로 정한다: 최소주문 미만이면 보내지 않고(선기록·ERROR 없음, WARN 은 포지션당 1회), `holdVolume` 을 실측으로 고친다. 거래소가 `under_min_total_ask` 로 거절해도(tick 가격과 판정 가격 차이) 같은 WARN 이다 — 이 경우는 tick 가격으로는 dust 가 아니라 매수 게이트가 열리지 않고, 청산 사유가 참인 동안 선기록·거절이 조용히 반복된다(거래소가 어느 가격으로 판정하는지 문서에 없어 감수).
- **매수 게이트만 연다** — dust 면 매수 평가를 한다. `buy()` 가 실잔고로 다시 확인해 팔 수 있는 보유면 사지 않고 재동기화하고(그 위에 사면 이중 포지션), dust 가 맞으면 **진입 메타만 지우고** 산다. 체결 확정이 실잔고로 수량·평단을 덮고(replace) 빈 메타를 오늘 날짜·이번 전략·새 청산 스냅샷·체결 뒤 평단을 고점으로 채워 **새 진입**이 된다. 주문이 무산돼도 dust 는 그대로 관리된다. 같은 tick 에 낸 매도가 결과 불명으로 남았으면 사지 않는다 — 미해소 매도 중에는 어떤 경로로도 매수하지 않는다(`entryBlocked`).
- **평단 오염은 감수한다** — 체결 뒤 평단은 계좌 평단이라 dust 원가가 섞인다. 오염 ≈ |dust 평가액 − dust 원가| ÷ (dust 원가 + 이번 매수액)이라 매수액에 비해 dust 가 작으면 작고(1,000원 잔량 + 5만원 매수 → 1% 안팎), 둘이 비슷하면 크다(봇의 5,300원 포지션이 4,900원 dust 가 된 것을 5,300원으로 흡수 → 약 3.9%). 손실 dust 는 손절선을, 싸게 산 dust 는 익절선을 당겨 흡수 직후 곧바로 청산될 수 있다 — 그러면 dust 까지 전량 팔려 정리되고 왕복 비용만 든다. 그 손익은 새 진입 전략 몫으로 기록된다.
- **목록 밖(exit-only) 티커의 dust** 는 새로 살 수 없어(#226) 흡수될 길이 없다 → `releaseDust` 가 실잔고를 확인한 뒤 장부에서 내리고 진입 메타를 지운다(기록 없음, 코인은 계좌에 남는다). 미해소 매도가 있거나 계좌에 잠긴 코인(사용자 지정가 등 — 풀리면 돌아온다)이 있으면 내리지 않는다. 비-auto 재시작에서는 다시 실리지 않고, auto 재시작에서는 dormant revive 로 되살아났다가 다시 해제된다.
- **사이징 하한** — 스윙 매수는 `investAmount × (1 − maxLossPct/100) < 5,000원` 이면 하지 않는다(기본 5% 면 약 5,264원 미만). 그보다 작게 사면 손절 시점에 팔 수 없는 포지션을 봇이 직접 연다.
- **적립 프로파일은 이 규칙 밖**이다 — 사다리 매도는 기존대로 최소주문 미만이면 `accumulateSkipReason` 으로 건너뛰고, 단 매수는 보유와 무관하게 계속 된다.
- 라운드트립 표시: 봇 자신의 손실 dust(사이에 SELL 없음)를 흡수하면 새 BUY 가 옛 그룹에 합쳐진다([[trade-record-volume-semantics]]).

## 주문 유실 방지 구조

주문은 비멱등이라 자동 재시도하지 않는다. 대신 **주문 전에** 클라이언트 identifier(`ctb-`+UUID)와 주문 의도(전략·트리거가·주문 전 보유량 등)를 `pendingBuyIdentifier`/`pendingSellIdentifier` 로 **durable 로 먼저 기록**하고, 응답의 uuid 를 받으면 `pendingBuyUuid`/`pendingSellUuid` 로 바꿔 기록한 뒤 체결을 확인한다. 선기록부터 체결 반영까지가 한 `NonCancellable` 블록이고, 정지(stop/reload)가 시작된 뒤에는 새 주문을 시작하지 않는다. 확인이 실패해도 uuid 가 남아 다음 tick 의 reconcile 이 이어받는다. 이 상태는 `trading_states` 테이블에 영속된다([[persistence-schema]]). pending 주문은 uuid 를 알면 uuid, 모르면 identifier 하나로만 식별되고, 모든 게이트는 둘 중 하나라도 있으면 미해소로 본다(`hasPendingBuy`/`hasPendingSell`).

### 응답을 못 받은 주문 (2026-09-28, #227)

타임아웃·연결 끊김·5xx·빈 응답·취소는 주문이 나갔는지 말해 주지 않는다. 이전에는 이것을 "미전송"으로 보고 pending 없이 끝내, 접수된 매수가 다음 tick 에 한 번 더 나가고(2배 포지션, 첫 매수 기록 없음) 접수된 매도는 청산 기록 없이 phantom 으로 정리됐다. 지금 규칙:

- 판정(조회·흔적·미접수 확정)은 `UnknownOrderResolver` 가 하고 상태는 바꾸지 않는다 — pending 이어받기·해제·halt 카운트·저장은 `PositionManager` 가 판정을 받아 한다. 판정은 조회·잔고 실패를 예외가 아니라 "아직 판단 불가"로 돌려준다(#235).
- [[upbit-api]] 의 요청 검증 오류만 미접수로 보고 pending 을 지운다. 나머지는 identifier 만 남긴 pending 이다 — 그 티커는 확정될 때까지 매매 평가를 건너뛴다.
- reconcile 은 `GET /v1/order?identifier=` 로 찾는다. 찾으면 uuid 를 이어받아 기존 uuid 경로로 확정한다(기록의 `exchange_order_id` = 그 uuid).
- 못 찾으면(404) **주문 전 기준값**과 잔고를 비교한다 — 매수: 보유(`heldVolume`)가 `pendingBuyPriorVolume` 보다 늘었나, 매도: free 가 `pendingSellPriorVolume` 보다 줄었나. 흔적 없는 404 가 **끊김 없이 2회 이상 + 그 첫 404 뒤 60초**가 지나면 미접수로 확정해 pending 을 푼다(매도는 포지션 유지 → 다음 tick 재매도). 조회 실패나 잔고 조회 실패가 끼면 처음부터 다시 센다. 증명은 아니다 — 시장가 주문은 접수되면 곧바로 체결돼 잔고가 움직인다는 전제에 기대고, 조회 가시성 지연은 문서에 없다. 횟수는 비영속이라 재시작·reload 하면 처음부터 다시 센다.
- **흔적이 한 번이라도 보이면 자동 처리하지 않는다** — identifier 만으로는 잔고 변화가 이 주문인지 같은 시각의 수동 매매인지 가를 수 없고, 기록의 중복 방지 키(uuid)도 없다. 흔적이 나중에 사라져도 풀지 않는다 — 단 같은 엔진 안에서만이다(비영속). 재시작·reload 뒤에는 흔적이 남아 있으면 다시 잡지만, 그 사이 사라졌으면 일반 규칙으로 풀린다. ERROR 1회로 사람을 부르고 조회는 계속한다(찾히면 정상 확정). 그동안 그 티커의 매매가 멈춘다 — 매수 흔적이면 새로 들어온 코인에 손절이 걸리지 않는다. 해제 API 는 없다: **봇 정지 → Upbit 주문 내역 확인 → `trading_states` 의 pending 정리 → 재기동** 순서로 한다. 봇이 도는 중에 DB 를 고치면 다음 tick 이 메모리 상태로 다시 덮어쓴다.
- 선기록이 실패하면 **매수는 보내지 않고, 매도는 보낸다**(ERROR + `pendingPersistFailed`) — 기록 장애가 손절을 막으면 안 된다. 같은 엔진 안에서는 메모리 identifier 가 이중 매도를 막는다. 다만 다음 tick 의 재기록(`retryPendingPersistIfNeeded`)보다 reload 가 먼저 오면 새 엔진은 durable 에 없는 그 주문을 모른다(uuid 기록 실패에도 있던 창).
- 스윙 매수의 `pendingBuyPriorVolume` 도 주문 직전 실제 보유량이다(이전 0). 흔적 판정과 uuid 경로의 잔고 복원이 장부 밖 잔고(수동 매수·dust)를 체결로 세지 않는다.

`getOrder` 와 잔고조회가 **둘 다** 실패하는 상황이 `reconcileHaltThreshold`(20회) 연속되면 해당 ticker 를 `halted` 로 두고 신규 진입만 막는다 — 매도·reconcile 은 계속 돌아야 잡힌 포지션이 갇히지 않는다. identifier-only 매수에서는 identifier 조회 실패와 "404 인데 잔고도 못 봄"이 같은 카운터에 들어가고, 카운터는 확정(찾음·미접수)에서만 되돌린다 — 404 마다 되돌리면 간헐 장애가 해제 조건과 halt 를 둘 다 끊어 매매가 멈춘 채 알림이 없다. 매도는 경과시간 알림(`pendingSellSince`, 선기록 시각부터)이 사람을 부른다.

## 체결·감사 원자 커밋

체결이 확정되면 `PositionManager`가 전이 결과를 `TradingState.copy()`에 먼저 적용하고, `TradeExecutionService.commitFill` 안에서 `trading_states` upsert와 `trade_records`·`trade_executions` 저장을 한 `TransactionalOperator` 트랜잭션으로 커밋한다. 트랜잭션 성공 뒤에만 원본 메모리 상태를 적용하고 Discord를 알리므로, 감사 저장 실패 시 원본 pending이 남아 다음 tick reconcile의 재시도 근거가 유지된다.

reconcile 에서 결과(주문 응답 또는 잔고)를 **확인한 뒤** 기록 단계의 실패(이 커밋, 또는 주문 결과 반영 중의 계좌 재조회)는 조회 실패가 아니다. 주문 결과가 있는데 잔고 추정으로 넘기면 실측 체결(수수료·VWAP·대금·체결량)을 추정치로 영구 기록하게 되므로 그러지 않는다. 매도는 WARN 과 함께 pending 을 두고 다음 tick 에 같은 주문을 다시 확정하며, 막힌 매도 경과 알림은 계속 돈다. 매수 잔고복원은 매수 기본 경로처럼 예외를 올린다(엔진 ERROR). 어느 쪽도 halt 카운터에 세지 않는다 — 잔고 추정과 halt 카운트는 getOrder·잔고조회 자체가 실패했을 때만이다. 커밋 원인은 `commitFill` 이 ERROR 로 남긴다.

`TradingEngine`은 `processTicker`의 게이트·순서만 조정하며 체결 기록을 별도로 저장하지 않는다. 주문 접수 직후의 pending durable 기록과 즉시 체결 후처리는 `NonCancellable` 구간에서 완주하고, 이후 tick의 reconcile도 같은 `commitFill` 원자 커밋을 사용한다. Discord 알림은 커밋 이후 외부 IO라 실패해도 이미 커밋된 거래 기록을 롤백하지 않는다.
