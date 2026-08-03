---
title: 아키텍처 개관 — 단일 Spring Boot 프로세스 안의 Upbit·KIS 봇·API·시세수집
category: concept
created: 2026-07-28
updated: 2026-08-02
claim_state: current
verified: 2026-08-02 — settings.gradle.kts, bot/src/main/kotlin/com/trading/bot/ 디렉토리 및 KIS 패키지 실측
sources:
  - settings.gradle.kts
  - PROJECT_ANALYSIS.md
  - bot/src/main/kotlin/com/trading/bot/
  - bot/src/main/kotlin/com/trading/bot/kis/
---

# 아키텍처 개관

Gradle 멀티모듈이지만 배포 단위는 **JVM 프로세스 하나**다. `settings.gradle.kts` 는 `common`, `bot` 둘만 포함한다.

| 모듈 | 역할 |
|---|---|
| `bot` | Spring Boot 애플리케이션 — Upbit·KIS 거래 봇 + REST API + SPA 정적 서빙 + in-process 시세 수집 (port 8080) |
| `common` | 공용 도메인(`NormalizedTicker`/`NormalizedCandle`), 지표(`Indicators`), 스윙 전략 7종, `ExitGates` |

거래 경로는 **Upbit와 국내주식 KIS** 두 가지다. Upbit 엔진은 `engine/`에, KIS 엔진·시세·주문은 `kis/`에 분리돼 있지만 공용 전략과 일부 도메인을 함께 쓴다. KIS 자동매매의 전체 순서는 [[kis-stock-trading-flow]], 주문 유실 방지와 체결 확정은 [[kis-order-lifecycle]]에 있다. 별도 collector 프로세스나 메시지 브로커는 없다 — 예전에 있었고 의도적으로 제거했다([[rightsizing-history]]).

> [!conflict] `AGENTS.md`의 환경 설명에는 거래소가 Upbit only라고 남아 있지만, 현재 코드에는 `bot/kis/`와 KIS 전용 DB·REST 경로가 존재한다. 이 페이지는 현재 코드를 기준으로 서술한다.

## `bot` 내부 패키지

| 패키지 | 담당 |
|---|---|
| `api/` | REST 컨트롤러 + Upbit/KIS 봇·주문 API + `UpbitErrorHandlerAdvice` |
| `auth/` | JWT 인증 (`AuthController`, `JwtProvider`, `SecurityConfig`) |
| `client/` | `UpbitClient` — 주문·조회 REST |
| `marketdata/` | WS ticker + REST 캔들 수집, `MarketDataStore` ([[marketdata-pipeline]]) |
| `engine/` | `TradingEngine`, `PositionManager`, `TradeExecutionService`, `BacktestEngine` ([[trading-engine-loop]]) |
| `kis/` | `KisClient`, 국내주식 시세·토큰·엔진·주문 WAL·체결 reconcile ([[kis-stock-trading-flow]], [[kis-order-lifecycle]]) |
| `stream/` | `CandleAggregator`, `MarketDataPersistenceService`, `DataRetentionService` |
| `persistence/` | R2DBC Entity/Repository ([[persistence-schema]]) |
| `security/` | `SecretsCrypto`(AES-GCM), `UserSecretsService` — 사용자별 거래소 키 암호화 |
| `notification/` | `DiscordNotifier` |

## 이 구조에서 나오는 성질

- **멀티유저·단일 인스턴스**: 사용자마다 `TradingEngine` 인스턴스가 뜨고(`UserTradingManager`), 프로세스는 하나다. 분산 배포를 전제한 lease·락은 없다.
- **비동기 일관**: WebFlux + Coroutines + R2DBC. 블로킹 JDBC 를 섞으면 이벤트 루프가 막히므로 persistence 는 R2DBC 로 유지된다.
- **시세 수집이 같은 프로세스 안에 있다**: Upbit와 KIS 모두 봇 프로세스 안에서 시세를 수집·캐시한다. 수집이 죽으면 매매도 같이 눈이 멀 수 있어 엔진에 신선도 체크와 REST 폴백이 있다([[trading-engine-loop]], [[kis-stock-trading-flow]]).
