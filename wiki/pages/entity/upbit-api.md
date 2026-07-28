---
title: Upbit API — 이 봇이 의존하는 동작
category: entity
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — PositionManager.kt 주문 경로 실측(ord_type·volume·상태 판정), MarketDataIngestionService.kt 수집 경로
sources:
  - bot/src/main/kotlin/com/trading/bot/client/UpbitClient.kt
  - bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt
  - bot/src/main/kotlin/com/trading/bot/marketdata/UpbitMarketFeed.kt
---

# Upbit API

이 repo 가 지원하는 **유일한 거래소**다. 아래는 코드가 실제로 의존하고 있는 동작만 적는다.

## 주문

| 경로 | 파라미터 |
|---|---|
| 시장가 매수 | `ord_type=price` (금액 지정) |
| 시장가 매도 | `ord_type=market`, `volume` = **거래소 원본 문자열 잔고** |

- **최소 주문 금액 5,000 KRW.** 미만이면 주문이 성립하지 않는다.
- 매도 수량은 `Double` 로 변환하지 않고 거래소가 준 문자열을 그대로 쓴다 — 부동소수 오차로 잔고를 초과하는 것을 피하기 위함이다.
- **주문은 멱등이 아니다.** idempotency key 가 없으므로 타임아웃·429 에 자동 재시도하지 않는다. 대신 uuid 를 durable 로 기록하고 다음 tick 에 reconcile 한다([[trading-engine-loop]]).

## 주문 상태

`getOrder` 응답의 `state` 는 `wait`(미체결/부분) / `done`(체결) / `cancel`(취소, 부분체결 가능) 이고 `executedVolume` 이 체결 수량이다.

주의할 조합:

- **시장가 매수(`price`)** 는 즉시 체결 후 소액 잔량을 환불하며 종료되므로 `wait` 로 장기 잔존하지 않는다. 지정가를 도입한다면 부분체결·잔여주문 취소 로직이 따로 필요하다.
- **`wait` + `executedVolume>0`** 일 때 미체결 잔량은 `locked` 로 묶인다. 이때 free balance 만 보면 "청산 완료"로 오판한다 — `free + locked` 합을 봐야 한다.

## 시세

- **WS ticker** — 실시간 체결가 스트림. 연결이 살아 있어도 데이터가 끊기는 half-open 이 실제로 발생하므로 워치독이 붙어 있다([[marketdata-pipeline]]).
- **REST 캔들** — `/v1/candles/days` 는 한 번에 최대 200개, `to` 파라미터로 페이지네이션한다. **D1 봉 경계는 KST 09:00(=UTC 00:00)** 이며, 이 때문에 일일 리셋 기준과 봉 open 이 정합한다.
- 분봉(M1)은 폴링으로 60초마다 수집한다.

## 에러

`UpbitApiException` 을 `UpbitErrorHandlerAdvice` 가 사용자 친화적 4xx 로 변환한다. **raw 401 을 그대로 노출하지 않는다** — 프런트엔드가 401 을 자동 로그아웃으로 처리하기 때문에, 거래소 인증 실패가 사용자 세션 로그아웃으로 둔갑하는 것을 막기 위함이다.
