---
title: 시세 수집 파이프라인 — WS ticker + REST 캔들, 무수신 워치독
category: concept
created: 2026-07-28
updated: 2026-08-23
claim_state: current
verified: 2026-08-23 — seedDailyCandles 200봉·실패 시 무재시도 확인, 전략별 minCandles 반영
sources:
  - bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt
  - bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataStore.kt
  - bot/src/main/kotlin/com/trading/bot/marketdata/UpbitMarketFeed.kt
  - bot/src/main/kotlin/com/trading/bot/stream/MarketDataPersistenceService.kt
---

# 시세 수집 파이프라인

이 페이지는 Upbit WS/REST 수집 경로다. KIS 국내주식은 별도 `KisMarketDataService`의 3초 현재가·300초 일봉 폴링과 엔진별 REST 폴백을 사용하며, 그 매매 연결은 [[kis-stock-trading-flow]]에 기록한다.

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
- `candleBuffers` — `ConcurrentSkipListMap<openTime, Candle>`, 마켓·interval 당 최대 200개. **openTime 키 upsert** 라서 `CandleAggregator` 가 같은 분봉을 반복 갱신해도 중복이 쌓이지 않는다(과거에 중복 누적으로 지표·매수 D1 이 오염된 적이 있다).
- `tickerSink` — hot multicast `Flux`. SSE 가 이걸 구독하므로 별도 WS 연결이 필요 없다. `autoCancel=false` 로 두어 마지막 구독자가 끊겨도 sink 가 닫히지 않는다.

## 수집 코루틴

- **ticker**: WS flow 를 collect 한다. flow 가 에러로든 정상으로든 끝나면 backoff 후 **재구독**한다. 예전 구현은 catch 후 종료라 한 번 끊기면 수집이 영영 멈췄다.
- **candle**: 60초마다 M1 을 폴링. 캔들 한 번 요청의 상한과 D1 봉 경계(KST 09:00)는 [[upbit-api]] 참조. 부팅 시 `seedDailyCandles` 가 D1 200개를 store 에 한 번 채운다 — 안 하면 D1 버퍼가 하루 1개씩만 쌓여 전략이 요구하는 봉수를 채울 때까지 매 tick REST 폴백을 탄다 — 기본 21일, `macd_cross` 36일, `knee_*` 41일([[trading-engine-loop]] 의 `MIN_DAILY_CANDLES` 와 [[swing-strategies]] 의 `minCandles`). **seed 가 실패하면 재시도가 없어** 그 상태가 오래 간다.
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
