---
title: trading-state-durability — per-ticker 거래 상태의 재시작 생존 (#19 halt 상한 + #20 pending durable + 포지션 메타 영속)
status: blocked
started: 2026-07-12
updated: 2026-07-12
---

# Goal

메모리 전용인 per-ticker `TradingState` 의 거래 무결성 필드를 durable 하게 영속화해 재시작/크래시/배포를 견디게 한다. 세 갈래를 하나의 응집 작업으로: ① #20 pendingBuyUuid(+order-state 작업의 pendingSellUuid) durable 영속화, ② #19 reconcile 무한 pending halt 상한 + Discord alert(halt 상태도 영속), ③ 포지션 메타(entryStrategy·진입 시점 청산 파라미터 스냅샷·peakPrice) 영속 — strategy-evolution-loop Phase 2 와 order-state 정책("진입 시점 설정으로 청산")의 선결 조건.

# Progress

- 2026-07-12: plan 간 틈새 분석에서 발굴 — 3개 plan(order-state-integrity·engine-lifecycle·strategy-evolution-loop)이 의존하는데 소유자가 없던 작업. #19/#20 이슈 본문 대조(#20 스스로 "H8 만 durable vs 상태 전체 영속화 설계 검토 필요"를 남김), 코드 확인: BotStateEntity/BotStateRepository 는 per-user 실행 상태만 저장(UserTradingManager.kt:63,147), per-ticker TradingState(TradingState.kt:9-20)는 전부 메모리. 구현 미착수.
- 2026-07-12: codex 검토(medium, read-only) 반영 — major 3건: ① pending 기록 실패를 "warn+계속"으로 두면 #20 의 크래시 구멍이 그대로 남음 → 해당 ticker 신규 진입 차단+alert 로 강화, ② position/잔고 제외 전제는 order-state 의 unsynced 재시도 게이트 존재에 의존함을 명시, ③ 복원 주입은 UserTradingManager 배선만으론 불가 — TradingEngine 이 상태를 private lazy 초기화(:57,:120-127)하므로 초기 상태 주입 API 신설 + syncPosition 병합 순서 정의 필요. minor: lastTradeTime 비영속 명시. Claude plan-reviewer 는 세션 쿼터 소진으로 생략(§9 사유 기록) — 착수 시 dlc plan 단계에서 보완.

# Next

order-state-integrity 머지 대기(TradingState 필드 확정 선행 — Blockers). 해소 후 첫 작업(TDD): "pending 보유 상태로 재시작 → 복원 → reconcile 재개 → TradeRecord 생성" 재현 테스트부터.

# Decisions

- **영속 범위 = 거래 무결성 최소 필드 집합(#20 의 설계 질문에 대한 답)**: per-(userId, ticker) 신규 테이블 `trading_states` 에 pendingBuyUuid/pendingBuyStrategy(+order-state 도입 예정 pendingSellUuid/사유), entryStrategy, buyDate, boughtToday, **peakPrice**(유실 시 재시작 후 트레일링 스톱 리셋 — 청산 지연 실손실), 진입 시점 청산 파라미터 스냅샷(jsonb), halt 상태/사유/누적 실패 수. **제외**: position/avgBuyPrice/holdVolume — syncPosition 이 거래소 잔고에서 복원(거래소가 진실 소스). ⚠️ 이 제외는 **order-state 의 unsynced 재시도 게이트가 있어야만 안전**(현재 syncPosition 은 실패를 삼키고 매수 평가로 진행 — codex 확인 PositionManager.kt:33-44, TradingEngine.kt:121-127) — 선행 의존의 실질 이유. lastTradeTime 은 **비영속**(프로덕션 읽기 없음 확인). 전체 객체 blob 직렬화는 스키마 진화·부분 갱신에 불리해 기각.
- **쓰기 시점 = 상태 전이 동기 upsert, 실패 정책은 리스크 비대칭**: placeOrder 직후 pending 기록, 체결 확정 시 해소, 진입 시 메타 기록. **pending 기록 실패는 best-effort 가 아니다**(codex major: 그 순간 크래시하면 #20 구멍 재현) — 기록 실패 시 해당 ticker **신규 진입 차단 + ERROR alert**, 거래소 reconcile 로 pending 해소될 때까지 유지. 메타(peakPrice 등) 기록 실패만 warn+재시도(유실 시 보수적 동작으로 퇴화).
- **#19 halt**: reconcilePendingBuy 의 getOrder+getAccounts 지속 실패 N회(설정) 누적 시 해당 ticker halt + Discord ERROR(이슈 본문의 해결 방향 그대로: pending clear 후 재매수는 금지 — H8 재발 방지). halt 는 영속되어 재시작 후에도 유지, 해제는 수동(운영 API 또는 SPA — 해제 시 reconcile 1회 강제 후 정상 복귀).
- **복원 경로 = TradingEngine 초기 상태 주입 API 신설**: 엔진 상태는 private lazy 초기화(TradingEngine.kt:57,:120-127 — 빈 TradingState 생성)이고 createEngine 에 초기 상태 파라미터가 없다(UserTradingManager.kt:180-198) — 생성자/start 시점 initial states 주입을 추가하고 **syncPosition 과의 병합 순서를 정의**(durable 복원값 주입 → syncPosition 이 position/잔고를 거래소에서 덮어씀 → pending 은 reconcile 로 해소). engine-lifecycle 의 restore 개편과 같은 파일 — 그 plan 머지 후 착수(Blockers).
- **소유권 정리(타 plan 과의 계약)**: order-state-integrity 는 pendingSellUuid 를 **메모리까지** 구현(그 plan 명시), 이 plan 이 durable 화를 인수. strategy-evolution-loop Phase 2 의 "보유 포지션은 진입 시점 설정으로 청산" 정책과 스왑 게이트("in-flight 없음" 판정의 재시작 신뢰성)는 이 plan 의 산출물에 의존 — 이 plan 이 strategy-evolution Phase 2 보다 먼저 머지되어야 함.
- **Closes #19, #20** (PR 에서 연결).

# Key Files

- `bot/src/main/kotlin/com/trading/bot/domain/TradingState.kt` — :9-20 영속 대상 필드(order-state 머지 후 pendingSellUuid 추가분 포함)
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — :57,:120-127(private lazy 상태 초기화 — 초기 상태 주입 API 신설 지점)
- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — :80-106(reconcilePendingBuy — halt 카운터 삽입점), pending 기록/해소 전이 지점
- `bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt` — 엔진 생성 시 복원 주입(:180-198), BotStateRepository 사용례(:34,:63,:147 — 동일 패턴의 신규 repository)
- `bot/src/main/kotlin/com/trading/bot/persistence/` — 신규 TradingStateRepository + entity (BotStateEntity 패턴 준용)
- `bot/src/main/resources/db/migration/` — trading_states 테이블(V 번호는 착수 시 확인 — stock-bot-kis V14-16 선점·dead-path drop migration 경합 주의)
- `bot/src/main/kotlin/com/trading/bot/notification/DiscordNotifier.kt` — halt ERROR alert
- `bot/src/test/kotlin/com/trading/bot/engine/PositionManagerExtendedTest.kt` — reconcile 테스트 패턴 확장

# Acceptance

- [ ] 재시작 생존: pending 보유 상태 저장 → 새 엔진 복원 → reconcile 재개 → 체결 확정 시 TradeRecord 생성 테스트 green
- [ ] halt: reconcile 실패 N회 주입 → ticker halt + ERROR 로그, pending clear 후 재매수 금지 확인, 재시작 후 halt 유지, 수동 해제 후 정상 복귀 테스트 green
- [ ] boughtToday·buyDate 복원으로 재시작 후 당일 1회 진입 규칙 유지 테스트 green
- [ ] peakPrice 복원으로 재시작 전후 트레일링 스톱 판정 연속성 테스트 green
- [ ] 진입 시점 청산 파라미터 스냅샷: champion 파라미터 변경 후에도 기존 포지션이 진입 시점 값으로 청산 판정(전이 시나리오) — strategy-evolution 과 공유하는 계약 테스트
- [ ] DB 기록 실패 시 주문 흐름 비차단(warn) + 연속 실패 ERROR 승격 테스트
- [ ] `./gradlew test` 전체 green

# Blockers

- **order-state-integrity 머지 선행**: TradingState 신규 필드(pendingSellUuid·unsynced)와 매도 reconcile 훅이 확정돼야 영속 스키마가 안정. **engine-lifecycle 머지 선행**: UserTradingManager restore 경로 개편과 같은 파일 — 개편 후 복원 주입 지점이 확정됨. 권장 순서: order-state → engine-lifecycle → **이 plan** → strategy-evolution Phase 2.
- 해소 시 status: in_progress 전환.
