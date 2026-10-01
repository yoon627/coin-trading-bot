---
title: 시세 수집 파이프라인 — WS ticker + REST 캔들, 무수신 워치독
category: concept
created: 2026-07-28
updated: 2026-10-01
claim_state: current
verified: 2026-10-01 — 차트·SSE·watchlist API 제거 뒤 `MarketDataStore` 의 공개 API 는 `updateTicker`·`addCandle`·`getLatestTicker`·`getCandles` 넷(rg — 엔진·수집기만 호출) · 2026-10-01 — 시세 DB 저장·보존 제거: `MarketDataIngestionService` 가 `CandleAggregator` 를 직접 부른다(`onMinuteCandle`·`startFrom(UPBIT, …)`·`prime`) — 실제 store·집계기 특성 테스트 `MarketDataIngestionAggregationTest` 3건(변경 전 저장 서비스 경유로 먼저 Green), 변이 4종(집계 호출·바닥·prime 제거, prime 시각 오전달) 각각 검출 · 2026-10-01 — 전략별 최소 봉수·volume 서술을 운영 전략 combined 기준으로(나머지 전략 삭제) · 2026-09-18 — 운영 DB M1 재구성 일봉 vs Upbit `candles/days`(13마켓, read-only): 구 규칙 09-10~15 78마켓·일 volume 비율 중앙값 0.66(최소 0.47)·레인지 비율 중앙값 1.000/p10 0.967/최소 0.875, 신 규칙 09-17 13마켓 전부 1.000/1.000 · 2026-09-17 — M1 폴링 `count=5` + `CandleAggregator` 분 단위 멱등(base/provisional/lastFolded, `prime(candle, fetchedAt)`)으로 상위봉이 완결 분봉으로 접힌다(`CandleAggregatorTest` 재수신 대체·꼬리 복원·자정 경계·prime 겹침, `MarketDataIngestionServiceTest` 오름차순·count 양 경로 검증). 2026-09-16 — `seedDailyCandles` 가 오늘 D1 을 `CandleAggregator.prime` 으로 등록해 첫 M1 이 seed 를 대체하지 않고 이어받는다(`CandleAggregatorTest` 재현 테스트 Red→Green, `MarketDataIngestionServiceTest` prime 검증). 2026-08-23 seedDailyCandles 200봉·실패 시 무재시도·전략별 minCandles 확인분 유지
sources:
  - bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt
  - bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataStore.kt
  - bot/src/main/kotlin/com/trading/bot/marketdata/UpbitMarketFeed.kt
  - bot/src/main/kotlin/com/trading/bot/stream/CandleAggregator.kt
---

# 시세 수집 파이프라인

이 페이지는 Upbit WS/REST 수집 경로다(KIS 주식 시세 폴링은 2026-09-16 경로 제거로 없어졌다).

구 collector 모듈(Kafka 발행)을 흡수한 **in-process** 수집기다([[rightsizing-history]]). 단일 JVM 이므로 메시지 버스 없이 직접 fan-out 한다.

```
UpbitMarketFeed ──ticker(WS)──┐
                              ├─► MarketDataIngestionService ─┬─► MarketDataStore (메모리)
                └──candle(REST 60s 폴링)──┘                    └─► CandleAggregator (분봉 → 상위 봉) ─► MarketDataStore
```

시세는 DB 에 내리지 않는다(아래 "저장").

## MarketDataStore

메모리 저장소. 봇의 가격 판단(신선한 ticker)과 일봉(D1)은 여기서 나온다([[architecture-overview]]). 읽는 쪽은 엔진뿐이다 — store 를 밖으로 보이던 차트·실시간 가격(SSE)·watchlist API 는 2026-10-01 지웠다.

- `latestTickers` — 마켓별 최신 스냅샷
- `candleBuffers` — `ConcurrentSkipListMap<openTime, Candle>`, 마켓·interval 당 최대 200개. **openTime 키 upsert** 라서 `CandleAggregator` 가 같은 분봉을 반복 갱신해도 중복이 쌓이지 않는다(과거에 중복 누적으로 지표·매수 D1 이 오염된 적이 있다). upsert 의 반대쪽 함정: 집계기가 period 를 **처음** 볼 때 M1 하나로 봉을 새로 만들면 그 upsert 가 부팅 seed 의 완전한 D1 을 재시작 이후 구간만 남은 봉으로 **대체**한다 — 그래서 `seedDailyCandles` 가 **오늘 D1(최신 openTime) 을 집계기에 prime** 해 두고(`CandleAggregator.prime`, 부팅 로그 `Seeded … D1 candles into store for … (primed today=true)`), seed 는 확정 집계(base)가 되고 M1 은 그 위에 얹힌다(시가·고저 보존, close 는 최신 M1, volume 은 합). prime 시각의 분보다 오래된 M1(폴링 꼬리)은 seed 에 이미 포함돼 있어 무시하므로 seed 와의 volume 겹침은 부팅당 최대 1분이고 누적되진 않는다(`combined` 는 D1 volume 을 쓰지 않는다). 어제 이전 봉은 M1 이 다시 오지 않아 seed 그대로다. seed 가 실패한 마켓(무재시도)은 그대로 노출된다.
- **집계기는 분 단위 멱등이다** (2026-09-17) — 폴링은 진행 중 분봉을 돌려주므로 `count=1` 이던 때는 각 분의 완결본을 영영 못 받았다(부팅일이 아니어도 상시). 실측(2026-09-18, 운영 DB M1 재구성 vs Upbit D1, 13마켓 09-10~15): 일봉 **volume 은 Upbit 의 중앙값 66%(최소 47%)**, **고저 레인지는 대부분 날 동일(중앙값 1.000)하고 약 5일 중 1일만 2% 이상 좁았다**(p10 0.967, 최악 −12.5%) — 부분 스냅샷이라도 분마다 시점이 달라 극값은 대체로 잡혔기 때문. 즉 돌파선(전일 레인지) 영향은 작았고 큰 쪽은 D1 volume 을 쓰는 전략이다. 수정 뒤 09-17 은 13마켓 전부 volume·고저가 Upbit 과 일치. 지금은 라운드마다 M1 `count=5`(`M1_FETCH_COUNT`) 를 **오름차순으로** 넣고, 집계기가 period 마다 base(확정 분봉 병합)·provisional(가장 새 분봉의 최신 버전)·lastFolded 를 들고 있다가 더 새 분봉이 오면 provisional 을 base 에 접는다 — 같은 분의 재수신은 대체, 이미 접힌 분은 무시, 건너뛴 분은 다음 라운드 꼬리로 복원(연속 3라운드 ≈4분 공백까지). 그보다 긴 공백은 직전 provisional 이 부분 봉으로 접힌 채 남는다. **바닥보다 오래된 상태 없는 period 는 무시한다** — (market, interval) 별 period 바닥이 있고, 부팅 seed 시점(`startFrom`, 모든 interval — seed 성공·오늘 행 유무와 무관)·prime·새 period 생성 때 올라가며 내려가지 않는다. 부팅 첫 라운드 꼬리의 어제 분봉, Upbit 이 무거래 분을 응답에서 생략해 꼬리에 실리는 오래된 분봉, 축출된 period 의 재유입은 앞부분을 모르므로 부분봉을 만들어 store 의 완전한 seed D1 을 덮지 않는다(2026-09-17 리뷰가 잡은 자정 직후 부팅 결정적 오염 — prime 만으로는 오늘 행이 없는 바로 그 창에서 바닥이 비었다). 부팅 시각의 현재 period(예: 13:02 부팅의 H1 13:00)는 그 이후 분봉만으로 만들어지는 부분봉이다(seed 가 없는 interval 의 기존 한계). count 상한은 period 축출 컷오프(3×interval, M5 = 15분) 미만. 오름차순이 전제다 — 내림차순이면 부분 봉이 확정되고 완결본이 버려져 옛날보다 나빠진다. 2026-09-16 이전엔 이 대체 때문에 재시작 다음날 00:00 직후 전일 레인지가 작아져 돌파선 ≈ 당일시가로 무너졌다([[lesson-seed-vs-stream-overwrite]], #209).

## 수집 코루틴

- **ticker**: WS flow 를 collect 한다. flow 가 에러로든 정상으로든 끝나면 backoff 후 **재구독**한다. 예전 구현은 catch 후 종료라 한 번 끊기면 수집이 영영 멈췄다.
- **candle**: 60초마다 M1 을 5개(`M1_FETCH_COUNT`) 폴링 — 진행 중 분봉과 완결된 직전 분봉들을 함께 받아 집계기가 완결본으로 접는다(위 `candleBuffers`). 캔들 한 번 요청의 상한과 D1 봉 경계(KST 09:00)는 [[upbit-api]] 참조. 부팅 시 `seedDailyCandles` 가 D1 200개를 store 에 한 번 채운다 — 안 하면 D1 버퍼가 하루 1개씩만 쌓여 전략이 요구하는 봉수를 채울 때까지 매 tick REST 폴백을 탄다 — `combined` 21일([[trading-engine-loop]] 의 `MIN_DAILY_CANDLES` 와 [[swing-strategies]] 의 `minCandles`). **seed 가 실패하면 재시도가 없어** 그 상태가 오래 간다.
- **fan-out 격리**: store 와 집계기를 각각 독립 try/catch 로 감싼다. 한쪽 실패가 다른 쪽이나 수집 코루틴을 죽이지 않게. 순서는 분봉을 store 에 넣은 뒤 집계기 — 집계기가 접은 상위 봉도 같은 store 로 간다.

## half-open 워치독

TCP 는 살아 있는데 데이터가 안 오는 상태는 flow 재구독으로 풀리지 않는다. `@Scheduled` 워치독(기본 20초 간격)이 `lastTickerAt` 을 보고 임계 초과면 **ticker job 을 취소·재생성**해 새 연결을 만든다.

- mutex 로 재시작을 직렬화하고, cancel 직전 staleness 를 재확인해 TOCTOU(대기 중 tick 도착)를 막는다.
- 부팅·재시작 시 `lastTickerAt` 을 now 로 리셋해 오발동을 막는다.
- 워치독은 **수집 복구**가 목적이다. 매매 정확성은 엔진의 30초 staleness 게이트가 따로 보호한다.

## 저장

시세(ticker·분봉)는 DB 에 내리지 않는다. 2026-10-01 까지는 `MarketDataPersistenceService` 가 `market_tickers`(종목별 10 tick 마다 1건 샘플링)·`market_candles`(M1 upsert)로 내리고 `DataRetentionService` 가 7일·30일로 정리했는데, 읽는 쪽이 차트·watchlist API 뿐이라 함께 지웠다. 엔진은 처음부터 이 테이블을 읽지 않았다 — store 와 REST 폴백만 쓴다([[trading-engine-loop]]). 두 테이블은 스키마에 남아 있다([[persistence-schema]]).

그 대가로 store D1 을 밖에서 볼 수단과 폴링 완결성을 사후 감사할 수단이 없어졌다 — 위 2026-09-18 실측이 운영 DB 의 M1 을 다시 조립해 Upbit 과 비교한 방식이다. 집계가 끊기면 오늘 D1 이 seed 시점에 멈추고, 다음 UTC 경계 5분 뒤 엔진이 그 D1 을 쓰지 않고 REST 로 폴백하며 WARN(`… has no candle for trading-day open …`)을 남긴다 — 이것이 지금 남은 관찰 지점이다.

이전의 `tickerHistory`·`orderBooks`·`getRecentTickers`·`getOrderBook` 경로와 SSE 용 ticker 스트림(`tickerSink`)·전체 조회(`getAllTickers`·`getTickersByExchange`)는 소비자가 없어 제거됐다. Store 는 최신 ticker 스냅샷과 캔들 버퍼만 보유한다.
