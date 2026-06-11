---
title: audit-quick-wins — 이슈 #27 무위험 Quick wins 7건 + #21 getAccounts 헬퍼화
status: done
started: 2026-06-11
updated: 2026-06-11
---

# Goal

이슈 #27(2026-06-09 수익성 감사) Quick wins 7건 + 이슈 #21 을 **신호·청산 행동 불변**으로 적용.
라이브 봇(KRW-BTC/`combined`) 가동 중 — 청산 게이트(checkTakeProfit/StopLoss/TrailingStop) 로직은 건드리지 않는다.

# Progress

- 2026-06-11: Explore 완료(TradingEngine·MarketDataStore·PositionManager·StrategyController·BollingerBounce·TradingProperties·application.yml·README·UserTradingManager·TradeExecutionService). worktree `audit-quick-wins` 생성, draft plan 작성.
- 2026-06-11: plan-review(CONDITIONAL GO) 반영 — deploy.sh/.env 스코프 확장·FeeRate 명명·테스트 스텁 교체. TDD Red 7건 확인(의도한 이유) → 구현 → Green(engine·strategy·api 전부 통과). 커밋 c4fcd96 (15 files +201/−40). deploy.sh render 에 숫자 키 4종(TP/SL/TRAIL/FEE) 추가(기존 미전달 불일치 해소, 디폴트 동일). 로컬 deploy/aws/.env TRADING_STRATEGY=combined 반영.
- 2026-06-11: code-review(REQUEST CHANGES) fix loop 1회차 — 커밋 16de5e8 (# Review Disposition 참고). code-simplifier 수정 0건. 최종 검증(격리 runner) `gradlew build` 통과 — 39 스위트 326 테스트 실패 0. push+PR 진행.
- 2026-06-11 (/e 마무리): PR #28 OPEN(미머지) 확인. worktree `audit-quick-wins` clean·unpushed 0 — 임시 커밋 불필요. 커밋 2개(c4fcd96, 16de5e8) 모두 원격 보존. 메모리 `audit-quick-wins-pr28` 기록.
- 2026-06-11 (/c 이어받기): main 의 #23(BollingerBounce shouldSell override·테스트 4건)과 충돌로 머지 불가 → worktree 에서 origin/main 머지, BollingerBounceTest 충돌 해소(**양쪽 보존**: falling-knife 가드 테스트 + shouldSell 테스트 4건, BollingerBounce.kt 는 auto-merge 의미 검수), `gradlew build` 통과(실패 0) 후 머지커밋 c20083e push. **PR #28 squash 머지 완료 — `514d430`, 2026-06-11 10:53:37 UTC (19:53:37 KST)**. #21 자동 close, #27 OPEN 유지. 이슈 #27 코멘트는 권한 정책으로 재차단 → 사용자 실행 명령 전달. plan done 전환·git 등록·worktree 정리 진행.

# Next

(완료 — 남은 것은 운영 액션 2건)
1. **이슈 #27 코멘트** (권한 차단으로 사용자 직접): net pnl 전환 — 머지 514d430 @ 2026-06-11 10:53:37 UTC, 실제 전환점은 배포 시각(배포 후 추가 코멘트 권장)
2. **prod 배포**: 무포지션 시점 권장(syncPosition 이 트레일링 peakPrice 미복원 — 재시작 시 고점 리셋)

# Decisions

1. **staleness 가드** (`TradingEngine.kt:124-136`): store 분기를 `getLatestPrice` → `getLatestTicker`(timestamp 보유)로 교체, 30s 신선도 체크. stale 이면 기존 WS 폴백 → REST 폴백 체인 그대로. 상수 `WS_PRICE_STALE_THRESHOLD_MS` → `PRICE_STALE_THRESHOLD_MS` rename(이제 store에도 적용, 내부 상수라 안전). `getRealtimePrice` 를 internal 로 바꿔 직접 테스트(기존 internal 테스트 패턴: decideSell/evaluateChartExit/loadStoreDailyCandles).
2. **trailing-stop-pct yml 노출**: `trailing-stop-pct: ${TRADING_TRAILING_STOP_PCT:2.0}` — 코드 디폴트 2.0 과 동일, 동작 무변.
3. **백테 폴백 → tradingProperties** (`StrategyController.kt:83-86`): `?: 5.0`→`tradingProperties.takeProfitPct`, `?: 3.0`→`maxLossPct`, `?: 2.0`(trailing)→`trailingStopPct`. 백테 API 디폴트가 TP5/SL3→TP2/SL5 로 바뀌는 것이 목적(라이브 정합). `BacktestConfig` 자체 디폴트는 불변(직접 호출 테스트 영향 회피, controller 가 항상 명시 전달).
4. **pnl net 통일**: `TradingProperties.roundTripFeeRate: Double = 0.001`(왕복 비율, Upbit 0.05%×2) + yml `round-trip-fee-rate` 노출. plan-review 반영: 명명을 `…FeePct`→`…FeeRate` 로 (기존 `takeProfitPct=2.0` 등 %단위 suffix 와 단위 혼란 회피). 기록용 pnl 에서 `roundTripFeeRate * 100`(%p) 차감 — `PositionManager.sell:220` + `TradeExecutionService` MANUAL 매도 2곳(95·129, TradingProperties 주입 추가). **청산 게이트는 gross 유지(행동 불변)**. 백테스트는 이미 net(`feeRate 0.0005×2`)이라 일관.
   - MANUAL pnl 은 nullable — `pnl?.let { it - … }` 로 null 보존(avgBuyPrice≤0 케이스).
   - `PositionManager.sell:222` 로그도 net 으로 정합(DB 기록과 0.1 어긋남 방지).
   - **기존 DB gross 레코드와 신규 net 레코드가 같은 컬럼에 혼재** — 별도 컬럼은 migration 필요(quick-win 범위 초과)라 채택 안 함. 대신 머지/배포 시각을 # Progress 와 이슈에 기록해 `created_at` 기준 구분 가능하게 한다. Discord 색상(`pnlPercent>=0` 파랑)·win_rate 의 gross (0,0.1] 경계 케이스가 net 음수로 바뀌는 것은 보고 정확도 개선으로 **수용**(TAKE_PROFIT 은 +2% 게이트라 net +1.9 — 영향은 TRAILING_STOP·CHART_EXIT·DAILY_RESET·MANUAL 미세 양수에 국한).
5. **BollingerBounce falling-knife**: `bouncedFromLower` 에 `currentPrice > prevPrice` AND 추가. 비활성 전략(라이브 combined) — 무위험.
6. **디폴트 전략 combined**: `TradingProperties.strategy`·`application.yml`·`BotStateEntity` 디폴트 모두 `volatility_breakout`→`combined`. plan-review 반영(스코프 확장): `deploy.sh` 의 `TRADING_STRATEGY:-volatility_breakout` 폴백이 yml/코드 디폴트를 상시 가림 → **deploy.sh 폴백 + `.env.example` + 로컬 `deploy/aws/.env`(gitignored, 별도 수정) 도 combined 로 변경**. 라이브 무영향 확인: prod 활성 전략은 botState DB 영속(`restoreOnStartup` 이 `state.strategy` 사용, `saveState` 는 strategy 항상 명시, stop→start(strategy=null) 경로도 prod 는 env 고정이라 무변 — 그리고 prod 는 이미 combined 가동 중이라 env 를 combined 로 맞추는 것은 현상 고정). `V3__create_bot_state.sql` DDL default 는 코드가 항상 strategy 명시 INSERT 라 실효 없음 + 적용된 migration 수정 금지 — **wontfix**.
7. **README 정정**: 리스크 관리 표를 라이브 디폴트 기준으로(손절 -5%·익절 +2%·트레일링 2%(수익 중일 때만)·일일리셋), MA50 시장필터·최대보유일 7일은 "백테스트 전용" 명시. 전략 표의 디폴트 변경 반영.
8. **#21 헬퍼화**: PositionManager 내 `getAccounts().find { it.currency == … }` 5곳(syncPosition·applyFillOutcome·recoverFromBalance·sell·getKrwBalance) → `private suspend fun findAccount(currency: String): Account?`. TradeExecutionService:128 은 client 가 파라미터(사용자별 인스턴스)라 패턴이 달라 범위 제외.

## 테스트 전략 (TDD)
- staleness: TradingEngineTest — (a) fresh store ticker → store 가격, (b) stale store ticker → WS 폴백, (c) store/WS 모두 stale → null, (d) 30s 경계값(29.9s fresh/30.1s stale). Red 먼저. **기존 setup 의 `getLatestPrice returns null` 스텁을 `getLatestTicker returns null` 로 교체**(relaxed mockk 가 non-null child mock 반환해 기존 테스트가 미정의 동작이 되는 것 방지). stale 폴백 발생 시 로그 1줄(피드 장애 관측성).
- pnl net: PositionManager sell 의 record.pnlPercent == gross − 0.1 검증 + **게이트 gross/기록 net 분리 고정 테스트**(gross +2.05 에서 checkTakeProfit true AND 기록 1.95). Red 먼저. TradeExecutionService MANUAL 경로 net + null 보존 검증.
- BollingerBounce: falling-knife(prev<lower, current>lower 인데 current<prev) → false. Red 먼저. 기존 positive 테스트의 if-guard 무의미 통과 함정 금지(무조건 assert).
- 백테 폴백·yml 키·디폴트 전략·README: 설정/문서 치환 — 기존 회귀 + 컴파일로 커버(사유: 단순 디폴트 치환, controller 단위 테스트 부재).

## Rollback
- 코드: 단일 브랜치 PR — revert 로 롤백 가능.
- 데이터: net pnl 레코드는 revert 후에도 잔존 — 머지 시각 기록으로 `created_at` 기준 구분(보정 SQL 가능 상태 유지).
- 배포 구성: 로컬 `deploy/aws/.env` 변경분은 git 밖 — 롤백 시 수동 원복 필요(Report 에 명시).

## 운영 노트
- 이 PR 배포(봇 재시작 수반) 시 `syncPosition` 이 peakPrice 를 복원하지 않는 기존 한계로 트레일링 고점이 리셋됨 — **무포지션 시점 배포 권장**.
- BollingerBounce 는 prod 미사용(2026-06-02 users 정리로 admin 단일 유저, combined 가동 — 메모리 근거) — 사전 DB 재확인 생략.

# Review Disposition

code-review(claude code-reviewer + codex 병행, REQUEST CHANGES) — fix loop 1회차 (커밋 16de5e8):
- **fix**: compose prod env 4키 미매핑(Critical) / deploy .env.example stale(Major) / avgBuyPrice≤0 가짜 −0.1%(Major — null 보존+테스트) / compose 디폴트 2곳 잔존 / getLatestPrice 죽은 코드 삭제 / WARN 스로틀(1분/ticker) / 경계 테스트 마진 25s / MANUAL currentPrice≤0 → null(+테스트)
- **defer**: gross/net 전환 경계 — 머지 시각을 이슈 #27 코멘트로 기록(plan 결정대로), DB 컬럼 분리는 범위 외
- **wontfix**: V3 migration DDL default(적용된 migration 수정 금지, 코드가 항상 명시 INSERT) / TradeRecord.price=0 기록(기존 동작, pnl 만 null 로 보호) / 클럭 skew 근본 개선(ingestion 수신시각 저장)은 후속
- **고지(영향 범위)**: UI 백테스트 폴백이 TP5/SL3→TP2/SL5 로 변경(의도된 라이브 정합 — UI 는 해당 파라미터 미전송이라 전면 적용)

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — staleness 가드
- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — pnl net + findAccount 헬퍼
- `bot/src/main/kotlin/com/trading/bot/engine/TradeExecutionService.kt` — MANUAL pnl net (TradingProperties 주입)
- `common/src/main/kotlin/com/trading/common/config/TradingProperties.kt` — roundTripFeePct·strategy 디폴트
- `common/src/main/kotlin/com/trading/common/strategy/BollingerBounce.kt` — falling-knife 가드
- `bot/src/main/kotlin/com/trading/bot/api/StrategyController.kt` — 백테 폴백
- `bot/src/main/kotlin/com/trading/bot/persistence/entity/BotStateEntity.kt` — strategy 디폴트
- `bot/src/main/resources/application.yml` — trailing-stop-pct·round-trip-fee-pct·strategy 디폴트
- `README.md` — 리스크 관리 표 정정

# Blockers

(없음)
