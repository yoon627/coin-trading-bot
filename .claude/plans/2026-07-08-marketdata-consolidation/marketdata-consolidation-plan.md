---
title: marketdata-consolidation — Upbit WS 수집 단일화 + half-open 고착 워치독 + 파싱 테스트
status: in_progress
started: 2026-07-08
updated: 2026-07-10
---

# Goal

단계별 목표(plan-review 반영으로 명확화):
- 1단계: 실거래 폴백 체인 실효성 회복 — WS 폴백이 거래 티커를 실제로 커버.
- 2단계: **양쪽 수집 경로(UpbitMarketFeed=주 경로, UpbitWebSocketClient=폴백)** 의 half-open 무수신 고착을 워치독으로 복구 + 재연결 race 제거 + 파싱 테스트.
- 3단계(별도 PR 가능): 상시 WS 연결 1개로 통합(UpbitMarketFeed 단일 수집, RealtimePrice 는 응답 DTO 변환만).

# Progress

- 2026-07-08: 전방위 감사(멀티에이전트 워크플로 + 메인 세션 grep spot-check) 기반 plan 작성. spot-check: WS_URL 동일 상수 2벌, init 하드코딩 4종목, ping/keepalive 0건.
- 2026-07-10: plan-review(Claude subagent + codex 병행) 반영 — **critical 교정**: 워치독이 폴백 클라이언트만 덮으면 Goal(영구 REST 폴백 고착 해소)이 성립하지 않음. 주 경로 UpbitMarketFeed 는 callbackFlow 지역 상태(:49-53)라 외부 워치독 접근 불가 + MarketDataIngestionService 는 flow 종료 시 재시작 없이 죽음(:65-71) → 2단계 스코프 확장. 또한 봇 시작 시 WS 구독 자체가 없어 watchlist 교체만으론 불충분 → engine.start 시 subscribe 추가.

# Next

1단계(즉효·저위험)부터 TDD: watchlist/거래 티커 중 하드코딩 4종목 외 티커의 WS 폴백 miss 를 고정하는 테스트 → Red → ① UpbitWebSocketClient init 하드코딩 → watchlist 구독 교체(WatchlistProperties 주입 — 현재 무의존 @Component 라 생성자 변경), ② TradingEngine.start(tickers) 에서 subscribe(tickers) 호출 추가(사용자가 watchlist 밖 티커로 startBot 하는 케이스).

# Decisions

- **단계 분할 유지하되 2단계 스코프 확장**: 1단계 = 폴백 구독 실효화(S), 2단계 = 무수신 워치독(양 경로)+세대 가드+파싱 테스트(M), 3단계 = 풀 통합(M~L, 별도 PR 가능). "1·2단계만으로 실운영 위험 해소" 주장은 **2단계가 주 경로를 덮을 때만 성립**(plan-review critical 교정).
- **주 경로(UpbitMarketFeed) 워치독**: MarketDataIngestionService 레벨에서 **store 최신 수신 timestamp 기반 감시** — N초 무수신 시 수집 flow 재기동(현재 collect 종료 시 재시작 없는 :65-71 도 함께 해소: 재시작 루프 + backoff). callbackFlow 내부 상태를 밖으로 여는 것보다 파급이 작다. (대안이던 'feed 상태 component 승격'은 3단계 통합 때 함께)
- **폴백(UpbitWebSocketClient) 워치독**: 마지막 수신 timestamp 기반 @Scheduled — 임계 초과 시 disposable.dispose() 로 기존 doFinally→scheduleReconnect 경로 재사용.
- **재연결 race**: 연결 세대(generation) 부여, doFinally 는 자기 세대가 현재일 때만 scheduleReconnect. (이유: 핸드셰이크 >1s 장애 상황에서 중복 connect → disposable 덮어쓰기 leak·이중 수신 — UpbitWebSocketClient.kt:75-118,162-186)
- **파싱 테스트는 이 plan 스코프**: 파싱 함수 internal 분리 + 실제 Upbit ticker 프레임 fixture(정상/필드누락/비-ticker/깨진 JSON/timestamp 단위). UpbitMarketFeed.parseTickerMessage 의 `catch { null }` 무로그(:187-189)에 warn 추가. 기존 UpbitWebSocketClientTest 의 실네트워크 유발 테스트(subscribe)는 연결 비활성 구조로 교체.
- 3단계 진행 시 SSE(PriceStreamController)·엔진 폴백을 MarketDataStore 기반으로 전환 — 동작 동등성 확인 필수.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/client/UpbitWebSocketClient.kt` — :39-48(init 하드코딩·무의존 @Component), :75-118(connect/reconnect), :130-160(파싱), :162-186(scheduleReconnect)
- `bot/src/main/kotlin/com/trading/bot/marketdata/UpbitMarketFeed.kt` — :44-101(callbackFlow 지역 상태·재연결), :168-190(parseTickerMessage)
- `bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt` — :65-71(collect 종료 시 미재시작 — 주 경로 워치독 삽입 지점)
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — :146-169(폴백 소비자), start(구독 추가 지점)
- `bot/src/main/kotlin/com/trading/bot/api/PriceStreamController.kt` — :45(SSE 구독 경로)
- `bot/src/main/kotlin/com/trading/bot/config/WatchlistProperties` — 주입 대상
- `bot/src/test/kotlin/com/trading/bot/client/UpbitWebSocketClientTest.kt` — 교체 대상

# Acceptance

- [ ] 폴백 구독: 거래 티커(watchlist 밖 포함) 전부 WS 폴백 latestPrice 조회 가능 테스트 green
- [ ] 주 경로 워치독: store 무수신 임계 초과 → 수집 재기동 테스트 green (+collect 종료 후 자동 재시작)
- [ ] 폴백 워치독: 무수신 임계 초과 → dispose→재연결 경로 호출 테스트 green
- [ ] 세대 가드: in-flight 핸드셰이크 중 중복 connect 차단 테스트 green
- [ ] 파싱 fixture 테스트 5종 green + 파싱 실패 warn 로그 검증
- [ ] (3단계 진행 시) 상시 WS 연결 1개 — 로컬 실행·관찰(연결 로그/netstat), SSE·엔진 폴백 동등성 확인
- [ ] `./gradlew test` 전체 green

# Blockers

(없음) — dead-path-cleanup 이 이 plan 의 워치독(수집 신뢰성)에 **기능 의존**(파일 인접 이상의 이유): 이 plan 머지 후 착수.
