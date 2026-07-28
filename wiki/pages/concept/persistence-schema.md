---
title: DB 스키마 — Flyway V1~V14 와 핵심 테이블
category: concept
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — bot/src/main/resources/db/migration/ 파일 목록 실측, PROJECT_ANALYSIS.md 표 대조
sources:
  - bot/src/main/resources/db/migration/
  - PROJECT_ANALYSIS.md
---

# DB 스키마

PostgreSQL 17 + **R2DBC**(비동기 드라이버) + Flyway. 현재 최신은 **V14** 다.

| 버전 | 내용 |
|---|---|
| V1~V9 | `trade_records`, `users`, `bot_state`, public profile, discord webhook, `price_snapshots`, admin role, 인덱스 |
| V10 | `market_tickers`, `market_candles` — 시계열 시세 ([[marketdata-pipeline]]) |
| V11 | `trade_executions`, `positions`, `strategy_signals` |
| V12 | `user_exchange_keys`, `bot_configs` — 사용자별 설정 |
| V13 | `bot_configs.trade_mode` 컬럼 |
| V14 | `trading_states` 신설 + `trade_executions.exchange_order_id` + 부분 unique. 미사용 `positions` 제거 |

다음 마이그레이션 번호를 정하는 규칙은 [[migration-numbering]] 에 있다 — 미머지 브랜치가 번호를 선점하는 문제가 실제로 있었다.

## `trading_states` (V14)

per-(user, ticker) 거래 상태를 durable 하게 보관한다. 이게 없으면 재시작·배포 때마다 다음이 증발한다:

- `pendingBuyUuid` / `pendingSellUuid` — 미해소 주문. 유실되면 아무도 reconcile 하지 않는 orphan 주문이 남는다.
- `peakPrice` — 트레일링 스톱의 기준선. 0 에서 다시 쌓이면 이미 발동했어야 할 청산이 안 걸린다.
- `halted` / `reconcileFailureCount` — 재시작으로 halt 가 풀려 장애 중 재진입하는 것을 막는다.
- `entryStrategy`, `exitParams` 스냅샷 — 진입 시점 기준으로 청산하기 위해([[exit-gates]]).

## `trade_executions.exchange_order_id` 부분 unique

재시작 후 reconcile 이 같은 체결을 다시 기록하는 것을 DB 레벨에서 막는 **멱등 키**다. 다만 이건 *중복 insert* 만 막고, *기록이 아예 없었던* 방향은 막지 못한다 — 감사 기록 유실 경로는 [[trading-engine-loop]] 의 "알려진 갭" 참조.

## 성질

- **R2DBC 를 고수해야 한다.** 블로킹 JDBC 를 섞으면 WebFlux 이벤트 루프가 막힌다.
- `DataRetentionService` 가 `market_tickers` 와 `market_candles`(M1)를 주기 정리한다. M1 정리를 빼면 분봉이 무한 증가한다.
- 사용자 거래소 키는 AES-GCM 256 으로 암호화해 저장한다(`SecretsCrypto`).
