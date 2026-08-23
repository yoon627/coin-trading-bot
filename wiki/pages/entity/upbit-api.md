---
title: Upbit API — 이 봇이 의존하는 동작
category: entity
created: 2026-07-28
updated: 2026-08-22
claim_state: current
verified: 2026-08-22 — docs.upbit.com 전체 계좌 조회의 balance/locked 필드 정의 원문, PositionManager.heldVolume 상한 규칙 (#56). 이전 2026-07-28 — PositionManager.kt 주문 경로 실측(ord_type·volume·상태 판정), MarketDataIngestionService.kt 수집 경로
sources:
  - bot/src/main/kotlin/com/trading/bot/client/UpbitClient.kt
  - bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt
  - bot/src/main/kotlin/com/trading/bot/marketdata/UpbitMarketFeed.kt
---

# Upbit API

이 repo 가 지원하는 **유일한 거래소**다. 아래는 코드가 실제로 의존하고 있는 동작만 적는다.

## 계좌 (`GET /v1/accounts`)

공식 문서의 필드 정의는 이렇다.

| 필드 | 정의(문서 원문) |
|---|---|
| `balance` | 주문 가능 수량 또는 금액 |
| `locked` | **출금이나 주문 등에 잠겨 있는 잔액** |

`locked` 를 "미체결 주문에 묶인 수량"으로만 읽으면 안 된다 — **출금 대기와 사용자가 거래소에서 직접 낸
주문까지 섞인다**. 봇이 매도에 쓸 수 있는 건 `balance`(free) 뿐이므로, 설명되지 않는 `locked` 를 보유로
세면 팔 수 없는 유령 포지션이 된다. 그래서 `holdVolume` 은 `free + min(locked, 우리 주문의 미체결 잔량)`
으로 상한을 둔다(`PositionManager.heldVolume` — 이 산식이 유일한 정의다).

## 주문

| 경로 | 요청 파라미터 |
|---|---|
| 시장가 매수 | `market`, `side=bid`, `ord_type=price`, `price=<내림한 KRW 금액>` (수량 아닌 **금액** 지정) |
| 시장가 매도 | `market`, `side=ask`, `ord_type=market`, `volume=<거래소 원본 문자열 잔고>` |

- **최소 주문 금액 5,000 KRW.** 미만이면 주문이 성립하지 않는다.
- 매도 수량은 `Double` 로 변환하지 않고 거래소가 준 문자열을 그대로 쓴다 — 부동소수 오차로 잔고를 초과하는 것을 피하기 위함이다.
- **주문은 멱등이 아니다.** idempotency key 가 없으므로 타임아웃·429 에 자동 재시도하지 않는다. 대신 uuid 를 durable 로 기록하고 다음 tick 에 reconcile 한다([[trading-engine-loop]]).

## 주문 상태

`getOrder` 응답의 `state` 는 `wait`(미체결/부분) / `done`(체결) / `cancel`(취소, 부분체결 가능) 이고 `executedVolume` 이 체결 수량이다.

주의할 조합:

- **시장가 매수(`price`)** 는 즉시 체결 후 소액 잔량을 환불하며 종료되므로 `wait` 로 장기 잔존하지 않는다. 지정가를 도입한다면 부분체결·잔여주문 취소 로직이 따로 필요하다.
- **`wait` + `executedVolume>0`** 일 때 미체결 잔량은 `locked` 로 묶인다. 이때 free balance 만 보면 "청산 완료"로 오판한다 — 우리 주문의 미체결 잔량만큼은 `locked` 도 보유로 세야 한다(위 계좌 절의 상한 규칙).

## 시세

- **WS ticker** — 실시간 체결가 스트림. 연결이 살아 있어도 데이터가 끊기는 half-open 이 실제로 발생하므로 워치독이 붙어 있다([[marketdata-pipeline]]).
- **REST 캔들** — `/v1/candles/days` 는 한 번에 최대 200개, `to` 파라미터로 페이지네이션한다. **D1 봉 경계는 KST 09:00(=UTC 00:00)** 이며, 이 때문에 일일 리셋 기준과 봉 open 이 정합한다.
- 분봉(M1)은 폴링으로 60초마다 수집한다.

## 에러

`UpbitApiException` 을 `UpbitErrorHandlerAdvice` 가 사용자 친화적 4xx 로 변환한다. **raw 401 을 그대로 노출하지 않는다** — 프런트엔드가 401 을 자동 로그아웃으로 처리하기 때문에, 거래소 인증 실패가 사용자 세션 로그아웃으로 둔갑하는 것을 막기 위함이다.
