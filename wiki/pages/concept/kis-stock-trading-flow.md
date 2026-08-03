---
title: KIS 주식 자동매매 흐름 — 시장데이터·신호·포지션 사이클
category: concept
created: 2026-08-02
updated: 2026-08-02
claim_state: current
verified: 2026-08-02 — StockBotController.kt, StockUserTradingManager.kt, KisStockTradingEngine.kt, StockPositionManager.kt, KisMarketDataService.kt, application.yml 실측
sources:
  - bot/src/main/kotlin/com/trading/bot/api/StockBotController.kt
  - bot/src/main/kotlin/com/trading/bot/kis/engine/StockUserTradingManager.kt
  - bot/src/main/kotlin/com/trading/bot/kis/engine/KisStockTradingEngine.kt
  - bot/src/main/kotlin/com/trading/bot/kis/engine/StockPositionManager.kt
  - bot/src/main/kotlin/com/trading/bot/kis/marketdata/KisMarketDataService.kt
  - bot/src/main/kotlin/com/trading/bot/kis/marketdata/KisMarketCalendar.kt
  - bot/src/main/kotlin/com/trading/bot/kis/config/KisProperties.kt
  - bot/src/main/resources/application.yml
  - common/src/main/kotlin/com/trading/common/strategy/TradingStrategy.kt
---

# KIS 주식 자동매매 흐름

이 페이지는 국내주식 KIS 자동매매의 **엔진 사이클**을 설명한다. 주문을 실제 KIS API로 보내고 체결 상태를 확정하는 공통 경계는 [[kis-order-lifecycle]]에 별도로 기록한다. Upbit `TradingEngine`의 흐름은 [[trading-engine-loop]]와 구분한다.

## 시작부터 엔진 기동까지

자동매매 진입점은 `POST /api/stock/bot/start`다.

```text
StockBotController
  → 종목코드·전략 정규화
  → StockUserTradingManager.startBot
  → 사용자 KIS 키·전략·종목 검증
  → 미확정 주문 reconcile
  → KisStockTradingEngine 생성
  → durable 포지션 메타데이터 복원
  → 엔진 루프 시작
  → bot_state(exchange=KIS) 저장
```

사용자별 엔진은 `KisClientFactory`가 만든 KIS 클라이언트와 `StockPositionManager`를 소유한다. 서버 재시작 시 `TRADING_AUTO_START=true`일 때만 `bot_state(exchange=KIS)`의 실행 상태를 복원한다. 기본값은 false이므로, 상태가 DB에 남아 있어도 자동 재기동은 기본으로 일어나지 않는다.

## 한 번의 엔진 패스

`KisStockTradingEngine`는 기본 10초 간격으로 돌며, 장외에는 패스를 건너뛴다. 현재 `KisMarketCalendar`는 평일 09:00~15:30(KST)만 판단하고 임시휴장·단축거래는 아직 반영하지 않는다.

1. **잔고 동기화** — `liveEnabled=true`이면 패스 시작에 KIS `getHoldings()`를 한 번 조회한다. 잔고 조회가 실패하면 빈 잔고로 간주하지 않고 해당 패스를 건너뛰어 잘못된 매도·매수 판단을 막는다.
2. **현재가 획득** — `MarketDataStore`의 신선한 KIS ticker를 먼저 사용한다. 없거나 TTL(5초)을 넘으면 엔진 전용 REST 폴백 캐시를 거쳐 `getCurrentPrice()`를 호출한다.
3. **일봉 획득** — store에 충분한 D1 캔들이 없으면 최근 100일을 KIS REST로 가져와 엔진 로컬 캐시에 둔다. 폴백 결과를 전역 store에 다시 쓰지 않아 수집기의 단일 writer 원칙을 유지한다.
4. **보유 중이면 매도 판정** — 손절 → 트레일링 스탑 → 익절 → `chartExitEnabled`일 때 차트 기반 청산 순서로 검사한다. 자세한 게이트 의미는 [[exit-gates]]를 참조한다.
5. **미보유이면 매수 판정** — `boughtToday`가 false인 경우에만 공용 `TradingStrategy.shouldBuyNormalized()`를 호출한다. 캔들이 부족하면 신호는 false다.

`liveEnabled=false`인 동안에도 엔진은 이 루프와 신호 판정을 수행하지만, 포지션은 메모리에서 시뮬레이션하고 주문은 KIS로 보내지 않는다. 이 설정은 [[kis-order-lifecycle]]의 `DRY_RUN` 경로로 이어진다.

## 시세 수집 경로

전역 KIS 자격증명과 watchlist가 설정돼 있으면 `KisMarketDataService`가 장중 현재가를 3초, 일봉을 300초 주기로 `MarketDataStore`에 채운다. 전역 계정이 없거나 폴러가 실패하면 사용자별 엔진이 신선도·백오프가 적용된 REST 폴백을 사용한다. Upbit 수집 경로와 섞어 읽지 않도록 별도 페이지인 [[marketdata-pipeline]]은 Upbit 범위로 한정돼 있다.

## 매수 수량과 포지션 메타데이터

실제 송신 모드에서는 `StockPositionManager`가 다음 순서로 수량을 제한한다.

```text
min(예수금, D+2 정산금)
  × trading.invest-ratio(기본 0.1)
  → max-invest-amount(기본 100,000원) 상한
  → 현재가 × 1.1 슬리피지 버퍼로 예산 수량 계산
  → KIS inquire-psbl-order 매수가능수량과 min
```

매도는 메모리 보유수량이 아니라 KIS가 반환한 `orderableQty`를 사용한다. 주문이 접수됐다고 즉시 보유·평단을 확정하지 않고, 다음 holdings 동기화가 거래소 잔고의 진실이 된다.

재시작을 위해 보유수량·평단 자체는 저장하지 않는다. 대신 트레일링 고점, 당일 매수 근거 날짜, 진입 전략만 `stock_position_state`에 저장·복원한다([[persistence-schema]]).

## 수동 주문과의 관계

수동 `POST /api/kis/order`는 전략 판정만 생략한다. 이후 수량·장시간·계좌·중복 주문 검증, WAL 기록, KIS 송신, 체결 reconcile은 자동매매와 동일한 [[kis-order-lifecycle]]을 통과한다. 따라서 자동매매와 수동주문을 별도 안전 경계로 관리하지 않는다.
