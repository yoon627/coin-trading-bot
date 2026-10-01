---
title: DB 스키마 — Flyway V1~V28 와 Upbit 핵심 테이블
category: concept
created: 2026-07-28
updated: 2026-10-01
claim_state: current
verified: 2026-10-01 — V23 행: 적립 프로파일 제거(MVP 2단계) 뒤 적립 전용 4컬럼은 엔티티가 매핑하지 않음(`TradingStateEntity`), 나머지 3컬럼의 사용처(`PositionManager`·`UnknownOrderResolver`)를 rg 로 확인 · 2026-10-01 — V24 행의 그림자 관측 목적을 지운 리포트 인용 없이 모델 청산가 기준으로 다시 적음(ShadowExitProperties KDoc 과 같은 문장) · 2026-09-28 — reconcile 로 늦게 확정된 매도(uuid·identifier·적립 단·부분 체결 뒤 2차 매도)의 기록 price·pnl·totalAmount 가 판단 tick 가격(`pending_sell_trigger_price`)이고 옛 pending 은 확정 tick 가격으로 떨어짐을 `PositionManagerExtendedTest`·`PositionManagerUnknownOrderTest`·`PositionManagerAccumulateTest` 로 확인, 변이 2종 검출 · 2026-09-28 — V28 을 `scripts/run-db-tests.sh`(실 Postgres 17)로 적용, TradingStateRoundTripTest 3건/skip 0 통과(identifier 두 컬럼 왕복 포함) · 2026-09-16 — V27(KIS DROP)을 `scripts/run-db-tests.sh`(실 Postgres 17)로 V1~V27 순차 적용, DB 통합테스트 5건/skip 0 통과. 빈 테이블이라 DELETE·아카이브는 구문만 증명. 이전 확인분: 2026-09-05 — V24 는 신규 테이블 추가만이라 기존 경로에 영향이 없고 `./gradlew build` 통과로만 확인했다(실제 Postgres 적용은 미실행 — `scripts/run-db-tests.sh` 필요). 이전 확인분: 2026-09-02 — V23 을 scripts/run-db-tests.sh(실제 Postgres 17)로 적용해 TradingStateRoundTripTest 3건/skip 0 통과. 이전 확인분: 2026-08-25 — V1~V22 를 격리 컨테이너에 순차 적용해 확인(V22 `strategy varchar(64)`·`reason varchar(32)` 둘 다 nullable). 이전 확인분: V1~V21 을 실제 Postgres 17 에 순차 적용해 확인(V20 컬럼 타입·NOT NULL·default, V21 pnl_amount 컬럼·백업테이블 2개). 운영 데이터를 재현한 시드로 V21 backfill 귀속 5/5 일치(엔진 2-leg 포함), 재실행 값 변경 0
sources:
  - bot/src/main/resources/db/migration/
  - PROJECT_ANALYSIS.md
  - bot/src/main/kotlin/com/trading/bot/persistence/
---

# DB 스키마

PostgreSQL 17 + **R2DBC**(비동기 드라이버) + Flyway. 현재 최신은 **V28** 다.

| 버전 | 내용 |
|---|---|
| V1~V9 | `trade_records`, `users`, `bot_state`, public profile, discord webhook, `price_snapshots`(V19 에서 제거), admin role, 인덱스 |
| V10 | `market_tickers`, `market_candles` — 시계열 시세 ([[marketdata-pipeline]]) |
| V11 | `trade_executions`, `positions`, `strategy_signals` |
| V12 | `user_exchange_keys`, `bot_configs` — 사용자별 설정 |
| V13 | `bot_configs.trade_mode` 컬럼 |
| V14 | `trading_states` 신설 + `trade_executions.exchange_order_id` + 부분 unique. 미사용 `positions` 제거 |
| V15 | KIS 주문 WAL `stock_order_intent`(V27 에서 제거) |
| V16 | `users.kis_*`(V27 에서 제거) |
| V17 | `bot_state` 거래소별 분리((user_id, exchange) — 유지, 현재 값은 UPBIT 뿐) + KIS WAL 인덱스(V27 에서 테이블째 제거) |
| V18 | KIS `stock_position_state`(V27 에서 제거) |
| V19 | 미사용 `price_snapshots` 제거 — watchlist 가 `market_tickers`/`market_candles` 로 옮겨가 소비자가 없어졌다([[marketdata-pipeline]]) |
| V20 | `trading_states.pending_sell_since`·`pending_sell_alerted` — 막힌 매도 알림의 판정 기준을 카운터에서 경과시간으로 |
| V21 | `trade_records.pnl_amount` 추가 + 매도 기록의 전략 귀속 소급 복구(아래) |
| V22 | `stock_order_intent.strategy`·`reason`(V27 에서 테이블째 제거) |
| V25 | `shadow_exit_observation.live_exit_vwap` — 실체결 단가. V24 는 모델 과대추정폭만 쟀고 남은 절반인 **실행 슬리피지**(판단 tick 가격 vs 실체결)를 여기서 얻는다. nullable 이며 값이 없으면 그 관측은 슬리피지 분모에서 빠진다(0 을 넣으면 "마찰 없음" 오독) |
| V26 | `trade_records.order_amount` — **이 주문의 실체결 대금**(`Σ trades[].funds`, 수수료 미포함). 엔진 BUY 행의 `total_amount` 는 포지션 원가 스냅샷이라 집계·SPA·Discord 가 그것을 주문 금액으로 읽어 부풀려졌다(#146). nullable·백필 없음·롤백 시 DROP 하지 않는다. 경로별 규칙은 [[trade-record-volume-semantics]] |
| V27 | **KIS 경로 제거** — 원자료를 `kis_archive_stock_order_intent`·`kis_archive_stock_position_state`·`kis_archive_users_keys`·`kis_archive_trade_executions` 4테이블에 복사한 뒤 `stock_order_intent`·`stock_position_state` DROP, `users.kis_*` 5컬럼 DROP, `bot_state`·`bot_configs`·`trade_executions` 의 `exchange='KIS'` 행 삭제. PR revert 는 복구가 아니다(Flyway validate 로 기동 실패) — 복구는 아카이브에서 새 마이그레이션으로(V28 은 아래 identifier 가 썼다. identity 재삽입은 `OVERRIDING SYSTEM VALUE` + sequence 재설정). 아카이브는 한 달 보관 후 DROP 예정(GitHub 이슈 소유). `bot_state.exchange` 컬럼·unique 는 Upbit 코드가 쓰므로 유지 |
| V28 | `trading_states.pending_buy_identifier`·`pending_sell_identifier`(VARCHAR(64)) — 주문 전에 남기는 클라이언트 identifier. 응답을 못 받은 주문을 재시작 뒤 거래소에서 찾는 근거다([[trading-engine-loop]] "응답을 못 받은 주문", #227). 컬럼 추가만이라 옛 이미지는 무시하지만, 옛 코드는 identifier 만 있는 pending 을 모르고 다시 주문한다. 그래서 롤백은 **새 이미지 정지 → `SELECT ticker FROM trading_states WHERE pending_buy_identifier IS NOT NULL OR pending_sell_identifier IS NOT NULL` 0 행 확인 → 옛 이미지 기동** 순서다(도는 중에 확인하면 그 사이 새 행이 생긴다). 남은 행은 Upbit 주문 내역으로 확인해, 주문이 있으면 그 uuid 를 `pending_buy_uuid`/`pending_sell_uuid` 로 옮기고 identifier 를 NULL 로, 없으면 identifier 만 NULL 로 비운다(있는 주문을 NULL 로만 지우면 옛 코드가 그 체결을 기록할 근거를 잃는다) |
| V24 | `shadow_exit_observation` — 후보 청산 파라미터의 **그림자 관측**. 라이브 매매에는 관여하지 않고(계산·기록 전용, 기본 off) 모델 청산가(트레일링 임계선 `peak × (1−trail/100)`)가 실제 10초 tick 에서 얼마나 낙관인지만 잰다. 되돌릴 때는 `trading.shadow-exit.enabled=false` 로 끈다(forward-off). #235 관측 보고 배포(2026-09-28) 뒤부터 reconcile 로 늦게 확정된 스윙 청산도 기록되고 그때 `live_exit_at` 은 판단 시각이다 — 그 전 행은 체결 확인 창 안에 끝난 매도만이다(재시작 때 발동 기록이 비므로 배포 시점에 열린 매도는 어느 쪽에도 남지 않는다) |
| V23 | `trading_states` 에 적립 사다리 장부 — `rungs_filled`·`last_action_price`·`flat_peak`·`pending_buy_trigger_price`·`pending_buy_prior_volume`·`pending_sell_trigger_price`·`pending_sell_prior_volume`. 적립 프로파일은 2026-10-01 에 지웠고 앞 네 컬럼은 더 매핑하지 않는다 — insert 는 DB 기본값(NOT NULL DEFAULT 0 셋, nullable 하나), update 는 건드리지 않는다. DROP 은 옛 이미지 롤백이 그 컬럼을 매핑하므로 같은 배포에서 하지 않았다(MVP 3단계). 뒤 세 컬럼은 스윙이 쓴다 — `pending_buy_prior_volume`·`pending_sell_prior_volume` 은 주문 전 보유량(잔고 복원·응답 못 받은 주문 판정, #227), `pending_sell_trigger_price` 는 매도 판단가(2026-09-28, #235 — 매도 기록가, [[trade-record-volume-semantics]]) |

> ⚠️ `trade_records.volume` 은 기록 경로에 따라 **총 보유량 스냅샷**(엔진)과 **증분**(수동)이 섞인다.
> 합산하면 조회·집계가 조용히 틀린다 — [[trade-record-volume-semantics]] 참조.

## 매도 기록의 전략 귀속 (V21)

`buildSellRecord` 가 `TradeRecord` 를 만들 때 `strategy` 인자를 넘기지 않아, V21 이전의 **매도 기록은 전부 `strategy=NULL`** 이었다. `pnl_percent` 를 가진 side 가 SELL 뿐이라 `/api/strategies/performance` 는 손익 전량을 `unknown` 그룹에 넣어 왔다 — 이 API 는 만들어진 이래 전략별 손익을 보여준 적이 없다.

소급 귀속 규칙은 **포지션 구간(직전 SELL 이후) 내 첫 번째 non-manual BUY** 다. 두 가지가 비자명하다.

**왜 첫 번째인가** — `markBought` 의 실제 분기는 `entryStrategy = if (resuming) entryStrategy ?: strategy else strategy` 이고 `resuming` 은 진입 시점의 `position` 이다. `completeBuy` 가 `replace=true` 로 부르므로 "추가매수 시 유지" 가지(`position && !replace`)는 타지 않지만, **else 안에서 `resuming` 이 참이면 기존 값이 그대로 살아남는다**. 재시작 후 `syncPosition` 이 `position=true` 로 만든 뒤 `reconcilePendingBuy`·`BalanceRecovery` 가 `completeBuy` 를 부르는 경로가 그렇다. 즉 한 포지션에 엔진 BUY 가 여럿이면 **먼저 찍힌 전략**이 남는다.

**왜 `manual` 을 빼는가**(V21 시점 서술 — 수동 매수는 2026-09-16 에 제거됐다, #129) — 당시 수동 매수(`executeBuy`)는 `TradingState` 를 아예 건드리지 않고 `syncPosition` 도 `entryStrategy` 를 세우지 않는다. 그래서 수동 매수 위에 엔진이 매수하면 `resuming=false` 로 엔진 전략이 들어간다 — `manual` 은 애초에 `entryStrategy` 후보가 아니다.

⚠️ **원금은 반대로 마지막 BUY 를 본다.** 전략은 최초 진입값이 유지되는 반면 `avgBuyPrice` 는 `markBought` 가 매번 덮어쓰기 때문이다. 두 기준이 다른 것은 런타임을 미러한 결과다.

이 페어링은 코드가 보장하는 불변식이 아니라 적용 시점 데이터의 성질이다 — 부분매도(와 2026-09-28 제거 전의 수동 수량 지정 매도)는 1:1 을 깰 수 있다. 전제가 어긋나는 행은 상관 서브쿼리가 NULL 을 돌려 건드리지 않고 넘어가고, 마이그레이션 끝의 점검 블록이 남은 미귀속 수를 `RAISE WARNING` 으로 알린다(`EXCEPTION` 이면 정상적인 미귀속에도 기동이 막혀 배포 자동 롤백이 걸린다). 원본은 `*_v21_backup` 테이블에 남는다. 이 백업을 언제 거둘 수 있는지의 판단 규칙은 [[lesson-rollback-removal]] 에 있다.

대상에 `id`·시각 상한을 두지 **않는다**. 마이그레이션이 도는 시점은 새 앱 기동 시이고 그때 `strategy` 가 빈 매도 행은 정의상 전부 구버전 코드가 쓴 것이다. 측정 시점의 max id 로 고정하면 측정과 배포 사이에 체결된 거래가 영구 미보정으로 남는다 — 봇은 그 사이에도 돈다. 다만 **페어링 순서와 tie-break 은 `created_at` 이 아니라 `id`** 로 한다: 두 테이블이 서로 다른 시각을 담고 타입도 다르며(`TIMESTAMP` vs `TIMESTAMPTZ`, 같은 리터럴이 세션 TimeZone 에 따라 다르게 해석된다) 마이크로초 동률도 가능하기 때문이다.

**수동 매도는 2026-09-28 에 제거됐다.** 그 전(2026-09-16~)의 수동 매도 행은 진입 전략으로 귀속됐고 모를 때만 `"manual"` 이다(#129, [[trade-record-volume-semantics]] 구분 키 절).

⚠️ **`trade_executions.fee` 는 V21 부터만 채워진다.** 그 이전 행은 `0`(미기록)이다 — `saveAudit` 이 값을 넘기지 않았다. 소급하지 않은 이유는 수수료율이 `TRADING_ROUND_TRIP_FEE_RATE` 로 환경마다 다를 수 있어 SQL 에 상수로 박으면 기본값이 아닌 환경에서 과거와 현재가 다른 기준이 되기 때문이다. 총 수수료를 집계할 일이 생기면 V21 이전 행을 제외해야 한다. 채워지는 값의 출처는 경로가 정한다 — 엔진 매수(#133)·엔진 매도(#148, 2026-09-14)는 `getOrder` 응답의 `paid_fee` 실측, 과거 수동 주문 행과 `paid_fee` 없는 매도는 설정값 추정([[trade-record-volume-semantics]] 수수료 절). `pnl_amount` 는 원금 기준 요율 추정이라 `fee` 와 더하지 않는다.

다음 마이그레이션 번호를 정하는 규칙은 [[migration-numbering]] 에 있다 — 미머지 브랜치가 번호를 선점하는 문제가 실제로 있었다.

## `trading_states` (V14)

per-(user, ticker) 거래 상태를 durable 하게 보관한다. 이게 없으면 재시작·배포 때마다 다음이 증발한다:

- `pendingBuyUuid` / `pendingSellUuid` — 미해소 주문. 유실되면 아무도 reconcile 하지 않는 orphan 주문이 남는다.
- `pendingBuyIdentifier` / `pendingSellIdentifier`(V28) — uuid 를 받기 전 단계의 같은 역할. 주문 **전에** 기록돼, 응답을 못 받은 주문을 identifier 조회로 확정한다. uuid 를 알게 되면 비운다.
- `peakPrice` — 트레일링 스톱의 기준선. 0 에서 다시 쌓이면 이미 발동했어야 할 청산이 안 걸린다.
- `halted` / `reconcileFailureCount` — 재시작으로 halt 가 풀려 장애 중 재진입하는 것을 막는다.
- `entryStrategy` — 매도 기록의 전략 귀속(`buildSellRecord`, [[exit-gates]])과 목록 밖 잔류의 진입 흔적(#226, [[trading-engine-loop]]).
- `exitParams` 스냅샷 — 진입 시점 청산 파라미터. 익절·손절·트레일링·보유상한이 이 값(없으면 전역)으로 판정한다(2026-09-06~, #177 — [[exit-gates]] "진입 시점 스냅샷을 따른다"). 보유 중 설정을 바꿔도 열린 포지션은 진입 때 규칙이다.

## `trade_executions.exchange_order_id` 부분 unique

재시작 후 reconcile 이 같은 체결을 다시 기록하는 것을 DB 레벨에서 막는 **멱등 키**다. 다만 이건 *중복 insert* 만 막고, *기록이 아예 없었던* 방향은 막지 못한다 — 응답을 못 받은 주문이 기록 없이 사라지던 경로와 그 확정 규칙은 [[trading-engine-loop]] 의 "응답을 못 받은 주문" 참조.

## 성질

- **R2DBC 를 고수해야 한다.** 블로킹 JDBC 를 섞으면 WebFlux 이벤트 루프가 막힌다.
- `DataRetentionService` 가 `market_tickers` 와 `market_candles`(M1)를 주기 정리한다. M1 정리를 빼면 분봉이 무한 증가한다.
- 사용자 거래소 키는 AES-GCM 256 으로 암호화해 저장한다(`SecretsCrypto`).
