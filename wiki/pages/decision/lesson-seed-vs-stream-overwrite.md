---
title: lesson — 부팅 seed 와 스트림 집계가 같은 키를 쓰면 스트림의 첫 값이 seed 를 통째로 대체한다 (D1 절단 → 돌파선 붕괴)
category: decision
created: 2026-09-16
updated: 2026-09-17
claim_state: current
verified: 2026-09-16 — 수정은 seed 가 오늘 D1 을 `CandleAggregator.prime` 으로 등록(store 읽기 없음). `CandleAggregator.kt` 원문(`existing == null` 분기가 M1 하나로 새 봉 → `addCandle` upsert)·`MarketDataIngestionService.seedDailyCandles`·`MarketDataStore.addCandle` 로 경로 확정. 라이브 00h 매수 17건 중 7건이 배포(재시작) 다음날 당일시가 바로 위에서 체결(#209 코멘트 표). 재현 테스트 `CandleAggregatorTest` Red→Green
sources:
  - bot/src/main/kotlin/com/trading/bot/stream/CandleAggregator.kt
  - bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt
  - bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataStore.kt
---

# lesson: 부팅 seed 와 스트림 집계가 같은 키를 쓰면 스트림의 첫 값이 seed 를 통째로 대체한다

**언제**: 2026-06(#27 C3 로 지목) → 2026-09-16 확정·수정 (#209)

## 증상

라이브 `combined` 가 배포(재시작) 다음날 09:00 KST 직후(00:02~00:47 UTC)에 **당일시가 바로 위**에서 매수한다. 돌파선(당일시가 + 0.5×전일레인지)을 캐시 일봉으로 다시 계산하면 매수가가 그 아래다 — 어떤 백테 계기도 재현하지 않는 진입이고, 76건 중 7건(그룹 B, [[entry-resolution-vs-live-2026-09]] 의 라이브 전용 24건 일부).

## 원인 (3 Whys)

1. 왜 돌파선이 낮았나 — 전일 D1 의 고가−저가가 실제보다 훨씬 작았다(재시작 시각 이후 구간만).
2. 왜 전일 D1 이 절단됐나 — `CandleAggregator` 가 프로세스 안에서 period 를 처음 보면 M1 하나로 D1 을 **새로** 만들어 `MarketDataStore.addCandle`(openTime upsert, 최신값 유지)로 넣는다. 부팅 `seedDailyCandles` 가 REST 로 넣어 둔 완전한 D1 이 그 순간 대체된다.
3. 왜 방치됐나 — #27 C3 가 6월에 "`AGGREGATE_INTERVALS` 에서 D1 제거 + 재폴링" 으로 지목했지만 "단독 배포 시 역효과" 경고와 함께 큐에 남았고, 증상이 "리셋 직후 재매수" 로 보여 #128(리셋 churn)·경계 stale window(#184)로 해석됐다. 라이브 진입을 계기와 대조([[entry-resolution-vs-live-2026-09]])하고서야 계기 밖 진입 17건이 드러났고, 그중 10건은 #184 가 이미 막은 stale window, 7건이 이 결함이었다.

## 잘못된 방법 / 올바른 방법

- ✗ seed 를 넣은 뒤 스트림이 같은 키를 "새로" 시작하게 두는 것 — upsert 는 중복은 막지만 **대체**는 막지 않는다. "D1 을 집계에서 빼고 REST 로 재폴링"(#27 C3 원안)은 신선도(집계기 입력도 60초 REST M1)·예산(+13/600 req/분)에서는 문제가 아니지만, 재폴링 실패 시 store D1 이 통째로 멈추고 `evaluateChartExit` 엔 current-day 가드가 없어 청산 판정이 어제 봉으로 돌 수 있다(리뷰) — 원인 자리에서 고치는 prime 이 더 작다.
- ✓ **seed 를 넣는 쪽이 스트림 집계기의 진행 중 봉을 prime 한다**(`CandleAggregator.prime(오늘 D1)`) — 첫 M1 은 seed 위에 얹힌다(시가·고저 보존, close 갱신, volume 합 — 2026-09-17 부터는 prime 시각의 분보다 오래된 M1 을 무시해 겹침이 1분으로 고정). seed 가 없는 interval·마켓, 축출된 period 의 fresh 시작 규칙은 동작 불변. 기각: 집계기가 store 의 같은 키를 읽어 병합 — 축출된 period 가 재유입되면 직전 집계분을 이중 계상해 `cleanupOldPeriods` 의 의미를 뒤집는다(리뷰). 재현 테스트가 "seed 시가 보존" 을 못 박는다.
- 일반화: 부팅 시 완전한 값을 채워 넣는 경로와 증분 스트림이 **같은 키 공간**을 쓰면, 증분 쪽의 "처음 봄" 분기가 항상 seed 를 볼 수 있어야 한다. 그렇지 않으면 재시작마다 그날 데이터가 조용히 부분값이 된다 — 그리고 그 부분값은 다음 거래일 신호에 들어간다.

## 재발 감지

재시작 다음날 00:00~01:00 UTC 진입이 당일시가 근처에 몰리면 이 계열이다. 같은 증상의 **상시판**(재시작과 무관하게 전일 레인지가 좁음)은 폴링 `count=1` 이 각 분의 완결본을 못 받던 결손이었고 2026-09-17 에 `count=5` + 집계기 분 단위 멱등으로 닫혔다([[marketdata-pipeline]] candleBuffers). `entry-resolution-vs-live-2026-09` 의 대조 하네스(`LiveEntryResolutionTest`)를 다시 돌리면 "라이브 전용" 건수로 잡힌다.

관련: [[marketdata-pipeline]], [[trading-engine-loop]], [[lesson-single-point-verification]](한 지점 통과를 일반화하지 말 것 — 여기서는 "부팅 직후엔 맞다" 가 그 지점이었다).
