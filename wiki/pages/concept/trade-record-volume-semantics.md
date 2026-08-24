---
title: trade_records.volume 의 두 의미 — 엔진은 스냅샷, 수동은 증분
category: concept
created: 2026-08-24
updated: 2026-08-24
claim_state: current
verified: 2026-08-24 — 운영 DB(user_id=4, 2026-06~08) 조회로 확인. SELL 30건이 **모두** 직전 BUY 와 수량이 정확히 일치(불일치 0건)하고, 연속 BUY 2건은 수량이 증가해 스냅샷 해석과 정합. `strategy` 분포는 combined 30 / manual 2 / rsi_bounce 1
sources:
  - bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt
  - bot/src/main/kotlin/com/trading/bot/engine/TradeExecutionService.kt
  - bot/src/main/kotlin/com/trading/bot/api/ManualTradeController.kt
  - bot/src/main/kotlin/com/trading/bot/api/TradeRoundTrip.kt
---

# `trade_records.volume` 의 두 의미

같은 컬럼에 **두 가지 의미**가 들어간다. 매수 기록을 단순 합산하면 보유량·평단·손익이 모두 어긋난다.

| 기록 경로 | `volume` | `price` |
|---|---|---|
| 엔진 매수 `PositionManager.completeBuy` | 거래소 **실잔고** = 그 시점 **총 보유량 스냅샷** | 거래소 **평단** |
| 수동 매수 `TradeExecutionService.executeBuy` | `주문금액 / 조회시점 가격` = **증분**(추정치) | 조회 시점 현재가 |

엔진이 실잔고를 적는 것은 의도된 설계다 — 재시작 시 `syncPosition` 이 거래소 잔고에서 복원한 분과
이중계상되지 않게 하려는 것이다(#20).

## 구분 키

`strategy` 컬럼. `ManualTradeController` 가 수동 주문에 `"manual"` 을 하드코딩한다.
값이 비어 있으면 출처를 알 수 없으므로 합산하는 쪽(보수적)으로 둔다.

## 보유량 산출 규칙

```
보유량 = 마지막 엔진 스냅샷 + 그 이후의 수동 증분들
        (엔진 기록이 없으면 모든 수동 증분의 합)
```

전부 합산하면 스냅샷에 이미 포함된 보유분을 두 번 세고, 마지막 행만 쓰면 뒤따르는 수동 매수분이 누락된다.
`TradeRoundTrip.kt` 의 `BuySide` 가 이 규칙을 담는다.

## 매도 쪽의 비대칭

매도는 반대다.

- 엔진 청산·수동 `sellAll` — **실제 잔고** 기준
- 수동 `executeSellVolume` — 주문 **요청 수량**. 주문 후 체결을 확인하지 않아 부분 체결이면 실제와 어긋난다(이슈 #105)

그래서 수동 매수(추정) → `sellAll`(실측) 조합에서는 이전 포지션이 없어도 매도 수량이 매수보다 많게
기록될 수 있다. 조회 측은 이 경우 초과분의 원가를 알 수 없어 손익을 비운다.

## 왜 중요한가

이 이중성을 모르면 조회·집계가 조용히 틀린다. 실제로 라운드트립 조회가 매수를 증분으로 오해해
`KRW-BTC` 의 매수 7건·매도 6건을 `78일 보유중` 한 줄로 뭉쳤고 손익도 `−9,276원` 으로 나왔다.
합성 테스트 12종은 그 전제를 그대로 반영했으므로 전부 통과했다.

근본 개선은 이슈 #105(수동 매도가 실제 체결량을 기록하도록)에서 다룬다.

관련: [[persistence-schema]] · [[trading-engine-loop]]
