---
title: dead-path-cleanup — 경량화 잔재 제거 (무소비 저장 경로·죽은 캐시·시세 3중 수집)
status: in_progress
started: 2026-07-08
updated: 2026-08-04
---

# Goal

경량화(collector/Kafka 제거) 잔재로 남은 "아무도 읽지 않는" 데이터 경로를 제거해 DB I/O 낭비·탐색 혼란을 없앤다. 동작 보존(외부 visible behavior — watchlist 의 현재가·24h·1h 변화율 계약 불변). 감사 발견 3건: 쓰기전용 경로 3벌(market_tickers insert·MarketDataStore.tickerHistory·orderBooks), PriceCacheService 죽은 코드, 시세 수집 3중화(PriceCollector→price_snapshots).

# Progress

- 2026-07-08: 전방위 감사(멀티에이전트 워크플로 + 메인 세션 grep spot-check) 기반 plan 작성. spot-check: PriceCacheService 프로덕션 호출자 0, getRecentTickers/getOrderBook 소비자 0, MarketTickerRepository.findRecent(3-arg) 소비자 0(ChartController 의 findRecent 는 MarketCandleRepository 4-arg 로 별개 확인).
- 2026-07-10: plan-review(Claude subagent + codex 병행) 반영 — **major 교정 2건**: ① findRecent(LIMIT 기반)로는 1h 변화율 계산 불가 → recorded_at 시간범위 쿼리 신설로 Decision 수정. ② market_tickers 저장이 전역 10-tick 카운터 샘플링(MarketDataPersistenceService.kt:22-27)이라 고활동 종목이 독식 — 저활동 종목의 1h 창 데이터 존재 보장 없음 → per-market 샘플링 전환 추가. 선행 의존 사유 보강(파일 충돌 아닌 기능 신뢰성), DataRetentionService 문구 교정, status: blocked 로 정정(§10 — 선행 머지 대기).
- 2026-08-03: `origin/main`에 marketdata-consolidation 1·2·3단계가 모두 반영된 사실을 확인(`a44782a`, `78ad3fc`, `5338572`, `b829a93` 모두 조상)하고, 깨끗한 브랜치에 `origin/main`을 로컬 병합(`12f1253`)했다. 선행 blocker를 해소해 status를 `in_progress`로 전환하고, 첫 정리 단계의 참조 검색·무논쟁 삭제를 시작한다.
- 2026-08-03: 참조 검색에서 `PriceCacheService`·전용 테스트, `MarketDataStore`의 `tickerHistory`·`orderBooks`·`getRecentTickers`·`getOrderBook`·`updateOrderBook`·`hasData`, `NormalizedOrderBook`의 프로덕션/테스트 소비자가 없음을 확인했다. 세 경로와 전용 테스트/도메인 파일을 삭제하고, 각 삭제 직후 JDK 21로 `compileKotlin`을 통과시켰다. `:bot:test --tests com.trading.bot.marketdata.MarketDataStoreTest` 및 전체 `./gradlew test`가 통과했고, 삭제 심볼 검색도 결과 0건이었다(기본 JDK 25 실행은 `25.0.2` 오류로 실패해 프로젝트 요구 JDK 21로 재실행).
- 2026-08-03: `PROJECT_ANALYSIS.md`와 `wiki/pages/concept/marketdata-pipeline.md`에서 삭제된 cache/orderbook 경로와 Redis 용도를 동기화했다. `wiki` 링크 검사·추가 검증·smoke가 모두 통과했다.
- 2026-08-03: 1단계(무논쟁 삭제)가 PR [#81](https://github.com/yoon627/coin-trading-bot/pull/81)로 squash merge 됐다(main `c7078c0`).
- 2026-08-04: 잔재 정리 — 브랜치 코드가 main 과 동일함을 확인한 뒤 로컬 `dead-path-cleanup`(tip `570b366`)과 원격 `dead-path-cleanup`(`8dcddb9`)·`dead-path-cleanup-pr`(`570b366`)를 삭제했다. **남은 단계는 main 기준으로 새 worktree 에서 재개한다**(아래 `# Next`).

# Next

다음 단계 착수 전: `PriceCollector`·`WatchlistController`·`PriceSnapshotRepository`/entity 호출 그래프와 최신 Flyway 번호를 다시 확인해 `price_snapshots` 제거·watchlist 1h 지표 전환의 테스트/마이그레이션 범위를 구체화한다. 이후 `market_tickers` per-market 샘플링과 시간범위 조회를 함께 구현한다.

# Decisions

- **착수 순서: marketdata-consolidation 머지 후** (이유: 파일 인접 + **기능 신뢰성 의존** — price_snapshots 는 REST 폴링이라 WS 장애와 무관했지만, 전환 후 watchlist 지표는 WS ingestion 생존에 의존 → 그 plan 의 무수신 워치독이 선행돼야 회귀가 아님. plan-review 보강)
- **market_tickers 처분 = reader 만들기**: WatchlistController 1h 변화율을 market_tickers 기반으로 전환하고 **price_snapshots(+PriceCollector+repository+entity) 제거** — "reader 있는 DB 시세 테이블 1개만". 단 plan-review major 교정 2건 반영:
  - `findRecent`(LIMIT 기반, MarketDataRepository.kt:14-15) 재사용 불가 — **`recorded_at BETWEEN` 시간범위 쿼리 신설**(1h 전 최근접 행 조회).
  - **샘플링을 per-market 로 전환**: 현재 전역 10-tick 카운터는 BTC 등 고활동 종목이 독식해 저활동 종목의 1h 데이터가 없을 수 있음 — market 별 카운터 또는 market 별 최소 저장 주기(예: 60s)로 변경해 1h 변화율의 존재·정확도 보장. 저장량 변화는 retention(7일)과 함께 확인.
- 현재가·24h 지표는 MarketDataStore.getAllTickers()(실시간 WS)로 전환 — 최대 5분 지연 스냅샷 제거.
- **RedisConfig·redis 의존성 유지**: RateLimitFilter 조건부 사용 — PriceCacheService 만 걷어냄.
- **DataRetentionService 는 정리 대상 축소가 아니라 재편**: market_tickers 정리 유지 + **market_candles 1m 정리(:35-41)도 유지 필수**(문구 오해로 지우면 1분봉 무한 증가 — plan-review 교정). price_snapshots 정리는 PriceCollector 내부 cron 이라 삭제와 함께 자연 소멸.
- **drop migration**: price_snapshots drop. 버전 번호는 착수 시 최신 확인 — main 은 V13 이나 미머지 stock-bot-kis 가 V14 를 선점할 수 있음: **충돌 시 이쪽이 renumber**(주식 브랜치가 먼저 머지되는 시나리오 기준) 절차 명시.
- 죽은 코드는 주석 아닌 삭제(git 이 기억) — CLAUDE.md §6.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/cache/PriceCacheService.kt` + `bot/src/test/.../cache/PriceCacheServiceTest.kt` — 참조 0 확인 후 삭제
- `bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataStore.kt` — tickerHistory·orderBooks·무소비 getter/update 경로 삭제, latestTickers/candleBuffers/tickerSink 유지
- `common/.../domain/NormalizedOrderBook.kt` — producer/consumer 0 확인 후 삭제
- `wiki/pages/concept/marketdata-pipeline.md` — 현재 MarketDataStore 저장 경로로 문서 동기화
- `bot/src/main/kotlin/com/trading/bot/engine/PriceCollector.kt` + `persistence/PriceSnapshotRepository`+entity — 제거
- `bot/src/main/kotlin/com/trading/bot/api/WatchlistController.kt` — :26-53(1h 변화율 전환 지점)
- `bot/src/main/kotlin/com/trading/bot/stream/MarketDataPersistenceService.kt` — :22-27(per-market 샘플링 전환)
- `bot/src/main/kotlin/com/trading/bot/persistence/MarketDataRepository.kt` — :14-15(시간범위 쿼리 신설)
- `bot/src/main/kotlin/com/trading/bot/stream/DataRetentionService.kt` — :25-42(정리 재편)
- `bot/src/main/resources/db/migration/` — drop migration

# Acceptance

- [ ] 삭제 후 `./gradlew compileKotlin` + `test` 전체 green
- [ ] watchlist API 응답 동등성: 현재가·24h·1h 변화율 필드 계약 불변(실행·관찰 — 로컬 기동 후 응답 비교) + 1h 변화율의 market_tickers 기반 신규 테스트(저활동 종목 케이스 포함)
- [ ] per-market 샘플링: 저활동 종목도 1h 창에 행 존재 테스트 green
- [ ] `rg` 로 삭제 심볼 잔여 참조 0 (PriceCacheService, tickerHistory, getRecentTickers, NormalizedOrderBook, PriceSnapshot)
- [ ] drop migration 적용 후 앱 정상 기동(Flyway) — 로컬 compose 실행·관찰
- [ ] PROJECT_ANALYSIS.md 시세 수집 서술 갱신 (문서 동기화 기준 확인)

# Blockers

- **없음** — marketdata-consolidation 1·2·3단계가 `origin/main`에 반영된 것을 확인했고, 현재 브랜치에 병합했다.
