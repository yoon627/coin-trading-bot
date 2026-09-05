---
title: fill-slippage — 실체결 단가를 기록해 실행 슬리피지를 실측 가능하게 한다
status: done
started: 2026-09-05
updated: 2026-09-05
---

# Goal

그림자 관측(PR #171·#172)이 인정한 공백을 메운다. V24 는 **모델 과대추정폭**(모델 청산가 vs 발동 tick)만 쟀고,
남은 절반인 **실행 슬리피지**(판단 시점 tick 가격 vs 실제 체결가)는 없었다 — 라이브가 실체결가를 기록하지 않았기 때문이다.

라이브 매매 동작은 바꾸지 않는다. 장부·손익 계산도 그대로다(추가 필드는 연구용 관측 입력).

# Progress

- 2026-09-05 — worktree 생성(base `main@1d6e641`). **거래 기록이 판단 시점 tick 가격을 쓴다**는 것을 확인:
  `PositionManager.buildSellRecord` 의 `price = currentPrice`. 거래소가 돌려준 체결가는 어디에도 안 남았다.
- 2026-09-05 — **공식 문서로 응답 형태 확인**(추측 금지): 개별 주문 조회는 최상위 체결금액 합계를 주지 **않고**
  `trades` 배열(`price`·`volume`·`funds`)만 준다. → `Σfunds / Σvolume` 으로 VWAP 를 만든다.
- 2026-09-05 — `Order.trades` + `filledVwap()` 추가(테스트 3건), `TradeRecord.executedVwap` 추가,
  매도 경로 두 곳(즉시 체결·reconcile)에서 전달, V25 로 `shadow_exit_observation.live_exit_vwap` 추가,
  관측기 배선(테스트 6건). `./gradlew build` 통과(실행 984 / skip 19 / 실패 0).

# Next

없음 — 닫혔다. 관측을 켜면(`TRADING_SHADOW_EXIT_ENABLED=true`) 두 마찰이 함께 쌓인다.

# Decisions

## 1) `funds` 로 VWAP 를 만든다 (`price × volume` 재계산 아님)

부분 체결이 여러 건이면 `price × volume` 재계산은 반올림이 누적된다. 거래소가 준 `funds` 를 그대로 합산한다.

## 2) 얻지 못하면 null — 추정하지 않는다

`paid_fee` 와 같은 규율(`Order.feeBasis`). 접수 직후 응답·조회 실패·수동 경로에는 체결 내역이 없다.
**0 을 채우면 "마찰 없음" 으로 오독**되고, 그 관측은 슬리피지 분모에서 빠져야 한다.
`"NaN"`·`"Infinity"` 는 `toDoubleOrNull()` 이 정상 파싱하므로 유한·양수 검사를 함께 건다.

## 3) 장부 의미를 바꾸지 않는다

`TradeRecord.price` 는 여전히 판단 시점 가격이고 손익도 그것으로 계산한다. 실체결가를 장부로 승격하면
PnL·수수료 기준(#105·#148 계열)이 함께 움직여 blast radius 가 커진다. 이 작업은 **관측만** 더한다.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/domain/Order.kt` — `OrderTrade` · `filledVwap()`
- `bot/src/main/kotlin/com/trading/bot/domain/TradeRecord.kt` — `executedVwap`(nullable)
- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — 매도 경로 두 곳에서 전달
- `bot/src/main/resources/db/migration/V25__shadow_exit_observation_live_vwap.sql`
- `wiki/pages/entity/upbit-api.md` — 응답 형태(검증된 외부 사실)

# Blockers

없음.

# Acceptance

1. ✅ 실체결 단가를 `trades` 에서 `Σfunds/Σvolume` 으로 만들고, 못 얻으면 null 이다(테스트 3건: 실형태 파싱·미체결·NaN/Infinity/음수/0수량).
2. ✅ 매도 경로 두 곳(즉시 체결 `done`, reconcile terminal)에서 전달된다.
3. ✅ 관측 1건에 판단가와 실체결가가 **둘 다** 남는다(테스트 6건). 못 얻으면 null 로 남는다.
4. ✅ V25 + wiki 3곳(`upbit-api`·`persistence-schema`·`exit-resolution-verdict`) 동기화.
5. ✅ `./gradlew build` 통과(실행 984 / skip 19 / 실패 0), wiki 검증 3종 통과.
6. ✅ 라이브 매매·장부 의미 무변경 — `TradingProperties`·`deploy/` diff 0, 손익은 여전히 `price` 기준.

# Deferred

- **`trade_records` 에도 실체결가 저장** — 지금은 그림자 관측에만 남는다(포지션의 약 30%). 전 거래에 남기려면
  `trade_records` 컬럼 추가가 필요하고, 그러면 장부 컬럼이 둘이 되어 소비자 정합(#105·#148)을 먼저 정리해야 한다. (중간)
