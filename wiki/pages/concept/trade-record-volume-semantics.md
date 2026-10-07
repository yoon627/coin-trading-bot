---
title: trade_records.volume 의 두 의미 — 엔진은 스냅샷, 수동은 증분
category: concept
created: 2026-08-24
updated: 2026-10-07
claim_state: current
verified: 2026-10-07 — 엔진 매수가 `wait`+부분체결에서 확정하지 않고 pending 을 유지해 종료 응답에서 기록함을 `PositionManagerExtendedTest`(wait→done 흐름·reconcile wait 유지)로 확인, base 코드에서 Red 확인 (#247) · 2026-10-01 — 빈 `strategy` 매수 행 0건(운영 읽기 전용 조회, `trade_records` 223건), null 허용 경로는 `PositionManager.kt` 에 남아 있음(rg) — `TradeRoundTrip` KDoc 과 같은 문장 · 2026-10-01 — 적립 프로파일 제거(MVP 2단계): `buildSellRecord` 의 전략 귀속이 진입 전략 하나(`state.entryStrategy`)로 · 2026-09-28 — 수동 매도 경로 제거(`ManualTradeController`·`executeSell*` 삭제, grep 0건) 뒤 수동 매도 서술을 과거 행 서술로 · 2026-09-28 — reconcile 로 늦게 확정된 매도(uuid·identifier·적립 단·부분 체결 뒤 2차 매도)의 기록 price·pnl·totalAmount 가 판단 tick 가격(`pending_sell_trigger_price`)이고 옛 pending 은 확정 tick 가격으로 떨어짐을 `PositionManagerExtendedTest`·`PositionManagerUnknownOrderTest`·`PositionManagerAccumulateTest` 로 확인, 변이 2종 검출 · 2026-09-28 — dust 흡수 매수의 BUY 스냅샷(총보유 = dust + 체결분)은 `PositionManagerDustTest`, 라운드트립 병합은 `TradeRoundTrip.assembleRoundTrips` 의 연속 BUY 그룹핑을 코드로 확인 · 2026-09-16 — 수동 매도 귀속·durable 정리는 `UserTradingManagerTest` 8건·`ManualTradeControllerTest` 6건(#129 절)으로 확인 · 수동 매도의 terminal 체결량 기록·미확정 무기록은 `TradeExecutionServiceTest` 8건(#105 절)으로 확인 · 2026-09-15 — `pnl_amount_net` 합산·all-or-nothing null 조건은 `TradeRoundTripTest` 6건(#115 절)으로 확인(SPA 의 net 우선·`≈` 폴백 표시는 정적 확인만) · 2026-09-14 — `order_amount` 경로별 규칙은 `PositionManagerExtendedTest` 5건(#146 절)·`TradeExecutionServiceTest`·`DiscordNotifierTest` 로, V26 매핑·집계 SQL 은 `TradeRecordAggregateRoundTripTest`(CI 실 Postgres)로 확인. 엔진 매도 fee 실측화는 `PositionManagerExtendedTest` 3건(즉시 done·reconcile·paid_fee 부재→추정)으로 확인 · 2026-08-24 — 운영 DB(user_id=4, 2026-06~08) 조회로 확인. SELL 30건이 **모두** 직전 BUY 와 수량이 정확히 일치(불일치 0건)하고, 연속 BUY 2건은 수량이 증가해 스냅샷 해석과 정합. `strategy` 분포는 combined 30 / manual 2 / rsi_bounce 1 · 2026-08-26 — 보유량 규칙을 `BuySide` 가 실제로 구현하도록 수정(#132), 추정 오차 부호는 코드로 미정 확인. 허용오차 상한은 기존 계약 테스트가 결정
sources:
  - bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt
  - bot/src/main/kotlin/com/trading/bot/persistence/TradeRecordRepository.kt
  - bot/src/main/kotlin/com/trading/bot/notification/DiscordNotifier.kt
  - bot/src/main/kotlin/com/trading/bot/engine/TradeExecutionService.kt
  - bot/src/main/kotlin/com/trading/bot/api/TradeRoundTrip.kt
---

# `trade_records.volume` 의 두 의미

같은 컬럼에 **두 가지 의미**가 들어간다. 매수 기록을 단순 합산하면 보유량·평단·손익이 모두 어긋난다.

| 기록 경로 | `volume` | `price` |
|---|---|---|
| 엔진 매수 `PositionManager.completeBuy` | 거래소 **실잔고** = 그 시점 **총 보유량 스냅샷** | 거래소 **평단** |
| 과거 수동 매수(`executeBuy`, **2026-09-16 제거** — #129) | `주문금액 / 조회시점 가격` = **증분**(추정치) | 조회 시점 현재가 |

수동 매수 경로는 사라졌지만 그 행들은 남아 있어 아래 규칙은 그대로 필요하다. 이제 매수는 엔진만 한다.

엔진이 실잔고를 적는 것은 의도된 설계다 — 재시작 시 `syncPosition` 이 거래소 잔고에서 복원한 분과
이중계상되지 않게 하려는 것이다(#20).

## 구분 키

`strategy` 컬럼. 과거 수동 매수 행은 `"manual"` 이다. 과거 **수동 매도** 행(2026-09-16~09-28)은 포지션의 진입 전략으로
귀속됐고 모르면 `"manual"` 이다(#129) — 수동 매도는 2026-09-28 제거됐다. 사유는 별도로 `reason=MANUAL`. 값이 비어 있으면
출처를 알 수 없으므로 합산하는 쪽(보수적)으로 둔다. 그런 매수 행은 운영에 0건이다(2026-10-01 조회 — 223건 중 `side='BUY' AND strategy IS NULL` 0, #145) — 데이터의 성질일 뿐이고, 엔진이 `strategy` 없이 기록할 수 있는 경로(`PositionManager` 의 pending 매수 전략 null 허용)는 남아 있어 그런 행이 생기면 이 규칙이 매수량을 부풀린다. 적립 프로파일(2026-10-01 제거)의 청산은 `accumulate` 로 귀속됐다 — 운영에는 그런 행이 없다(2026-10-01 조회).

## 보유량 산출 규칙

```
보유량 = 마지막 엔진 스냅샷 + 그 이후의 수동 증분들
        (엔진 기록이 없으면 모든 수동 증분의 합)
```

전부 합산하면 스냅샷에 이미 포함된 보유분을 두 번 세고, 마지막 행만 쓰면 뒤따르는 수동 매수분이 누락된다.
`TradeRoundTrip.kt` 의 `BuySide` 가 이 규칙을 담는다.

> ⚠️ 2026-08-24~26 사이에는 이 문서만 규칙을 담고 **코드는 앞 절반만 구현**했다(마지막 스냅샷만 쓰고
> 이후 수동 증분을 버림). 이슈 #132 로 드러나 2026-08-26 에 맞췄다. 문서가 먼저 옳았던 사례다.

이 값은 **불변식이 아니라 조회 시점의 최선 추정**이다 — 어떤 기록도 "이 스냅샷이 저 수동 매수를 포함하는가"를
직접 말해주지 않아 `created_at` 순서로 추론한다. 수동 체결과 그 행 기록 사이에 엔진 스냅샷이 끼면
어느 쪽에도 안 잡힐 수 있다(dust 수준).

## 매도 쪽의 비대칭

매도는 반대다 — 전부 거래소 실측이다.

- 엔진 청산 — 즉시 `done` 은 주문량, reconcile 은 terminal 응답의 `executed_volume`
- 과거 수동 매도(2026-09-28 제거) — terminal(done/cancel) 응답의 `executed_volume` 만 기록했다(#105). 체결을 확정하지
  못한 주문은 행을 남기지 않았다.

그래서 과거 수동 매수(추정) → 수동 전량 매도(실측) 조합에서는 이전 포지션이 없어도 매도 수량이 매수보다 많게
기록될 수 있다. 조회 측은 이 경우 초과분의 원가를 알 수 없어 gross 손익(`pnl_amount_gross`)을 비운다 — 매도 행의
실현 손익 합(`pnl_amount_net`, #115)은 기록 시점 평단으로 계산돼 매수 조립과 무관하므로 그대로 낸다.

### 추정 오차의 부호는 정해져 있지 않다

이 문서는 한때 그 어긋남을 **과소추정 한 방향**으로만 서술했으나, 코드상 근거가 없다(2026-08-26 정정).

과거 `executeBuy`(2026-09-16 제거)는 `volume = amount / currentPrice` 를 적었는데,
`currentPrice` 는 **주문 접수 이후** 읽은 틱이었다. 실제 취득 수량은 `(amount − 수수료) / 체결가` 이므로:

```
기록 > 실제  ⟺  체결가 / 조회시점가격  >  1 − 수수료율(≈0.0005)
```

체결과 틱 조회 사이 가격이 수수료율보다 크게 **오르면 과소**, 그 외엔 **과대**다. 코인 변동폭은 초 단위로
그 폭을 양방향으로 넘나든다. 따라서 보정은 단방향 가정이 아니라 **대칭 상대오차**여야 한다.

## 잔량 0 판정의 허용 오차

추정 수량(수동 증분)과 실측 수량(엔진 스냅샷·매도)이 한 그룹에 섞이면 잔량이 정확히 0 이 되지 않는다.
`TradeRoundTrip.kt` 는 상대 허용 오차를 두되, **추정이 들어간 수량에만** 비례시킨다.

```
허용오차 = max(1e-8, 추정증분합 × 0.0025)
```

- **포지션 전체에 비례시키면 안 된다.** 수동 증분이 포지션의 그 비율보다 작으면 증분이 통째로 삼켜져,
  실제로는 보유 중인 포지션이 청산으로 표시된다 — #132 가 신고한 증상이 그대로 되살아난다.
  (엔진 1.0 + 수동 0.002 에서 엔진분만 팔면 잔량 0.002 ≤ 1.002 × 0.0025 로 "청산"이 된다.)
- 실측만으로 이뤄진 그룹은 추정증분합이 0 이라 자동으로 `1e-8` 이 된다 — 완화하지 않는다.
- 청산 판정(`isClosed`)과 `oversold` 판정에 **같은 값**을 쓴다. 기준이 갈리면
  "청산됐는데 매수 기록을 못 믿는다" 는 모순 상태가 생긴다.
- **폭의 상한은 계약에 눌려 있다** — `수동 100.0 매수 → 100.3 매도`(초과 0.3%)를 실제 잔여분으로 보고
  손익을 비우는 기존 테스트(`TradeRoundTripTest.kt:261`)가 있다. 다만 그 0.3% 는 허용 오차가 `1e-8`
  이던 시절 임의로 고른 수치이지 "0.29% 는 추정 오차"라는 도메인 사실이 아니다. 재검토 대상이다.
- **부작용**: 허용 오차 안에서 조기 청산된 뒤 그 dust 를 실제로 팔면, 매수 없는 고아 SELL 행이
  목록에 하나 더 생긴다(`partial=true`, 진입 정보 없음).

수동 매수(2026-09-16, #129)와 수동 매도(2026-09-28)는 제거돼 추정 행은 더 생기지 않는다. 허용 오차가 남는 이유는
과거 수동 **매수** 행이 추정이기 때문이다.

## 이 주문의 체결 금액 — `order_amount` (V26, #146)

스냅샷 의미는 그대로 두고, **이번 주문에 실제로 오간 돈**을 옆 컬럼 `order_amount` 에 남긴다(2026-09-14).
값은 terminal(`done`/`cancel`) 주문 응답의 `Σ trades[].funds` 다(`Order.filledFunds`, 수수료 미포함 — [[upbit-api]]).

| 경로 | `order_amount` |
|---|---|
| 엔진 매수·매도, 응답 terminal | Σfunds |
| 엔진 매수, 폴링 소진 시 `wait`+부분체결 | 기록하지 않는다 — pending 을 유지하고 종료 응답에서 Σfunds 로 기록한다(2026-10-07, #247). 그 전에는 여기서 확정해 NULL 이었다 |
| 엔진 잔고복원(매수·매도) | NULL — 주문 응답 없음 |
| 과거 수동 매도(2026-09-28 제거), 응답 terminal | Σfunds (#105) |
| 과거 수동 매수 | NULL — 요청액은 실측이 아니다 |
| V26 이전 행 | NULL — 소급 불가 |

**소비처 규칙** — 스냅샷을 "이번 주문 금액"으로 읽던 3곳이 이것을 우선 읽는다:

- 전략별 집계(`aggregateByStrategy`)의 `total_amount` 는 **`SUM(order_amount)`**(실측만)이고 미상 행 수를 `amount_unknown_trades` 로 같이 낸다.
  V26 이전 행이 전부 미상이라 배포 직후 이 합계는 0 근처에서 다시 쌓인다 — 축소가 아니라 정의 변경이다.
- SPA 거래 목록의 "금액" 열은 `order_amount` 가 있으면 그 값, 없으면 `total_amount` 를 흐리게 보여준다.
- Discord 알림은 `order_amount` 가 있으면 "체결금액", 없으면 "기록 금액(체결 미상)" 라벨로 `total_amount` 를 보여준다 — 그 값의
  의미가 경로마다 달라(엔진 매수=원가 스냅샷, 매도=평가액, 수동 매수=요청액) 한 단어로 이름 붙일 수 없다.

매도의 `total_amount` 도 판단 tick 가격 × 수량 **평가액**이지 체결 대금이 아니라서 매도에도 같은 규칙을 적용한다.
reconcile 로 늦게 확정된 매도(체결 확인 창을 넘김·응답을 못 받음·재시작)도 판단 tick 가격으로 기록한다 — 주문할 때 `pending_sell_trigger_price` 에 남긴 값이다.
이 규칙은 **#235 판단가 기록이 배포된 뒤 시작된 매도**부터다(`fix(engine): reconcile 로 확정된 매도를 청산을 결정한 tick 가격으로 기록한다` 커밋을 담은 main 배포, 2026-09-28).
그 전의 reconcile 행, 그리고 **배포 시점에 이미 열려 있던** pending 이 배포 뒤 확정된 행은 확정 tick 가격이다(판단가가 없어 폴백한다) — `created_at` 만으로는 둘을 가를 수 없고, 확정 경로 컬럼도 없다.
`created_at` 은 여전히 확정 시각이라 price 와 최대 reconcile 지연만큼 어긋난다. 추정 수수료 행(잔고복원, `paid_fee` 가 없는 reconcile)의 기준 금액도 판단가 × 수량이다.
`TradeRoundTrip` 의 조립은 스냅샷 규칙 위에 서 있어 그대로 `total_amount` 를 쓴다.

## 수수료 기준도 같은 뿌리다

`trade_executions.fee` 는 한때 **무조건** `totalAmount × 요율 / 2` 로 유도됐다. 엔진 매수의 `totalAmount`
가 포지션 전체 원가라, 기존 보유분이 있으면 그만큼 수수료가 부풀려졌다(#133).

2026-08-31 부터 **출처를 경로가 정한다**:

| 출처 | 경로 | 저장값 |
|---|---|---|
| 실측 | 엔진 매수·매도 정상 — `getOrder` 응답의 `paid_fee` (매도는 2026-09-14, #148) | 그 값 |
| 추정 | 과거 수동 매수, 그리고 `paid_fee` 가 없는 매도(엔진 잔고복원·과거 수동 매도 포함) — `totalAmount` 가 그 체결의 대금이다 | `대금 × 요율 / 2` |
| 미기록 | 엔진 **매수** 복구(`recoverFromBalance`) · 매수의 `paid_fee` 부재·파싱 실패 | `0` |

**`0 = 미기록`** 은 V21 이 세운 규약이다("fee 는 소급하지 않는다. 이전 행은 0(미기록)으로 남는다").
**매수**의 basis 를 모를 때 추정으로 떨어뜨리지 않는 이유는 같은 작업의 교훈이다 — *"0(미기록)은 '없다'고 읽히지만
틀린 추정치는 맞는 값과 구분되지 않는다."*

매수 복구 경로가 미기록인 근거: 그 경로의 잔고 전제는 **수량 귀속**의 근거이지 수수료 복원의 근거가 아니다.
`평단 × 잔고` 는 포지션 스냅샷이라 그 주문의 실제 체결 대금·maker/taker 조건을 보장하지 않는다.

매도 쪽 유보 두 가지:

- 과거 수동 매도(2026-09-28 제거)도 엔진 매도와 같은 규칙으로 기록됐다 — terminal 응답의 체결량, `paid_fee` 가 있으면
  실측·없으면 추정, `order_amount` 는 Σ`funds`(#105).
- 엔진 매도는 `paid_fee` 가 없으면 **미기록이 아니라 추정**으로 떨어진다(`PositionManager.sellFeeBasis`). 매도의
  `totalAmount` 는 이 체결의 대금이라 추정이 정당하고, 매수처럼 스냅샷 과대계상이 없기 때문이다. 즉시 done 과
  reconcile terminal 은 `paid_fee` 가 있을 때 실측·없으면 추정이고, 주문 응답이 없는 잔고복원은 언제나 추정이다.
  DB 행에는 출처 마커가 없어 매도의 실측/추정은 SQL 로 구분할 수 없다.

외부 API 쪽 사실은 [[upbit-api]] 의 수수료 절에 있다.

## dust 흡수 매수 (2026-09-28, #234)

팔 수 없는 dust 위에 엔진이 사면([[trading-engine-loop]] "팔 수 없는 보유") 그 BUY 행도 위 규칙 그대로다 — `volume` 은 dust 를 포함한 총보유 스냅샷, `total_amount` 는 계좌 평단(dust 원가가 섞임) × 총보유, `order_amount` 는 이번 주문의 체결 대금뿐이다.

**라운드트립 표시 한계**: `assembleRoundTrips` 는 SELL 없이 이어지는 BUY 를 한 그룹으로 묶는다. 봇 자신의 포지션이 손실로 dust 가 된 경우(사이에 SELL 이 없다) 흡수 BUY 가 옛 그룹에 합쳐져 진입 시각·전략이 옛 값으로 보인다. SELL 기록의 전략은 새 진입 전략이라 둘이 갈린다. 부분체결 잔량(과거에는 봇 화면의 수동 매도로 판 잔량도)은 사이에 SELL 이 있어 따로 묶인다. 거래소 앱에서 직접 판 잔량은 SELL 기록이 없어(봇은 거래소 주문 내역을 가져오지 않는다) 합쳐진다.

## 왜 중요한가

이 이중성을 모르면 조회·집계가 조용히 틀린다. 실제로 라운드트립 조회가 매수를 증분으로 오해해
`KRW-BTC` 의 매수 7건·매도 6건을 `78일 보유중` 한 줄로 뭉쳤고 손익도 `−9,276원` 으로 나왔다.
합성 테스트 12종은 그 전제를 그대로 반영했으므로 전부 통과했다.

매도 쪽은 #105 로 실측이 됐고, 수동 매수는 #129 로 제거됐다 — 추정 행은 과거분만 남는다.

관련: [[persistence-schema]] · [[trading-engine-loop]]
