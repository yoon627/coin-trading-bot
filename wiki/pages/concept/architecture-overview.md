---
title: 아키텍처 개관 — 단일 Spring Boot 프로세스 안의 봇·API·시세수집
category: concept
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — settings.gradle.kts, bot/src/main/kotlin/com/trading/bot/ 디렉토리 실측, PROJECT_ANALYSIS.md 대조
sources:
  - settings.gradle.kts
  - PROJECT_ANALYSIS.md
  - bot/src/main/kotlin/com/trading/bot/
---

# 아키텍처 개관

Gradle 멀티모듈이지만 배포 단위는 **JVM 프로세스 하나**다. `settings.gradle.kts` 는 `common`, `bot` 둘만 포함한다.

| 모듈 | 역할 |
|---|---|
| `bot` | Spring Boot 애플리케이션 — 실거래 봇 + REST API + SPA 정적 서빙 + in-process 시세 수집 (port 8080) |
| `common` | 공용 도메인(`NormalizedTicker`/`NormalizedCandle`), 지표(`Indicators`), 스윙 전략 7종, `ExitGates` |

거래소는 **Upbit 전용**이다. 별도 collector 프로세스나 메시지 브로커는 없다 — 예전에 있었고 의도적으로 제거했다([[rightsizing-history]]).

## `bot` 내부 패키지

| 패키지 | 담당 |
|---|---|
| `api/` | REST 컨트롤러 + `UpbitErrorHandlerAdvice` |
| `auth/` | JWT 인증 (`AuthController`, `JwtProvider`, `SecurityConfig`) |
| `client/` | `UpbitClient` — 주문·조회 REST |
| `marketdata/` | WS ticker + REST 캔들 수집, `MarketDataStore` ([[marketdata-pipeline]]) |
| `engine/` | `TradingEngine`, `PositionManager`, `TradeExecutionService`, `BacktestEngine` ([[trading-engine-loop]]) |
| `stream/` | `CandleAggregator`, `MarketDataPersistenceService`, `DataRetentionService` |
| `persistence/` | R2DBC Entity/Repository ([[persistence-schema]]) |
| `security/` | `SecretsCrypto`(AES-GCM), `UserSecretsService` — 사용자별 거래소 키 암호화 |
| `notification/` | `DiscordNotifier` |

## 이 구조에서 나오는 성질

- **멀티유저·단일 인스턴스**: 사용자마다 `TradingEngine` 인스턴스가 뜨고(`UserTradingManager`), 프로세스는 하나다. 분산 배포를 전제한 lease·락은 없다.
- **비동기 일관**: WebFlux + Coroutines + R2DBC. 블로킹 JDBC 를 섞으면 이벤트 루프가 막히므로 persistence 는 R2DBC 로 유지된다.
- **시세 수집이 같은 프로세스 안에 있다**: 봇이 읽는 가격과 API 가 노출하는 가격이 같은 `MarketDataStore` 에서 나온다. 수집이 죽으면 매매도 같이 눈이 먼다 — 그래서 신선도 체크가 엔진 안에 있다([[trading-engine-loop]]).
