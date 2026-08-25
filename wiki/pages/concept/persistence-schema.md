---
title: DB 스키마 — Flyway V1~V22 와 Upbit·KIS 핵심 테이블
category: concept
created: 2026-07-28
updated: 2026-08-25
claim_state: current
verified: 2026-08-25 — V1~V22 를 격리 컨테이너에 순차 적용해 확인(V22 `strategy varchar(64)`·`reason varchar(32)` 둘 다 nullable). 이전 확인분: V1~V21 을 실제 Postgres 17 에 순차 적용해 확인(V20 컬럼 타입·NOT NULL·default, V21 pnl_amount 컬럼·백업테이블 2개). 운영 데이터를 재현한 시드로 V21 backfill 귀속 5/5 일치(엔진 2-leg 포함), 재실행 값 변경 0
sources:
  - bot/src/main/resources/db/migration/
  - PROJECT_ANALYSIS.md
  - bot/src/main/kotlin/com/trading/bot/persistence/
---

# DB 스키마

PostgreSQL 17 + **R2DBC**(비동기 드라이버) + Flyway. 현재 최신은 **V22** 다.

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
| V21 | `trade_records.pnl_amount` 추가 + 매도 기록의 전략 귀속 소급 복구(아래) |
| V22 | `stock_order_intent.strategy`·`reason` — KIS 체결 기록의 전략·사유 귀속(#130). 값을 주문 시점 WAL 에 실어 reconcile 경합을 피한다([[kis-order-lifecycle]]) |

> ⚠️ `trade_records.volume` 은 기록 경로에 따라 **총 보유량 스냅샷**(엔진)과 **증분**(수동)이 섞인다.
> 합산하면 조회·집계가 조용히 틀린다 — [[trade-record-volume-semantics]] 참조.

## 매도 기록의 전략 귀속 (V21)

`buildSellRecord` 가 `TradeRecord` 를 만들 때 `strategy` 인자를 넘기지 않아, V21 이전의 **매도 기록은 전부 `strategy=NULL`** 이었다. `pnl_percent` 를 가진 side 가 SELL 뿐이라 `/api/strategies/performance` 는 손익 전량을 `unknown` 그룹에 넣어 왔다 — 이 API 는 만들어진 이래 전략별 손익을 보여준 적이 없다.

소급 귀속 규칙은 **포지션 구간(직전 SELL 이후) 내 첫 번째 non-manual BUY** 다. 두 가지가 비자명하다.

**왜 첫 번째인가** — `markBought` 의 실제 분기는 `entryStrategy = if (resuming) entryStrategy ?: strategy else strategy` 이고 `resuming` 은 진입 시점의 `position` 이다. `completeBuy` 가 `replace=true` 로 부르므로 "추가매수 시 유지" 가지(`position && !replace`)는 타지 않지만, **else 안에서 `resuming` 이 참이면 기존 값이 그대로 살아남는다**. 재시작 후 `syncPosition` 이 `position=true` 로 만든 뒤 `reconcilePendingBuy`·`BalanceRecovery` 가 `completeBuy` 를 부르는 경로가 그렇다. 즉 한 포지션에 엔진 BUY 가 여럿이면 **먼저 찍힌 전략**이 남는다.

**왜 `manual` 을 빼는가** — 수동 매수(`TradeExecutionService.executeBuy`)는 `TradingState` 를 아예 건드리지 않고 `syncPosition` 도 `entryStrategy` 를 세우지 않는다. 그래서 수동 매수 위에 엔진이 매수하면 `resuming=false` 로 엔진 전략이 들어간다 — `manual` 은 애초에 `entryStrategy` 후보가 아니다.

⚠️ **원금은 반대로 마지막 BUY 를 본다.** 전략은 최초 진입값이 유지되는 반면 `avgBuyPrice` 는 `markBought` 가 매번 덮어쓰기 때문이다. 두 기준이 다른 것은 런타임을 미러한 결과다.

이 페어링은 코드가 보장하는 불변식이 아니라 적용 시점 데이터의 성질이다 — 부분매도와 수동 `executeSellVolume` 은 이미 오늘도 1:1 을 깰 수 있다. 전제가 어긋나는 행은 상관 서브쿼리가 NULL 을 돌려 건드리지 않고 넘어가고, 마이그레이션 끝의 점검 블록이 남은 미귀속 수를 `RAISE WARNING` 으로 알린다(`EXCEPTION` 이면 정상적인 미귀속에도 기동이 막혀 배포 자동 롤백이 걸린다). 원본은 `*_v21_backup` 테이블에 남는다.

대상에 `id`·시각 상한을 두지 **않는다**. 마이그레이션이 도는 시점은 새 앱 기동 시이고 그때 `strategy` 가 빈 매도 행은 정의상 전부 구버전 코드가 쓴 것이다. 측정 시점의 max id 로 고정하면 측정과 배포 사이에 체결된 거래가 영구 미보정으로 남는다 — 봇은 그 사이에도 돈다. 다만 **페어링 순서와 tie-break 은 `created_at` 이 아니라 `id`** 로 한다: 두 테이블이 서로 다른 시각을 담고 타입도 다르며(`TIMESTAMP` vs `TIMESTAMPTZ`, 같은 리터럴이 세션 TimeZone 에 따라 다르게 해석된다) 마이크로초 동률도 가능하기 때문이다.

⚠️ **수동 매도는 여전히 귀속을 틀린다** — `executeSellAll`/`executeSellVolume` 이 `strategy="manual"` 을 하드코딩해서, 엔진이 잡은 포지션을 사람이 청산하면 진입 전략이 크레딧을 못 받는다(Upbit 경로). **KIS 경로는 V22 에서 해소됐다** — 주문 WAL 이 전략·사유를 싣고 `buildExecution` 이 그대로 옮긴다([[kis-order-lifecycle]]). 다만 "수동 매도가 엔진 포지션을 청산했을 때 진입 전략을 크레딧한다"는 문제는 양쪽 모두 미해결이다.

⚠️ **`trade_executions.fee` 는 V21 부터만 채워진다.** 그 이전 행은 `0`(미기록)이다 — `saveAudit` 이 값을 넘기지 않았다. 소급하지 않은 이유는 수수료율이 `TRADING_ROUND_TRIP_FEE_RATE` 로 환경마다 다를 수 있어 SQL 에 상수로 박으면 기본값이 아닌 환경에서 과거와 현재가 다른 기준이 되기 때문이다. 총 수수료를 집계할 일이 생기면 V21 이전 행을 제외해야 한다. 채워지는 값도 **체결 응답의 실제 수수료가 아니라 설정값 기반 추정**이다(`Order` 가 Upbit `paid_fee` 를 파싱하지 않는다).

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
