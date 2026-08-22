---
title: DB 스키마 — Flyway V1~V20 와 Upbit·KIS 핵심 테이블
category: concept
created: 2026-07-28
updated: 2026-08-22
claim_state: current
verified: 2026-08-22 — V20 까지 실제 Postgres 16 에 순차 적용해 컬럼 타입·NOT NULL·default 확인
sources:
  - bot/src/main/resources/db/migration/
  - PROJECT_ANALYSIS.md
  - bot/src/main/kotlin/com/trading/bot/persistence/
---

# DB 스키마

PostgreSQL 17 + **R2DBC**(비동기 드라이버) + Flyway. 현재 최신은 **V20** 이다.

| 버전 | 내용 |
|---|---|
| V1~V9 | `trade_records`, `users`, `bot_state`, public profile, discord webhook, `price_snapshots`(V19 에서 제거), admin role, 인덱스 |
| V10 | `market_tickers`, `market_candles` — 시계열 시세 ([[marketdata-pipeline]]) |
| V11 | `trade_executions`, `positions`, `strategy_signals` |
| V12 | `user_exchange_keys`, `bot_configs` — 사용자별 설정 |
| V13 | `bot_configs.trade_mode` 컬럼 |
| V14 | `trading_states` 신설 + `trade_executions.exchange_order_id` + 부분 unique. 미사용 `positions` 제거 |
| V15 | KIS 주문 WAL인 `stock_order_intent`와 활성 주문 partial unique index |
| V16 | 사용자별 KIS 키·계좌번호·모의투자 여부(`users.kis_*`) |
| V17 | `bot_state` 거래소별 분리 + KIS WAL 활성 unique key에 `side` 추가 |
| V18 | KIS 포지션 메타데이터(`stock_position_state`) durable snapshot |
| V19 | 미사용 `price_snapshots` 제거 — watchlist 가 `market_tickers`/`market_candles` 로 옮겨가 소비자가 없어졌다([[marketdata-pipeline]]) |
| V20 | `trading_states.pending_sell_since`·`pending_sell_alerted` — 막힌 매도 알림의 판정 기준을 카운터에서 경과시간으로 |

다음 마이그레이션 번호를 정하는 규칙은 [[migration-numbering]] 에 있다 — 미머지 브랜치가 번호를 선점하는 문제가 실제로 있었다.

## `trading_states` (V14)

per-(user, ticker) 거래 상태를 durable 하게 보관한다. 이게 없으면 재시작·배포 때마다 다음이 증발한다:

- `pendingBuyUuid` / `pendingSellUuid` — 미해소 주문. 유실되면 아무도 reconcile 하지 않는 orphan 주문이 남는다.
- `peakPrice` — 트레일링 스톱의 기준선. 0 에서 다시 쌓이면 이미 발동했어야 할 청산이 안 걸린다.
- `halted` / `reconcileFailureCount` — 재시작으로 halt 가 풀려 장애 중 재진입하는 것을 막는다.
- `entryStrategy` — 진입 전략으로 청산을 평가하기 위해([[exit-gates]]). **실제로 소비되는 건 이 필드뿐이다.**
- `exitParams` 스냅샷 — 진입 시점 청산 파라미터. **저장·복원만 되고 청산 판정에는 쓰이지 않는다**(소비는 strategy-evolution Phase 2). 보유 중 설정을 바꾸면 열린 포지션에도 즉시 적용된다 — [[exit-gates]] 의 경고 참조.

## `trade_executions.exchange_order_id` 부분 unique

재시작 후 reconcile 이 같은 체결을 다시 기록하는 것을 DB 레벨에서 막는 **멱등 키**다. 다만 이건 *중복 insert* 만 막고, *기록이 아예 없었던* 방향은 막지 못한다 — 감사 기록 유실 경로는 [[trading-engine-loop]] 의 "알려진 갭" 참조.

## KIS 주문·포지션 상태

KIS 주문은 `stock_order_intent`에 송신 전에 기록한다. `SUBMITTING`, `PLACED`, `PARTIAL`, `UNKNOWN`, `NEEDS_REVIEW` 같은 비종료 상태를 남겨 프로세스가 주문 직후 죽어도 [[kis-order-lifecycle]]의 reconcile이 이어받을 수 있게 한다. V17의 `side` 포함 partial unique index는 같은 종목의 활성 BUY와 SELL을 각각 하나씩 허용하고 같은 side 중복만 막는다.

`stock_position_state`에는 거래소 잔고가 아닌 엔진 메타데이터만 저장한다. 보유수량·평단은 재시작 시 KIS `getHoldings()`가 진실이므로 저장하지 않고, 트레일링 고점(`peak_price`), 당일 매수 근거(`bought_date`), 진입 전략(`entry_strategy`)만 복원한다([[kis-stock-trading-flow]]).

KIS 앱 키·시크릿은 사용자별로 암호화 저장하고, `kis_paper`가 모의투자 도메인·tr_id 선택을 결정한다. 서버의 `KIS_LIVE_ENABLED=false`는 주문을 KIS에 송신하지 않고 WAL에 `DRY_RUN`만 기록하는 별도 안전축이다.

## 성질

- **R2DBC 를 고수해야 한다.** 블로킹 JDBC 를 섞으면 WebFlux 이벤트 루프가 막힌다.
- `DataRetentionService` 가 `market_tickers` 와 `market_candles`(M1)를 주기 정리한다. M1 정리를 빼면 분봉이 무한 증가한다.
- 사용자 거래소 키는 AES-GCM 256 으로 암호화해 저장한다(`SecretsCrypto`).
