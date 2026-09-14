---
title: trade-order-amount — "이 주문의 체결 금액"을 기록하고 스냅샷 소비처 3곳을 바꾼다 (#146)
status: done
started: 2026-09-14
updated: 2026-09-15
---

# Goal

`trade_records` 의 엔진 BUY 행은 `volume`·`total_amount` 가 **포지션 스냅샷**(총 보유량·전체 원가)이라, 그것을 "이번 주문 금액"으로
읽는 소비처 3곳(전략별 집계 SUM·SPA 거래목록 총액·Discord 매수 알림 금액)이 부풀려진 값을 보여준다(#146).
주문 응답의 `trades[].funds` 합으로 얻는 **이 주문의 체결 대금**을 새 컬럼 `order_amount` 에 남기고, 3곳이 그것을 우선 읽게 한다.

# Progress

- 2026-09-14 — worktree 생성(base 로컬 `main@225c906`). Explore: 기록 경로(TradeRecord→Repository.save→Entity), 소비처 3곳, 마이그레이션 관례(V25 nullable 컬럼, 추정 금지), 기존 테스트(OrderFilledVwapTest·PositionManagerExtendedTest·TradeRecordRepositoryTest·DiscordNotifierTest) 확인.
- 2026-09-15 — PR #194(#131·#148 로컬 ff-merge 커밋 2건 동반). CI DB 통합테스트가 Acceptance 7 증거.
- 2026-09-14 — 최종 검증 `:bot:test` 1060건 중 실패 1 = baseline #193, DB 통합 2건 로컬 skip(CI). 구현·테스트(단위 12건 신규/갱신, DB 통합 2건은 CI). arch(정밀)·code-reviewer(+codex) 지적 전부 처분(Disposition). wiki 3·PROJECT_ANALYSIS·README 동기화, JSX 파싱 실측.
- 2026-09-14 — plan-reviewer(+codex)·architecture-reviewer 검토 → Decisions 2~6 정정(수동=null·terminal 한정·SELL 통일·집계 정의·DB 테스트 하네스 존재). researcher: Upbit `trades[].funds` = price×volume(수수료 미포함, ✅ 공식문서), Σfunds+paid_fee=총차감 등식은 문서 미명시(⚠️). Deferred 4건.

# Next

없음 — PR #194 로 종결. 배포 후 SPA 렌더 1회 확인(Acceptance 10)은 운영 확인 사항으로 남긴다.

# Intent

- Problem: 엔진 BUY 행의 `total_amount` 는 그 주문이 아니라 포지션 전체 원가다(#20 의 의도된 스냅샷). SUM 은 앞선 매수를 되풀이 합산하고, SPA·Discord 는 "5만원 추가매수" 에 "100만원" 을 보여준다. Discord 가 사용자 체감이 가장 크다.
- Constraints:
  - 기존 `volume`·`total_amount` 의 의미(스냅샷)는 **바꾸지 않는다** — `syncPosition` 이중계상 방지(#20)·`TradeRoundTrip.BuySide`·V21 백필이 그 의미에 기대고 있다.
  - 얻을 수 없으면 **추정하지 않는다**(null). 이 repo 의 `paid_fee`·`executedVwap` 규율과 동일 — `price×volume` 재계산은 스냅샷을 다시 넣는 것과 같다.
  - 과거 행은 소급하지 않는다(엔진 BUY 의 "이 주문 금액"은 기록에 없어 복원 불가).
  - `trade_executions` 는 건드리지 않는다 — 이슈의 소비처 3곳은 전부 `trade_records` 를 읽는다.
- Out of scope: `#147`(부분 매도 후 재매수 이중 계상)·`#145`(strategy=null)·`#105`(수동 매도 요청수량) — 같은 스냅샷 계열이지만 각각 별도 판단. 수동 경로는 `orderAmount=null` 명시만(사용자: 수동매매 미사용 전제, Decision 2).
- Open questions: 없음 (funds 정의는 researcher 로 확정, 위 Progress).

# Decisions

## 1) 새 컬럼 `order_amount`(nullable), 기존 컬럼 의미 보존

`total_amount` 를 "이 주문 금액"으로 바꾸면 스냅샷에 기대는 `BuySide`·V21·재시작 정합이 전부 흔들린다. 스냅샷은 그대로 두고 **주문 단위 값을 옆에 둔다**. V25 `live_exit_vwap` 과 같은 nullable 관례 — 없으면 null, 0 을 넣지 않는다(0 은 "무료" 로 오독).

## 2) 값의 출처는 경로별로

| 경로 | `orderAmount` |
|---|---|
| 엔진 매수·매도, 주문 응답이 **terminal(done/cancel)** | `Order.filledFunds()` = `Σ trades[].funds` (하나라도 파싱 불가·비유한·음수·volume≤0 이면 null) |
| 엔진 매수, 응답이 `wait`+부분체결(폴링 소진) | null — `completeBuy` 가 pending 을 해소해 최종 funds 로 갱신할 경로가 없다(plan-review 강한우려 3) |
| 엔진 잔고복원(매수 `recoverFromBalance`·매도 `recoverSellFromBalance`) | null — 주문 응답 없음 |
| 수동 매수·매도 | **null** — 요청액(`amount`)·tick×요청수량은 실측이 아니고, 출처 마커 없는 컬럼에 섞이면 수수료에서 겪은 "구분 불가"가 재발한다. 사용자가 수동매매를 쓰지 않아 비용 0. |

`funds` 는 Upbit 정의상 price×volume(수수료 미포함) — 즉 `orderAmount` 는 **수수료 제외 체결 대금**이다. `filledFunds` 는 `filledVwap` 과 같은 순회·같은 규율(비유한·음수·volume≤0 → null)을 공유한다 — funds 만 필요해도 volume 검증을 상속하는 것은 fail-closed 선택이다.
`TradeRecord.orderAmount` 는 **기본값 없음**(`fee` 규율 — 배선 누락이 컴파일을 통과하면 집계에서 조용히 빠져 이슈가 고쳐진 척 닫힌다). 엔티티 쪽은 DB 미러라 `= null`.

### 기각한 대안
- `total_amount` 의미 변경 — `BuySide`·V21·재시작 정합이 스냅샷에 기댄다.
- `executed_volume`+`executed_vwap` 저장 후 곱하기 — 반올림 누적(V25 가 `funds` 를 택한 이유와 동일), 컬럼 2개·소비처가 곱해야 한다.
- view / `trade_executions` 재사용 — 소비처 3곳이 전부 `trade_records` 를 읽고 `trade_executions.total_amount` 도 같은 스냅샷이라 해결이 아니다.
- 수동 경로에 요청액 기록 — 위 표 사유.
- `OrderFill` 값객체로 `Order` 파생값 묶기 — arch 권고, 이번엔 스칼라 인자 유지(`# Deferred`).

## 3) 소비처 3곳 — `order_amount` 가 있는 행만 실측, 없으면 "미상"을 드러낸다

SELL 의 `total_amount` 도 "그 체결의 대금"이 **아니다** — `currentPrice(판단 tick) × volume` 평가액이다(`buildSellRecord`). 그래서 BUY/SELL 구분 없이 같은 규칙:

- **집계 SQL**: `total_amount` = `SUM(order_amount)`(실측만), 추가로 `amount_unknown_trades` = `COUNT(*) FILTER (WHERE order_amount IS NULL)` 를 내려 소비자가 "일부 미상"을 알 수 있게 한다. 과거 행·잔고복원·수동은 전부 미상. 전략명 CASE 는 두지 않는다(수동이 null 이라 발화할 행이 없다). `StrategyPerformance.totalAmount` 의 의미 변경("스냅샷 합"→"실측 체결대금 합")은 KDoc 에.
- **SPA** `/api/trades` 목록: `order_amount` 가 있으면 그 값, 없으면 `total_amount` 를 흐리게 + title "주문 금액 미상 — 기록 시점 포지션 평가액". 열 이름은 중립 "금액". `??` 사용(`||` 는 0 을 통과시킨다).
- **Discord**: `orderAmount` 있으면 "체결금액", 없으면 출처 중립 "기록 금액(체결 미상)" 라벨로 `totalAmount` — 폴백이 발화하는 경로(잔고복원·wait 확정·**수동 매수**)마다 그 값의 의미가 달라(원가 스냅샷/평가액/요청액) side 별 라벨도 어느 한 경로에선 거짓이다(code-review 지적으로 side 별 라벨 기각).
- **유지**: `TradeRoundTrip`(스냅샷 규칙 위에 선 조립·매도 합산), `LeaderboardController`(pnl_percent 만), KIS(`trade_records` 미사용) — 영향 밖.
- `/api/trades` 는 엔티티를 그대로 직렬화하므로 응답에 `order_amount` 키가 **추가**된다(호환 변경).

## 4) 검증 — 실 Postgres 하네스로 V26 매핑·집계 SQL 을 덮는다

`TradingStateRoundTripTest`(`@DataR2dbcTest` + `TEST_DB_*` + Flyway) 가 이미 있고 CI 가 `DB_TESTS_REQUIRED=true` 로 skip 을 실패로 만든다. 같은 패턴의 `TradeRecordAggregateRoundTripTest` 를 추가한다: `save()`→`order_amount` 재조회, 엔진 BUY(NULL)·신규 BUY·SELL seed 후 `aggregateByStrategy` 의 `total_amount`·`amount_unknown_trades` 단언. `scripts/run-db-tests.sh`·`deploy.yml` 의 XML 가드가 한 클래스로 하드코딩돼 있어 두 클래스를 함께 보게 확장한다. **로컬은 docker 데몬 미기동이라 못 돌린다** — PR CI 가 증거(그래서 ff-merge 가 아니라 `/e merge`).

## 5) 롤백

V26 은 additive nullable — down 스크립트를 두지 않고, 앱 롤백 시 컬럼을 **남긴다**(`DROP COLUMN` 은 신규 데이터 유실). R2DBC 는 엔티티 프로퍼티만 SELECT/INSERT 하므로 구버전 앱도 그대로 동작한다. 순서: 앱 먼저.

## 6) 사용자-visible 변화(배포 노트)

전략별 성과의 `total_amount` 는 과거 행이 전부 미상이라 배포 직후 **0 근처에서 다시 쌓인다**. 축소가 아니라 정의 변경이다.

# Review Disposition (2026-09-14)

## architecture-reviewer(정밀)
- **fix** `amount_unknown_trades`·`total_amount` 를 SPA 전략 성과 카드가 렌더하지 않음 → 카드 부가정보 줄에 "체결 금액 (미상 N건 제외)" 추가, KDoc 단언 정정.
- **fix** `StrategyPerformance` KDoc 이중(앞 주석 dangling, strategy=null 설명 유실) → 병합.
- **fix→재결정** SPA 폴백 title 이 BUY 행에 "평가액"으로 틀림 → side 별 문구로 고쳤다가 code-review 가 수동 BUY(요청액)에서 또 틀림을 지적 → 출처 중립 문구로 통일(Discord 동일).
- **fix** terminal 판정 리터럴이 엔진에 3사본 → `Order.isTerminal()` 추출, `terminalFunds`·`awaitFill` 이 사용.
- **defer** DB 테스트 게이트(`run-db-tests.sh`·`deploy.yml`) 목록·판정 이중화 → `scripts/assert-db-tests-ran.sh` 단일 소스화. `# Deferred`.
- **defer** Postgres 하네스 boilerplate 2사본 → 3번째 클래스 전에 공용 base. `# Deferred`.

## code-reviewer(+codex high)
- **fix** 수동 BUY 폴백 라벨 "보유 원가" 오표기 → 출처 중립 라벨(위).
- **fix** `/api/strategies/performance` 응답 변경(`amount_unknown_trades` 추가·`total_amount` 의미)이 README 미반영 → README 집계 한계 절에 한 줄.
- **defer** `reconcilePendingSell` 광범위 catch 가 실측 funds 를 버리고 잔고복원(null)로 확정 → #148 에서 이미 `# Deferred`(선행 결함), orderAmount 도 같은 축.
- **미검증 명시** SPA(JSX 런타임 babel)는 로컬 실행 불가(Postgres 없음) → 수동 절차를 Acceptance 10 에.
- nit fix: `filledTotals()?.first`, `amountUnknownTrades` KDoc 에 BUY/SELL 합산 명시. nit 유지: `pipefail`+`grep` 조기 종료(기존 동작), 수수료 미포함 단서는 KDoc·wiki 에만.

# Acceptance

1. `Order.filledFunds()` — 정상 합·trades 없음 null·비유한/음수 null — `OrderFilledVwapTest` 통과.
2. 엔진 매수: (a) done + **거래소에 기존 보유 0.02 BTC 가 있는 계좌**(completeBuy 가 실잔고로 스냅샷을 채움) 에서 `orderAmount` = 이번 주문 Σfunds ≠ `totalAmount`(스냅샷), (b) cancel+executed 도 Σfunds, (c) `wait`+executed 는 null — `PositionManagerExtendedTest` 통과.
3. 엔진 매도: 즉시 done·reconcile 은 Σfunds, 잔고복원은 null — 같은 파일.
4. 수동 매수·매도 `orderAmount == null` — `TradeExecutionServiceTest` 통과.
5. `TradeRecordRepository.save` 가 `orderAmount` 를 엔티티에 실음 — `TradeRecordRepositoryTest`(mock) 통과.
6. Discord: orderAmount 있음 → "체결금액", 없음 → "기록 금액(체결 미상)"(side 무관) — `DiscordNotifierTest` 통과.
7. **DB 통합**: `TradeRecordAggregateRoundTripTest` 가 V26 컬럼 왕복 + `aggregateByStrategy` 의 `total_amount`(실측 합)·`amount_unknown_trades` 를 단언. 로컬 docker 부재로 **PR CI 통과가 증거**(`run-db-tests.sh`·`deploy.yml` 가드 확장 포함).
8. `./gradlew :bot:test`·`compileKotlin` 통과(#193 baseline 제외).
9. 문서: wiki `trade-record-volume-semantics`(새 컬럼·규칙)·`persistence-schema`(V26)·`upbit-api`(funds 정의)·`PROJECT_ANALYSIS.md` 스키마 절·README 집계 한계 절 갱신 + wiki 검증 3종 통과.
10. SPA: `@babel/standalone` react preset 으로 `screens.jsx` 파싱 통과(로컬 실측 — 구문 오류로 SPA 가 죽는 회귀는 배제). **렌더는 로컬 미검증**(Postgres 없이 기동 불가): 배포 후 `/app.html` 거래 목록 "금액" 열과 봇 페이지 전략 성과 카드의 "체결 … (미상 N건 제외)" 줄이 렌더되는지 사람이 확인. 실패 시 JSX 파서 오류로 SPA 전체가 죽으므로 배포 직후 1회 필수.

# Key Files

- `bot/src/main/resources/db/migration/V26__trade_records_order_amount.sql` — 신규
- `bot/src/main/kotlin/com/trading/bot/domain/Order.kt` — `filledFunds()`
- `bot/src/main/kotlin/com/trading/bot/domain/TradeRecord.kt` — `orderAmount: Double?`
- `bot/src/main/kotlin/com/trading/bot/persistence/entity/TradeRecordEntity.kt` · `persistence/TradeRecordRepository.kt` — 컬럼·save·집계 SQL
- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — completeBuy/applyFillOutcome·buildSellRecord 배선
- `bot/src/main/kotlin/com/trading/bot/engine/TradeExecutionService.kt` — 수동 경로 배선
- `bot/src/main/kotlin/com/trading/bot/notification/DiscordNotifier.kt` — 금액 라벨
- `bot/src/main/resources/static/tide-app/screens.jsx` — 거래목록 체결금액
- 테스트: `OrderFilledVwapTest`·`PositionManagerExtendedTest`·`TradeExecutionServiceTest`·`TradeRecordRepositoryTest`·`DiscordNotifierTest`
- `bot/src/main/kotlin/com/trading/bot/api/StrategyController.kt` — `amount_unknown_trades` 노출 · `TradeHistoryController.kt` — 엔티티 직렬화(변경 없음, 응답 키 추가)
- `bot/src/test/kotlin/com/trading/bot/persistence/TradeRecordAggregateRoundTripTest.kt` — 신규 DB 통합
- `scripts/run-db-tests.sh` · `.github/workflows/deploy.yml` — DB 테스트 가드 2클래스 확장
- 문서: `wiki/pages/concept/trade-record-volume-semantics.md` · `wiki/pages/concept/persistence-schema.md` · `wiki/pages/entity/upbit-api.md` · `PROJECT_ANALYSIS.md`

# Deferred

- `Order` 파생값(vwap·funds·feeBasis)을 `OrderFill` 값객체로 묶기 — #133→#148→#146 세 번 연속 스칼라 인자가 늘었다(arch 권고). 심각도 낮음, `PositionManager.kt` private 시그니처 2개·호출부 5곳.
- `filledVwap`·`paid_fee` 도 `wait`+부분체결 확정 경로(`applyFillOutcome` 분기 순서)에서 비terminal 값이 기록된다 — 이번 작업은 `orderAmount` 만 terminal 한정. `PositionManager.kt` applyFillOutcome.
- `trade_executions.total_amount` 는 여전히 스냅샷(소비처 없음) — 두 감사 테이블의 충실도 격차.
- `FeeBasis.Estimate` 기준가를 `orderAmount ?: totalAmount` 로 바꾸면 "totalAmount 가 실대금인 경로에서만" 전제를 코드로 강제할 수 있다 — 지금은 동작 동일이라 후속.
- DB 통합테스트 게이트 단일 소스화(`scripts/assert-db-tests-ran.sh` 추출 — `run-db-tests.sh`·`deploy.yml` 이 목록·판정을 각각 들고 있다) 및 Postgres 하네스 공용 base(3번째 DB 테스트 클래스 전에). arch 권고.
- Σfunds + paid_fee = 총 차감 KRW 등식, cancel 부분체결의 `funds` 최종성 — 공식문서 미명시(researcher). 실거래 응답 샘플로 확인 전까지 KDoc 은 "수수료 미포함 체결 대금"까지만 주장.

# Blockers

없음.
