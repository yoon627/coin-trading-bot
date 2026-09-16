---
title: 시세 수집 파이프라인 — WS ticker + REST 캔들, 무수신 워치독
category: concept
created: 2026-07-28
updated: 2026-09-17
claim_state: current
verified: 2026-09-17 — M1 폴링 `count=5` + `CandleAggregator` 분 단위 멱등(base/provisional/lastFolded, `prime(candle, fetchedAt)`)으로 상위봉이 완결 분봉으로 접힌다(`CandleAggregatorTest` 재수신 대체·꼬리 복원·자정 경계·prime 겹침, `MarketDataIngestionServiceTest` 오름차순·count 양 경로 검증). 2026-09-16 — `seedDailyCandles` 가 오늘 D1 을 `CandleAggregator.prime` 으로 등록해 첫 M1 이 seed 를 대체하지 않고 이어받는다(`CandleAggregatorTest` 재현 테스트 Red→Green, `MarketDataIngestionServiceTest` prime 검증). 2026-08-23 seedDailyCandles 200봉·실패 시 무재시도·전략별 minCandles 확인분 유지
sources:
  - bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt
  - bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataStore.kt
  - bot/src/main/kotlin/com/trading/bot/marketdata/UpbitMarketFeed.kt
  - bot/src/main/kotlin/com/trading/bot/stream/MarketDataPersistenceService.kt
  - bot/src/main/kotlin/com/trading/bot/stream/CandleAggregator.kt
---

# 시세 수집 파이프라인

이 페이지는 Upbit WS/REST 수집 경로다(KIS 주식 시세 폴링은 2026-09-16 경로 제거로 없어졌다).

구 collector 모듈(Kafka 발행)을 흡수한 **in-process** 수집기다([[rightsizing-history]]). 단일 JVM 이므로 메시지 버스 없이 직접 fan-out 한다.

```
UpbitMarketFeed ──ticker(WS)──┐
                              ├─► MarketDataIngestionService ─┬─► MarketDataStore (메모리)
                └──candle(REST 60s 폴링)──┘                    └─► MarketDataPersistenceService (DB + 집계)
```

## MarketDataStore

메모리 저장소. 봇의 가격 판단과 SSE 스트림은 여기서 나온다([[architecture-overview]]).

**차트 API 는 store 전용이 아니다** — `ChartController` 는 메모리에 요청 개수만큼 없으면 **DB(`market_candles`)로 완전히 대체**한다(`ChartController.kt:46-53`). 차트 값과 봇이 본 값이 어긋난다면 이 폴백 경로를 먼저 의심한다.

- `latestTickers` — 마켓별 최신 스냅샷
- `candleBuffers` — `ConcurrentSkipListMap<openTime, Candle>`, 마켓·interval 당 최대 200개. **openTime 키 upsert** 라서 `CandleAggregator` 가 같은 분봉을 반복 갱신해도 중복이 쌓이지 않는다(과거에 중복 누적으로 지표·매수 D1 이 오염된 적이 있다). upsert 의 반대쪽 함정: 집계기가 period 를 **처음** 볼 때 M1 하나로 봉을 새로 만들면 그 upsert 가 부팅 seed 의 완전한 D1 을 재시작 이후 구간만 남은 봉으로 **대체**한다 — 그래서 `seedDailyCandles` 가 **오늘 D1(최신 openTime) 을 집계기에 prime** 해 두고(`persistenceService.primeAggregate` → `CandleAggregator.prime`, 부팅 로그 `Primed D1 aggregate for …`), seed 는 확정 집계(base)가 되고 M1 은 그 위에 얹힌다(시가·고저 보존, close 는 최신 M1, volume 은 합). prime 시각의 분보다 오래된 M1(폴링 꼬리)은 seed 에 이미 포함돼 있어 무시하므로 seed 와의 volume 겹침은 부팅당 최대 1분이고 누적되진 않는다(`combined` 경로는 D1 volume 을 쓰지 않고, volume 을 보는 전략(`mean_reversion`·`vwap_band`)에도 하루 중 1분 이내라 무시할 크기). 어제 이전 봉은 M1 이 다시 오지 않아 seed 그대로다. seed 가 실패한 마켓(무재시도)은 그대로 노출된다.
- **집계기는 분 단위 멱등이다** (2026-09-17) — 폴링은 진행 중 분봉을 돌려주므로 `count=1` 이던 때는 각 분의 완결본을 영영 못 받아 상위봉 volume 이 평균 절반, 고저 레인지가 체계적으로 좁았다(부팅일이 아니어도 상시). 지금은 라운드마다 M1 `count=5`(`M1_FETCH_COUNT`) 를 **오름차순으로** 넣고, 집계기가 period 마다 base(확정 분봉 병합)·provisional(가장 새 분봉의 최신 버전)·lastFolded 를 들고 있다가 더 새 분봉이 오면 provisional 을 base 에 접는다 — 같은 분의 재수신은 대체, 이미 접힌 분은 무시, 건너뛴 분은 다음 라운드 꼬리로 복원(연속 3라운드 ≈4분 공백까지). 그보다 긴 공백은 직전 provisional 이 부분 봉으로 접힌 채 남는다. **바닥보다 오래된 상태 없는 period 는 무시한다** — (market, interval) 별 period 바닥이 있고, 부팅 seed 시점(`startFrom`, 모든 interval — seed 성공·오늘 행 유무와 무관)·prime·새 period 생성 때 올라가며 내려가지 않는다. 부팅 첫 라운드 꼬리의 어제 분봉, Upbit 이 무거래 분을 응답에서 생략해 꼬리에 실리는 오래된 분봉, 축출된 period 의 재유입은 앞부분을 모르므로 부분봉을 만들어 store 의 완전한 seed D1 을 덮지 않는다(2026-09-17 리뷰가 잡은 자정 직후 부팅 결정적 오염 — prime 만으로는 오늘 행이 없는 바로 그 창에서 바닥이 비었다). 부팅 시각의 현재 period(예: 13:02 부팅의 H1 13:00)는 그 이후 분봉만으로 만들어지는 부분봉이다(seed 가 없는 interval 의 기존 한계). count 상한은 period 축출 컷오프(3×interval, M5 = 15분) 미만. 오름차순이 전제다 — 내림차순이면 부분 봉이 확정되고 완결본이 버려져 옛날보다 나빠진다. 2026-09-16 이전엔 이 대체 때문에 재시작 다음날 00:00 직후 전일 레인지가 작아져 돌파선 ≈ 당일시가로 무너졌다([[lesson-seed-vs-stream-overwrite]], #209).
- `tickerSink` — hot multicast `Flux`. SSE 가 이걸 구독하므로 별도 WS 연결이 필요 없다. `autoCancel=false` 로 두어 마지막 구독자가 끊겨도 sink 가 닫히지 않는다.

## 수집 코루틴

- **ticker**: WS flow 를 collect 한다. flow 가 에러로든 정상으로든 끝나면 backoff 후 **재구독**한다. 예전 구현은 catch 후 종료라 한 번 끊기면 수집이 영영 멈췄다.
- **candle**: 60초마다 M1 을 5개(`M1_FETCH_COUNT`) 폴링 — 진행 중 분봉과 완결된 직전 분봉들을 함께 받아 집계기가 완결본으로 접는다(위 `candleBuffers`). 캔들 한 번 요청의 상한과 D1 봉 경계(KST 09:00)는 [[upbit-api]] 참조. 부팅 시 `seedDailyCandles` 가 D1 200개를 store 에 한 번 채운다 — 안 하면 D1 버퍼가 하루 1개씩만 쌓여 전략이 요구하는 봉수를 채울 때까지 매 tick REST 폴백을 탄다 — 기본 21일, `macd_cross` 36일, `knee_*` 41일([[trading-engine-loop]] 의 `MIN_DAILY_CANDLES` 와 [[swing-strategies]] 의 `minCandles`). **seed 가 실패하면 재시도가 없어** 그 상태가 오래 간다.
- **fan-out 격리**: store 와 persistence 를 각각 독립 try/catch 로 감싼다. 한 sink 실패가 다른 sink 나 수집 코루틴을 죽이지 않게 — 구 Kafka 2-consumer-group 격리와 등가.

## half-open 워치독

TCP 는 살아 있는데 데이터가 안 오는 상태는 flow 재구독으로 풀리지 않는다. `@Scheduled` 워치독(기본 20초 간격)이 `lastTickerAt` 을 보고 임계 초과면 **ticker job 을 취소·재생성**해 새 연결을 만든다.

- mutex 로 재시작을 직렬화하고, cancel 직전 staleness 를 재확인해 TOCTOU(대기 중 tick 도착)를 막는다.
- 부팅·재시작 시 `lastTickerAt` 을 now 로 리셋해 오발동을 막는다.
- 워치독은 **수집 복구**가 목적이다. 매매 정확성은 엔진의 30초 staleness 게이트가 따로 보호한다.

## 저장

`MarketDataPersistenceService` 가 `market_tickers`/`market_candles` 로 내린다([[persistence-schema]]). ticker 저장은 **종목별 카운터 기반 샘플링**(10 tick 마다 1건)이다. 예전에는 전역 카운터라 고활동 종목이 저장 슬롯을 독식했다 — watchlist 를 이 테이블로 옮기면서(2026-08-05) 종목별로 분리했다.

> [!caution]
> **샘플링 때문에 이 테이블은 "그 종목이 존재하는가"의 근거가 될 수 없다.** 거래가 드문 종목은 1시간에 10 tick 이 안 차 행이 아예 없을 수 있다. 종목 목록·현재가는 메모리 스냅샷과 `market_tickers` 마지막 기록 중 **더 나중 관측**을 쓰고, 둘 다 없을 때만 제외한다(재시작 직후에는 WS 가 `isOnlyRealtime` 이라 메모리가 비어 있다). **신선도로 목록을 거르지 않는다** — 걸러내면 조용한 종목이 UI 에서 사라지고, 값이 언제 것인지는 `updated_at` 이 드러낸다. **시간창 지표는 `market_tickers` 가 아니라 `market_candles` 에서 얻는다** — 캔들은 60초 REST 폴링이라 거래량과 무관하게 채워지는 반면 ticker 샘플은 조용한 종목에서 아예 비기 때문이다. watchlist 의 1시간 변화율이 이 구조다(1분봉 창의 첫 봉 종가 기준).

> [!important]
> **`market` 컬럼은 정규화 형식(`BTC/KRW`)이지 Upbit 형식(`KRW-BTC`)이 아니다.** `UpbitMarketFeed` 가 `MarketPair.normalize` 를 거쳐 저장하기 때문이다. 반면 watchlist·엔진 설정은 Upbit 형식을 쓴다. 이 테이블을 조회하는 코드는 반드시 변환해야 하며, 빠뜨리면 **에러 없이 항상 빈 결과**가 나온다(무증상). watchlist 전환 때 실제로 이 함정에 걸렸고 테스트가 잡았다.

이전의 `tickerHistory`·`orderBooks`·`getRecentTickers`·`getOrderBook` 경로는 소비자가 없어 제거됐다. Store 는 최신 ticker 스냅샷·캔들 버퍼·ticker 스트림만 보유한다.
