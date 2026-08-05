---
title: dead-path-cleanup-rest — watchlist 를 market_tickers 로 전환하고 price_snapshots 수집 제거
status: in_progress
started: 2026-08-05
updated: 2026-08-05
---

# Goal

시세 3중 수집의 마지막 잔재를 제거한다. `PriceCollector` 가 5분마다 REST 로 같은 종목을 다시 긁어 `price_snapshots` 에 쌓고 있는데, 동일 종목이 이미 WS 로 `market_tickers` 에 저장되고 있다. watchlist API 를 `market_tickers` 기반으로 전환하고 `PriceCollector` 경로를 삭제한다. **외부 동작 불변**(watchlist 의 현재가·24h·1h 변화율).

이 작업은 `.claude/plans/2026-07-08-dead-path-cleanup/`(1단계: 무논쟁 삭제, PR #81 머지)의 잔여 단계다.

# Progress

- 2026-08-05: Explore 완료. 아래 Decisions 의 사실을 코드로 확인.
- 2026-08-05: **함정 발견 — 테스트가 잡았다.** `market_tickers.market` 은 정규화 형식(`BTC/KRW`)인데(`UpbitMarketFeed.kt:182` 가 `MarketPair.normalize` 를 거쳐 저장) watchlist 설정은 Upbit 형식(`KRW-BTC`)이다. 조회 시 변환하지 않으면 **에러 없이 항상 빈 결과**가 된다. parity 테스트를 먼저 써둔 덕에 구현 직후 잡혔다.
- 2026-08-05: 구현 완료 — `findByTimeRange` 신설, `WatchlistController` 전환(정규화 변환 포함), per-market 샘플링, `PriceCollector`·`PriceSnapshotRepository`·`PriceSnapshotEntity` 삭제. wiki `marketdata-pipeline` 에 정규화 함정·샘플링 변경 적립.
- 2026-08-05: codex code-review(high) **P0 0** / P1 1 / P2 3 / P3 1 → 전량 처분.
- 2026-08-06: **pre-push 3차 P2 2건** — 폴백 추가가 "관측 1건인데 변화율 계산" 경로를 새로 만들었다(같은 행이 latest 이자 oldest). 시각 비교로 구분하고 `findRecent` tie-breaker 추가. 602 tests green.
- 2026-08-06: **pre-push 2차에서 또 P1** — 메모리만 보면 재시작 직후 종목이 빠지고(WS `isOnlyRealtime`), 가격 무변동 시 `change_1h` 가 `null` 이 되는 회귀도 있었다. DB 폴백 추가 + 조건 정정. 601 tests green.
- 2026-08-05: **pre-push codex 가 P1 을 추가 검출 — 설계를 바꿨다.** 샘플링 때문에 조용한 종목이 1h 창에 행이 없어 watchlist 에서 사라진다(구 REST 는 5분마다 기록해 항상 노출). 현재값·목록은 `MarketDataStore` 메모리에서, DB 는 1h 기준값 1건만 읽도록 전환 — 같은 수정으로 P2(1h 전체 적재)도 해소. 599 tests green.

# Next

PR 생성·머지. 그 뒤 2단계(`V19__drop_price_snapshots.sql`)를 별도 작업으로.

# Decisions

## 전환이 안전한 근거 (코드로 확인)

- **종목 집합이 동일하다** — WS 구독 종목은 `MarketDataIngestionService.kt:84` 에서 `watchlistProperties.tickerList()` 로 정해지고, `PriceCollector` 도 같은 소스를 쓴다. 따라서 전환해도 watchlist 에서 종목이 사라지지 않는다. (이 전제가 깨지면 이 작업 전체가 무효다 — 테스트로 고정한다.)
- **필드가 모두 대응된다** — `MarketTickerEntity` 의 `price`·`highPrice24h`·`lowPrice24h`·`changeRate24h`·`quoteVolume24h`·`recordedAt` 이 `PriceSnapshotEntity` 의 `price`·`highPrice`·`lowPrice`·`signedChangeRate`·`accTradePrice24h`·`capturedAt` 에 1:1 대응.
  - ⚠️ 시간 타입이 다르다: `capturedAt: LocalDateTime`(KST) vs `recordedAt: Instant`. 응답의 `updated_at` 문자열 표현이 바뀌지 않도록 KST 로 변환해 포맷한다.
- **정리(retention)도 이미 있다** — `DataRetentionService`(`stream/`)가 `market_tickers` 를 7일 보존으로 정리한다. `PriceCollector.cleanupOldSnapshots` 와 같은 역할이라 삭제해도 공백이 없다.

## 2단계 배포 — 이번엔 DROP 하지 않는다 (사용자 결정)

배포 롤백(`.last-good-sha`)이 실제 운영 절차이므로, 테이블을 먼저 지우면 롤백한 구 이미지가 없는 테이블을 조회해 watchlist 가 비고 에러가 쌓인다.

- **이번 PR(1단계)**: 코드만 전환하고 `price_snapshots` 테이블은 **남긴다**. 쓰기는 멈추고 읽기도 없어진다. 7일 보존 정리가 이미 돌고 있으므로 방치해도 무한 증가하지 않는다.
- **다음 PR(2단계)**: 안정 확인 후 `V19__drop_price_snapshots.sql`. 그때는 롤백 대상 이미지가 이미 테이블을 안 쓰는 버전이라 안전하다.
- 마이그레이션 번호는 그때 확정한다(현재 main 최신 V18).

## per-market 샘플링 (사용자 결정)

`MarketDataPersistenceService.persistTicker` 의 `tickerSaveCount` 는 **전역** `AtomicLong` 이라, 고활동 종목이 카운터를 독식하면 저활동 종목은 1h 창에 데이터가 없어 변화율이 `null` 이 된다. 종목별 카운터로 분리하되 **10-tick 간격은 유지**한다(계약 변경 최소화).

- `ConcurrentHashMap<String, AtomicLong>` 으로 market 별 카운터. 키는 `exchange:market`(다중 거래소 대비).
- 종목 수만큼 쓰기가 늘 수 있으나 tick 자체가 드문 종목이 대상이라 실제 증가는 작다.

## 범위

- `PriceCollector`·`PriceSnapshotRepository`·`PriceSnapshotEntity` 삭제, `WatchlistController` 전환, per-market 샘플링.
- **테이블 DROP 은 범위 밖**(2단계).
- `V7__create_price_snapshots.sql`·`V9__add_missing_indexes.sql` 은 **건드리지 않는다** — 적용된 마이그레이션 수정은 Flyway checksum 위반이다.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/api/WatchlistController.kt` — 전환 대상(현재 `PriceSnapshotRepository` 사용)
- `bot/src/main/kotlin/com/trading/bot/persistence/MarketDataRepository.kt:12-19` — `MarketTickerRepository`, 시간범위 조회 신설 위치
- `bot/src/main/kotlin/com/trading/bot/stream/MarketDataPersistenceService.kt:19-27` — 전역 샘플링 카운터
- `bot/src/main/kotlin/com/trading/bot/engine/PriceCollector.kt` — 삭제 대상
- `bot/src/main/kotlin/com/trading/bot/persistence/PriceSnapshotRepository.kt`, `entity/PriceSnapshotEntity.kt` — 삭제 대상
- `bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt:84` — WS 구독 종목 = watchlist 라는 근거
- `bot/src/main/kotlin/com/trading/bot/stream/DataRetentionService.kt` — market_tickers 7일 보존(공백 없음의 근거)

# Blockers

(없음)

# Acceptance

- [x] **응답 계약 불변**: watchlist 응답의 키 9종(`ticker`·`currency`·`price`·`high_price`·`low_price`·`change_24h`·`change_1h`·`volume_24h`·`updated_at`)과 정렬(24h 거래대금 내림차순)이 그대로 — 테스트로 확인
- [x] **1h 변화율**: 1h 창에 2건 이상이면 `(latest-oldest)/oldest*100`, 1건 이하면 `null` — 기존 동작과 동일함을 테스트로 확인
- [x] **종목 집합 일치**: WS 구독과 watchlist 가 같은 소스(`watchlistProperties.tickerList()`)임을 테스트로 고정 — 이 전제가 깨지면 전환이 무효가 되므로 회귀를 막는다
- [x] **per-market 샘플링**: 한 종목이 다른 종목의 저장을 막지 않음을 테스트로 확인(고활동 종목 100 tick 중에도 저활동 종목이 10 tick 째 저장)
- [x] **죽은 경로 제거**: `PriceCollector`·`PriceSnapshotRepository`·`PriceSnapshotEntity` 삭제 후 `price_snapshots` 참조가 마이그레이션 파일 외에 0건 — grep 으로 확인
- [x] **빌드·테스트**: JDK 21 `./gradlew build` 통과
- [x] **문서 동기화**: `PROJECT_ANALYSIS.md`·`wiki/pages/concept/marketdata-pipeline.md` 에서 price_snapshots 수집 서술 갱신 + wiki 검증 3종

# Review Disposition

codex code-review (2026-08-05, effort=high) — P0 0 / P1 1 / P2 3 / P3 1, 미해결 0.

| # | finding | 처분 |
|---|---|---|
| P1 | `market_tickers` 는 nullable 컬럼인데 `price_snapshots` 는 non-null 이었다 — 응답에 `null` 이 새로 등장해 프론트 계약이 조용히 바뀐다. `change_24h` 만 `?: 0.0` 이라 일관성도 없었다 | **fix** — `high_price`·`low_price`·`volume_24h` 도 `?: 0.0`. null 입력 테스트 추가. `change_1h` 는 원래도 null 을 낼 수 있었으므로 유지 |
| P2-a | 동일 `recorded_at` 다건이면 `first()`/`last()` 가 비결정적이라 `change_1h` 가 실행마다 달라진다 | **fix** — `ORDER BY recorded_at DESC, id DESC` tie-breaker |
| P2-b | parity 테스트가 동어반복(같은 식을 양쪽에 써서 구현이 틀려도 통과). 컨트롤러 테스트 fixture 의 `market` 값도 실제 저장 형식이 아니었다 | **fix** — 동어반복 테스트를 정규화 멱등성 검증으로 교체하고, `verify` 로 **실제 호출 인자**(`BTC/KRW`)를 고정 + `KRW-BTC` 로 불리지 않음까지 단언. fixture 를 저장 형식으로 정정. `updated_at` KST·nullable 테스트 추가 |
| P2-c | `tickerSaveCounts` 가 키를 지우지 않아 동적 market 유입 시 무한 증가 | **wontfix(근거 주석)** — 구독 목록은 `MarketDataIngestionService.start` 가 부팅 시 한 번 정해 고정된다(codex 도 현재 call graph 에서 누수 없음 확인). 런타임 유입 경로가 생기면 정리가 필요하다는 조건을 코드 주석에 남김 |
| P3 | `PriceCollector` 삭제로 `price_snapshots` 의 7일 정리 스케줄도 사라졌다 | **defer(2단계)** — 새 writer 가 없어 증가하지 않고, 기존 행은 2단계 DROP 에서 테이블째 사라진다. `## pre-push codex review (2026-08-05, high) — P1 1 / P2 1, 미해결 0

| # | finding | 처분 |
|---|---|---|
| P1 | **거래가 드문 종목이 watchlist 에서 사라진다** — 종목별 10 tick 샘플링이라 조용한 종목은 1h 창에 행이 없다. 구 REST 수집은 5분마다 무조건 기록해 항상 노출됐으므로 명백한 회귀 | **fix(설계 변경)** — 현재값·종목 목록은 `MarketDataStore` 메모리 스냅샷에서 읽고, DB 는 1h 기준값에만 쓴다. 내 acceptance "데이터 없는 종목은 빠진다" 자체가 잘못된 기준이었다 |
| P2 | 1h 창 전체를 `collectList()` 로 적재하는데 계산엔 2건만 필요 — 활발한 종목에서 수천 행 | **fix** — `findByTimeRange` → `findOldestInRange`(`LIMIT 1`). 최신값은 메모리에서 오므로 DB 는 1행만 읽는다. P1 수정과 함께 해결 |

## pre-push codex review 2차 (2026-08-06, high) — P1 1 / P2 1, 미해결 0

| # | finding | 처분 |
|---|---|---|
| P1 | 메모리만 보면 **재시작 직후** 종목이 사라진다 — WS 는 `isOnlyRealtime` 이라 초기 스냅샷이 없어, 첫 tick 전까지 `MarketDataStore` 가 비어 있다 | **fix** — 메모리 → `findRecent(…, 1)` DB 폴백 → 둘 다 없을 때만 제외. `ChartController` 와 같은 메모리+DB 폴백 패턴이다 |
| P2 | 1h 전과 가격이 같으면 `takeIf { it.price != latest.price }` 가 기준점을 버려 `change_1h` 가 `null` — 기존은 `0.0` 이었다 | **fix** — 그 조건은 내가 잘못 넣었다. `price > 0` 만 남겨 무변동 시 `0.0` 을 낸다. 테스트로 고정 |

## pre-push codex review 3차 (2026-08-06, high) — P2 2건, 미해결 0

| # | finding | 처분 |
|---|---|---|
| P2-a | 관측이 1건뿐일 때도 변화율을 계산한다 — 특히 DB 폴백 시 같은 행이 latest 이자 oldest 가 되어 `0.0` 이 나오는데, 기존은 `snapshots.size > 1` 조건으로 `null` 이었다. **내 폴백 추가가 만든 새 경로** | **fix** — `oldest.recordedAt.isBefore(latest.at)` 로 서로 다른 관측일 때만 계산. 가격만 같은 경우(정상적인 `0.0`)와 구분된다 |
| P2-b | `findRecent` 가 `recorded_at DESC` 만 정렬해 동일 시각 행에서 "최신" 이 비결정적 | **fix** — `id DESC` tie-breaker. 이 메서드의 소비자는 이번에 추가한 폴백뿐이라(grep 확인) 영향 범위가 닫혀 있다 |

# Deferred` 에 명시 |

## pre-push codex review (2026-08-05, high) — P1 1 / P2 1, 미해결 0

| # | finding | 처분 |
|---|---|---|
| P1 | **거래가 드문 종목이 watchlist 에서 사라진다** — 종목별 10 tick 샘플링이라 조용한 종목은 1h 창에 행이 없다. 구 REST 수집은 5분마다 무조건 기록해 항상 노출됐으므로 명백한 회귀 | **fix(설계 변경)** — 현재값·종목 목록은 `MarketDataStore` 메모리 스냅샷에서 읽고, DB 는 1h 기준값에만 쓴다. 내 acceptance "데이터 없는 종목은 빠진다" 자체가 잘못된 기준이었다 |
| P2 | 1h 창 전체를 `collectList()` 로 적재하는데 계산엔 2건만 필요 — 활발한 종목에서 수천 행 | **fix** — `findByTimeRange` → `findOldestInRange`(`LIMIT 1`). 최신값은 메모리에서 오므로 DB 는 1행만 읽는다. P1 수정과 함께 해결 |

## pre-push codex review 2차 (2026-08-06, high) — P1 1 / P2 1, 미해결 0

| # | finding | 처분 |
|---|---|---|
| P1 | 메모리만 보면 **재시작 직후** 종목이 사라진다 — WS 는 `isOnlyRealtime` 이라 초기 스냅샷이 없어, 첫 tick 전까지 `MarketDataStore` 가 비어 있다 | **fix** — 메모리 → `findRecent(…, 1)` DB 폴백 → 둘 다 없을 때만 제외. `ChartController` 와 같은 메모리+DB 폴백 패턴이다 |
| P2 | 1h 전과 가격이 같으면 `takeIf { it.price != latest.price }` 가 기준점을 버려 `change_1h` 가 `null` — 기존은 `0.0` 이었다 | **fix** — 그 조건은 내가 잘못 넣었다. `price > 0` 만 남겨 무변동 시 `0.0` 을 낸다. 테스트로 고정 |

## pre-push codex review 3차 (2026-08-06, high) — P2 2건, 미해결 0

| # | finding | 처분 |
|---|---|---|
| P2-a | 관측이 1건뿐일 때도 변화율을 계산한다 — 특히 DB 폴백 시 같은 행이 latest 이자 oldest 가 되어 `0.0` 이 나오는데, 기존은 `snapshots.size > 1` 조건으로 `null` 이었다. **내 폴백 추가가 만든 새 경로** | **fix** — `oldest.recordedAt.isBefore(latest.at)` 로 서로 다른 관측일 때만 계산. 가격만 같은 경우(정상적인 `0.0`)와 구분된다 |
| P2-b | `findRecent` 가 `recorded_at DESC` 만 정렬해 동일 시각 행에서 "최신" 이 비결정적 | **fix** — `id DESC` tie-breaker. 이 메서드의 소비자는 이번에 추가한 폴백뿐이라(grep 확인) 영향 범위가 닫혀 있다 |

# Deferred

- **`price_snapshots` 테이블 DROP**(2단계, 사용자 결정): 이번 PR 배포가 안정된 뒤 `V19__drop_price_snapshots.sql` 로 별도 진행. 그때까지 테이블은 남지만 **쓰기가 없어** 증가하지 않는다. 단 `PriceCollector` 삭제로 7일 정리 스케줄도 함께 사라졌으므로(codex P3) 기존 행은 DROP 때까지 남는다 — 최대 7일치 소량이라 방치 가능.
