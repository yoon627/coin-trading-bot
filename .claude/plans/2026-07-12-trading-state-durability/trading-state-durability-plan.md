---
title: trading-state-durability — per-ticker 거래 상태의 재시작 생존 (#19 halt 상한 + #20 pending durable + 포지션 메타 영속)
status: in_progress
started: 2026-07-12
updated: 2026-07-26
---

# Goal

메모리 전용인 per-ticker `TradingState` 의 거래 무결성 필드를 durable 하게 영속화해 재시작/크래시/배포를 견디게 한다. 세 갈래를 하나의 응집 작업으로: ① #20 pendingBuyUuid(+order-state 의 pendingSellUuid) durable 영속화 + **재시작 reconcile 멱등성**, ② #19 reconcile 무한 pending halt 상한 + Discord alert + 수동 해제 API(백엔드), ③ 포지션 메타(entryStrategy·진입 시점 청산 파라미터 스냅샷·peakPrice·boughtToday·buyDate) 영속. **스냅샷 "소비"(진입 시점 값으로 청산)는 strategy-evolution Phase 2 로 이관** — 이 PR 은 저장+복원까지(현재 청산 파라미터가 전역이라 지금 소비해도 관측 효과 없음).

# Progress

- 2026-07-12: plan 간 틈새 분석에서 발굴 — 3개 plan(order-state-integrity·engine-lifecycle·strategy-evolution-loop)이 의존하는데 소유자가 없던 작업. #19/#20 이슈 본문 대조(#20 스스로 "H8 만 durable vs 상태 전체 영속화 설계 검토 필요"를 남김), 코드 확인: BotStateEntity/BotStateRepository 는 per-user 실행 상태만 저장(UserTradingManager.kt:63,147), per-ticker TradingState(TradingState.kt:9-20)는 전부 메모리. 구현 미착수.
- 2026-07-12: codex 검토(medium, read-only) 반영 — major 3건: ① pending 기록 실패를 "warn+계속"으로 두면 #20 의 크래시 구멍이 그대로 남음 → 해당 ticker 신규 진입 차단+alert 로 강화, ② position/잔고 제외 전제는 order-state 의 unsynced 재시도 게이트 존재에 의존함을 명시, ③ 복원 주입은 UserTradingManager 배선만으론 불가 — TradingEngine 이 상태를 private lazy 초기화하므로 초기 상태 주입 API 신설 + syncPosition 병합 순서 정의 필요. minor: lastTradeTime 비영속 명시. Claude plan-reviewer 는 세션 쿼터 소진으로 생략(§9 사유 기록) — 착수 시 dlc plan 단계에서 보완.
- 2026-07-18: **blocker 둘 다 해소**(order-state #39·engine-lifecycle #43 main 머지) → main(6ec7157) rebase(코드 충돌 0, plan 커밋만 재배치). status blocked→in_progress. sync 진단(라인 shift): TradingState 영속필드 확정(peakPrice:13·buyDate:14·boughtToday:15·entryStrategy:17·pendingBuyUuid:19·pendingBuyStrategy:20·pendingSellUuid:23·pendingSellReason:24·unsynced:27). engine-lifecycle 이 createEngine 을 internal 화(:284)·SmartLifecycle 도입. TradingEngine states:63·초기화 :140. reconcilePendingBuy:120. migration 최신 V13 → trading_states=V14.
- 2026-07-18: /c 이어받기 — 최신 origin/main(1ecee2e) 위로 재rebase(engine/migration 무관, 충돌 0). V13 최신 재확인. dlc structural 파이프라인 진입.
- 2026-07-19: **Explore 완료.** R2DBC reactive(prod=Postgres, 단위테스트=mockk, 실DB/flyway 없음). 복원 주입점=runLoop:139-145(computeIfAbsent 빈상태→syncPosition). **dead `positions` 인프라 발견**(V11 테이블+PositionEntity+PositionRepository 프로덕션 미사용). halt 삽입점=reconcilePendingBuy:120. halt alert=log.error→DiscordErrorLogAppender. 사용자 확정: (b) 새 테이블+dead 정리, 스냅샷=VARCHAR JSON.
- 2026-07-21: **구현 완료(dlc 9~10) — 전체 test green(exit 0), 기존 회귀 0.** 도메인(markBought replace 가드·halt 필드·clearHalt·pendingPersistFailed·exitParams·TradingProperties threshold) + 인프라(TradingStateEntity/Repository/Service·V14 CREATE+DROP+unique·ExitParamsSnapshot·dead positions 삭제) + PositionManager(seam·pending durable·halt 카운터·멱등 dedup·replace·sell 대칭) + TradeExecutionService(exchange_order_id·dedup) + TradingEngine(start initialStates·halt 게이트·주석 drift) + UserTradingManager(seam 배선·복원·부분복원 격리·halt 해제) + DailyReset flush + TradingController(halt 해제 endpoint·status 노출). 신규 재현 테스트: Critical① 이중계상 없음·#19 halt·카운터 reset·Critical② 멱등 dedup 2건. 16 files +411/-82 + 신규 5.
  - **slice test(Acceptance) defer**: 이 프로젝트는 통합테스트 인프라(test resources·H2 flyway 하네스)가 전무 — slice test 하나를 위한 하네스 구축은 과도한 스코프 확대라 별도 이슈로 분리. V14 SQL 문법·entity 매핑·exchange_order_id unique 는 flyway 부팅 검증 + code-review 로 커버(mock 사각은 남는 리스크로 명시).
- 2026-07-19: **arch planning + plan-reviewer(codex gpt-5.5 병행) 완료 — 양측 강하게 합치.** Critical/blocker: ① **markBought 이중계상**(durable pending 복원+syncPosition→completeBuy averaging→holdVolume 2×·spurious BUY, TradingState.kt:52-58 검증됨), ② **재시작 reconcile 멱등성 부재**(exchange_order_id 미기록·unique 없음→중복 TradeRecord), ③ **placeOrder↔durable pending 취소 창**(NonCancellable 안·awaitFill 이전 완료 필수), ④ **주입 seam 미정**. Major: 실패정책 Acceptance↔Decisions 모순, 스냅샷 소비부 미포함, 부분복원 격리 없음, halt 해제 endpoint 부재, peakPrice write 증폭, resetDaily durable flush 누락, reload memory→durable 유실. 직렬화=Jackson(bot 은 kotlinx 미사용). **사용자 스코프 확정: 스냅샷 소비=Phase 2 이관, halt 해제=백엔드 endpoint 만.** 전 지적을 아래 Decisions/Acceptance 에 반영.
- 2026-07-26: **base drift 해소** — 최신 origin/main(bfb237f) rebase 완료(`ffb1c5a`). 충돌 2건 해결: ① `TradingEngine.start` 의 `webSocketClient?.subscribe` 제거(#48 이 UpbitWebSocketClient 삭제) + initialStates seed 유지, ② halt 게이트를 #47 이 도입한 internal 3-arg `processTicker` seam 안으로 이동(2-arg wrapper 는 위임만). `UserTradingManagerTest` 생성자 인자에서 upbitWebSocketClient 제거·tradingStateService 유지. rebase 후 `compileKotlin`+`compileTestKotlin`+전체 `test` green(JDK21). code-review 의 "processTicker 회귀테스트 8개 삭제" 는 예상대로 base drift 착시였음(#47 테스트 그대로 편입). 브랜치는 origin 백업 완료(`CODEX_SKIP=1` — 리뷰 통과 아님, 소실 방지용). 작업 순서는 트래킹 이슈 #49 에 등록(우선순위 1).
- 2026-07-26: **code-review Critical 5건 fix 완료**(TDD, 각 커밋마다 전체 test green). `00f016d` daily-reset 거래일화(boughtDate 신설 + domain/TradingDay 추출) · `2ffc9dc` durable 로드 실패의 silent 미복원(restoreOne/startBot 선평가, containsKey→isRunning, reloadUserRuntime 은 stop 이후 로드 유지하되 실패 시 교체엔진 미등록) · `d4f82e5` halt 게이트 buy() 이동 + pendingPersistFailed 매 tick 재기록 + loadStates 필드단위 격리. **리뷰 제안 1건 반려**: "lastResetDate 를 시작 시 오늘로 초기화"는 9AM 경계를 넘긴 재시작에서 리셋이 영영 안 걸려 당일 매수를 막는 반대 버그 — 대신 boughtDate 로 근본 해소(Decisions). 신규 테스트 9개(DailyResetManager 3·TradingState 2·PositionManagerExtended 3·UserTradingManager 2·TradingStateService 3, 기존 2개 시그니처 갱신).
- 2026-07-26(4): **3차 code-review(subagent+codex) = REQUEST CHANGES(경미) → 대응 완료.** `0a9c191`: **Major-1 = 2차 fix 의 미봉 확인** — `markBought` 의 `resuming=position` 은 "잔고 있음" 과 "같은 포지션 재개" 를 구분 못 하고, `runLoop` 이 루프 진입 전 전 ticker `syncPosition` 을 돌리므로(TradingEngine.kt:178) 체결 확인 전 재시작 시 `position=true` 가 먼저 서서 옛 메타가 재상속된다. **진입은 체결이 아니라 주문에서 시작**하므로 `buy()` 가 pending 기록 시 `clearEntryMeta()` 로 끊어 crash 경로까지 닫음(`resuming` 은 방어망 유지). 회귀 게이트도 buy→재시작→syncPosition→reconcile 전체 순서로 재작성(직접 markBought 호출은 이 순서를 재현 못 함). 함께: NoBalance 카운터 리셋 durable 미반영(허위 halt) · 드롭 ticker orphan 경고를 ERROR 로(WARN 은 DiscordErrorLogAppender 가 버림) · `updated_at` 을 `Instant` 로 정렬(TIMESTAMPTZ↔LocalDateTime 은 repo 유일 조합·미검증 = deploy 리스크) · `onTrade` 주석 drift. **simplify**: `markSold` 의 진입메타 초기화를 `clearEntryMeta` 로 통합. **문서 동기화**: README API 표 + PROJECT_ANALYSIS(V14·마이그레이션 범위·엔드포인트). **471 test green.** 미수용은 후속 이슈 5건으로 분리(위 Next).
- 2026-07-26(3): **2차 code-review(code-reviewer subagent + codex 병행) = REQUEST CHANGES → 대응 완료.** Critical 1(syncPosition 이 locked 잔고를 못 봐 재시작 시 포지션 유실 → 무방비 보유 + 이중 노출) + Major 3(죽은 진입메타 상속으로 신규 포지션 즉시 청산 · reload 로드실패 방치 · 매도 reconcile 상한·알림 부재) + Minor 2 fix, 470 test green. 지적의 성격은 "이번 fix 의 버그" 가 아니라 durable 화로 position 만 비영속이 된 구조의 복원 경로 부족이었다. 미처리분 처분은 # Review Disposition 2026-07-26 절.
- 2026-07-26(2): **Major fix 완료 — 전체 467 test green.** `410ed7c` peakPrice 영속(updatePeakPrice 가 갱신여부 반환 → 상승 tick 만 flush) + recoverFromBalance 를 Filled/NoBalance/LookupFailed 로 3분류(미체결을 장애로 세어 멀쩡한 ticker 를 halt 하던 오탐 제거) · `8d112fd` clearHalt durable 실패 전파(실패 시 메모리 해제 롤백·error 응답) + markSold 가 exitParams 초기화 + reconcileHaltThreshold require(>=1) · `3c3f0a2` pendingSellVolume/AvgPrice 영속(재시작 후 잔고 0 만 보고 확정하던 유령 SELL 차단, 근거 없으면 pending 유지) · 비활성 ticker seed 필터. **테스트 신규 8개 추가**(halt 매수전용·pending 재기록 2·미체결 non-halt·peak flush 2·매도 복구 2·markSold 스냅샷). 기존 halt 테스트는 주석과 mock 이 어긋나 있어(잔고조회가 성공하고 있었음) 의도대로 교정. **simplify 보류 1건**: `TradeExecutionService.saveAndNotify` 의 `record.userId ?: 0` 은 리뷰가 dead 로 봤으나 null 유입 경로를 전수 확인 못 해 동작 보존(제안만 — §5).
- 2026-07-25: 구현(커밋 **ab89676**, test green) 후 **code-review(메인+codex gpt-5.5 high 병행) = REQUEST CHANGES**. 실재 Critical 다수 발굴 + **base drift 발견** → status blocked. ⓐ Critical: daily-reset `lastResetDate` in-memory 라 재시작 첫 tick 이 boughtToday 리셋(durable #6 무효·당일 재매수) / restoreOne 이 loadStates 실패 시 미기동 엔진을 map 에 남겨 영구 미복원(silent) / pendingPersistFailed 가드가 buy 초입이라 재기록 도달 불가(deadlock) / halt 게이트가 processTicker 초입이라 **매도·reconcile 까지 차단**(청산 갇힘) / JSON row-drop 격리가 pending UUID 까지 폐기 → 이중주문. ⓑ Major: recoverFromBalance 오탐 halt·**peakPrice 미영속(plan 결정했으나 구현 누락)**·pending sell 수량 미저장(phantom zero SELL)·stale buyDate 상속·clearHalt 허위성공·NonCancellable timeout·V14 미검증/DROP. ⓒ **base drift**: origin/main 이 base(1ecee2e) 이후 3커밋 전진 — #47(processTicker 회귀테스트 추가 → code-review 의 "테스트 8개 삭제 Major"는 이 착시로 인한 **오탐**), **#48 UpbitWebSocketClient 제거(rebase 충돌)**, #42(backtest). 통과 항목: Critical① replace·멱등 dedup·userId 정합(onTrade copy)·backward compat.

# Next

**PR #50 오픈 — 리뷰/머지 대기.** 머지되면 status: done + 배포 절차(PR 본문) 수행. 후속 이슈 6건은 PR 본문 하단에 정리됨(별도 GitHub Issue 로 등록 필요).

# Decisions

- **[영속 범위] 거래 무결성 최소 필드**: per-(userId, ticker) 신규 테이블 `trading_states` 에 pendingBuyUuid/pendingBuyStrategy·pendingSellUuid/사유, entryStrategy, buyDate, boughtToday, peakPrice, 진입 시점 청산 파라미터 스냅샷(VARCHAR JSON — 하단), halt 상태(halted/haltReason/reconcileFailureCount). **제외**: position/avgBuyPrice/holdVolume — syncPosition 이 거래소 잔고에서 복원. ⚠️ 이 제외는 order-state 의 unsynced 재시도 게이트에 의존(선행 이유). lastTradeTime 비영속. 전체 blob 직렬화는 스키마 진화·부분 갱신 불리로 기각.
- **[Critical① 복원 병합 규칙 — markBought idempotency 가드]**: durable pendingBuyUuid 복원 시 그 주문이 이미 체결됐으면 syncPosition 이 먼저 position=true·holdVolume=balance 로 채우고, 이후 reconcile→completeBuy→`markBought`(TradingState.kt:52-58)가 `if(position)` averaging 경로로 진입 → holdVolume≈2×balance·avgBuyPrice 오평균·spurious BUY 기록. **이 기능의 대표 시나리오에서 터짐(검증됨).** 대응: markBought 에 idempotency 가드 — 이미 이 fill 이 반영(holdVolume≈balance)돼 있으면 averaging 대신 pending clear + entryStrategy/buyDate 세팅만. runLoop init 순서: durable seed → syncPosition → tick reconcile.
- **[Critical② 재시작 reconcile 멱등성]**: `saveAndNotify`(TradeExecutionService.kt:175-186)가 `exchange_order_id` 를 안 채우고 V11 에 unique 도 없음 → 체결 기록 후 durable pending clear 커밋 전 크래시 시 재시작 reconcile 이 같은 uuid 로 **중복 기록**. 대응: **exchange_order_id = order uuid 를 채우고**, reconcile(completeBuy/completeSellRecord) 전에 "이 uuid 로 이미 trade_executions 에 기록됐나" 확인 → 있으면 기록 skip + pending clear 만. V14 에 `trade_executions.exchange_order_id` 부분 unique 인덱스(NOT NULL). (트랜잭션 묶기 대안보다 uuid dedup 이 크래시 위치 무관하게 견고.)
- **[Critical③ 취소 창 + 쓰기 시점]**: durable pending upsert 는 suspend R2DBC → **placeOrder 반환 직후, `withContext(NonCancellable)` 안에서 awaitFill 이전**에 완료(PositionManager.kt:98·104 구간). placeOrder↔upsert 사이에 reload/stop 취소가 끼면 "주문은 나갔는데 durable pending 없음". 취소(정상 종료)와 예외(DB 장애)를 구분: 취소는 전파, DB 예외는 pendingPersistFailed 게이트.
- **[Blocker④ 주입 seam] = concrete `TradingStateService` + userId 를 PositionManager 에 주입**: PositionManager 는 현재 upbitClient+props 만 의존. durable write 를 상태 전이 지점(pending set:98 등)에 co-locate 하려면 seam 필요. house style(TradeExecutionService 도 concrete service, interface 아님)을 따라 `TradingStateService`(upsert/load/멱등확인 담당)를 만들고 PositionManager(client, props, tradingStateService, userId) 로 주입. userId 는 createEngine:296(user.id!!) 공급. 테스트는 service mock. (arch 는 함수형 port 도 제안했으나 house style 일관 우선.)
- **[실패정책 — 리스크 비대칭, 2항목 분리]**: **pending 기록 실패 = 차단** — `pendingPersistFailed` 게이트(unsynced 선례 재사용)로 buy() 초입 신규진입 차단 + ERROR alert, 다음 tick 재기록/reconcile 성공 시 해소. **메타(peakPrice/boughtToday) 기록 실패 = warn+재시도**(유실 시 보수적 퇴화). Acceptance 를 이 둘로 분리(기존 모순 해소).
- **[#19 halt]**: reconcilePendingBuy 에서 **getOrder throw + recoverFromBalance 도 null**(둘 다 실패) 경로만 reconcileFailureCount++, `wait`(정상 진행중)·`cancel+0 abandoned`(pending 해소)는 카운트 금지, 성공 시 0 reset. count≥threshold(TradingProperties 신설) → state.halted=true + haltReason + log.error(→DiscordErrorLogAppender). processTicker:208 초입 `if(state.halted) return` 게이트. halt 영속·재시작 유지.
- **[halt 수동 해제 = 백엔드 endpoint (SPA 제외, 사용자 확정)]**: TradingController 에 신규 endpoint(현재 부재) + current-user authz(타인 봇 해제 차단) + status 응답에 halt 노출. 해제는 UserTradingManager(per-user lock 중재) 메서드 경유 — engine states 는 private 이라 해제용 신규 메서드 필요(getStates 는 복사본). 해제 시 halt clear + 다음 tick reconcile 재개.
- **[복원 API] = `start(tickers, initialStates: Map<String,TradingState> = emptyMap())`**: 3 진입점(restoreOne:178·startBot:207·reloadUserRuntime:280)이 모두 engine.start 로 수렴 → 한 곳 주입으로 균일 커버. 기본값 emptyMap 으로 기존 호출부·테스트 무변경(blast radius=UserTradingManager 배선). durable read(suspend/reactive)는 UserTradingManager(botStateRepository 이미 보유)가 TradingStateService 로 수행 → Map 매핑해 start 전달. 생성자 주입은 computeIfAbsent non-suspend 람다 제약으로 기각.
- **[reload 정합] durable authoritative**: reloadUserRuntime 이 memory states 폐기 후 durable+syncPosition 재구축 → 상태 전이마다 durable flush 완결이 전제(peakPrice/boughtToday flush 정책과 일관). in-memory 갱신이 durable 로 안 내려가면 reload 로 유실.
- **[peakPrice write 정책]**: updatePeakPrice 는 매 tick 2회 호출(TradingEngine:230·PositionManager:439) → 매번 upsert 는 write 증폭. **값 상승(실제 갱신) 시에만 flush** + 매도 판정 직전 보장. resetDaily(boughtToday=false)도 durable flush 동반(9AM reset 후 재시작 시 boughtToday=true 복원으로 재진입 재차단 방지).
- **[부분 복원 격리]**: restoreOne(UserTradingManager:181-184)은 예외 1개에 유저 전체 미복원 → 손상 JSON row 1개가 봇 전체 복원 차단. **per-ticker row decode 실패 시 해당 ticker 만 빈 상태로 격리(syncPosition 재구축)**, 나머지 ticker 는 복원 진행 + 격리 ticker WARN.
- **[스냅샷 컬럼 타입 = VARCHAR/TEXT JSON, 직렬화=Jackson (사용자 확정)]**: 진입 시점 청산 파라미터를 Jackson(bot 은 kotlinx.serialization 미사용 — plan-reviewer 확인)으로 JSON 직렬화해 VARCHAR/TEXT 저장. H2/Postgres 중립·매핑 단순. jsonb 는 단위테스트(repo mock) 매핑 미검증이라 기각. **소비(exit gate read)는 이 PR 밖 — Phase 2**.
- **[스냅샷 소비 = strategy-evolution Phase 2 이관 (사용자 확정)]**: checkStopLoss/TrailingStop/TakeProfit·shouldSellForDailyReset 이 전역 tradingProperties read. 진입 시점 값 소비는 exit gate 3종+TradingState 모델 cross-cutting refactor → champion 파라미터 도입(Phase 2) 시점에. 이 PR 은 스냅샷 저장+복원까지, Acceptance 의 "진입 시점 값 청산"은 이 PR 밖.
- **[dead `positions` 정리 = (b) 신규+삭제 (사용자 확정)]**: ① V14 `DROP TABLE IF EXISTS positions` ② PositionEntity.kt 삭제 ③ TradingRepository.kt 에서 PositionRepository interface+import 제거(TradeExecutionRepository·BotConfigRepository 유지). 삭제 안전 재검증(양측): positions 역참조 FK 0·코드 사용처 0·테스트 0. strategy_signals 는 스코프 밖.
- **[rollback] V14 = trading_states CREATE + positions DROP, forward-only**: Postgres DDL 트랜잭션이라 all-or-nothing. 롤백은 V15 forward-fix 로만. **DROP 전 운영 DB 의 positions empty/non-prod 확인 또는 백업**(dead 라 비었을 것이나 절차 명시).
- **[Critical-1 daily-reset = 전역 lastResetDate 의존 제거, boughtToday 에 날짜를 붙임]** (2026-07-26): code-review 제안 두 가지 중 "시작 시 현재 tradingDate 로 초기화"는 **반대 방향 버그**를 만든다 — 봇이 9AM 경계를 넘겨 정지했다가 재시작하면 `lastResetDate == 오늘`이라 리셋이 영영 안 걸리고 어제의 `boughtToday=true` 가 남아 **당일 매수가 차단**된다. "lastResetDate durable 화"는 bot_state 컬럼 추가 + 리셋마다 별도 영속 경로를 TradingEngine 에 배선해야 한다. 근본 원인은 *당일 진입 여부*가 프로세스 수명 플래그에 의존한다는 것이므로, **`TradingState.boughtDate` 를 신설·영속화하고 `resetDaily(tradingDate)` 를 날짜 비교로** 바꾼다(`boughtDate != tradingDate` 일 때만 해제). 재시작 첫 tick 이 리셋을 호출해도 무해(멱등)하고, 경계를 넘긴 재시작도 정상 해제된다. 9AM 경계 규칙은 `DailyResetManager` 와 `markBought` 두 곳에서 필요하므로 `domain/TradingDay` 로 추출해 단일 소스 유지(도메인이 engine 클래스를 역참조하지 않게).
- **[주석 drift]** resolveExitStrategy(TradingEngine.kt:267-268) "entryStrategy 재시작 유실은 알려진 한계" 주석은 이 plan 으로 무효화 → 구현 시 동기화(§5).
- **[소유권 계약]** order-state 는 pendingSellUuid 를 메모리까지 구현, 이 plan 이 durable 인수. strategy-evolution Phase 2(진입시점 청산·스왑 게이트)는 이 plan 산출물 의존 → 이 plan 이 먼저 머지.
- **Closes #19, #20** (PR 에서 연결).

# Key Files

- `bot/.../domain/TradingState.kt` — :52-72 markBought(idempotency 가드 추가), :47-50 resetDaily(durable flush), 영속 필드 :13-27
- `bot/.../engine/TradingEngine.kt` — :63 states·:138-145 runLoop(초기 상태 주입 API·병합 순서), :208 processTicker(halt 게이트), :267-268 주석 drift, :346 onTrade
- `bot/.../engine/PositionManager.kt` — :98·104 buy(pending durable upsert·NonCancellable), :120 reconcilePendingBuy(halt 카운터+멱등 dedup), :186-209 completeBuy, :428-446 exit gate(Phase 2 소비 — 이 PR 미변경)
- `bot/.../engine/UserTradingManager.kt` — createEngine:284(seam 배선·userId), 복원 주입 :178/:207/:280, restoreOne 부분복원 격리:166, halt 해제 메서드 신설
- `bot/.../engine/TradeExecutionService.kt` — :175-186 saveAndNotify(exchange_order_id 채움·멱등 dedup), reactive→suspend 영속 service 선례
- `bot/.../engine/DailyResetManager.kt` — :46-49 resetDaily durable flush 동반
- `bot/.../api/TradingController.kt` — halt 해제 endpoint 신설 + authz + status halt 노출
- `bot/.../persistence/` — 신규 `TradingStateService` + `TradingStateRepository` + entity(BotStateEntity 패턴), TradingRepository.kt(PositionRepository 삭제), entity/PositionEntity.kt(삭제)
- `common/.../config/TradingProperties.kt` — reconcileHaltThreshold 신설
- `bot/src/main/resources/db/migration/V14__*.sql` — trading_states CREATE + positions DROP + trade_executions.exchange_order_id 부분 unique
- 테스트: `PositionManagerExtendedTest.kt`·`PositionManagerTest.kt`·`TradingEngineTest.kt`·`UserTradingManagerTest.kt`(생성자 blast radius, 기본값 흡수) + 신규 멱등/복원/halt 테스트 + Flyway+R2DBC slice/smoke test 1개(mockk 한계 보완)

# Acceptance

- [ ] **재시작 생존 + 이중계상 없음(Critical①)**: durable pending 복원 + syncPosition 이 balance 채운 상태 → reconcile → 단일 확정(holdVolume 2× 아님·avgBuyPrice 정상·BUY 기록 1건) green
- [ ] **재시작 reconcile 멱등(Critical②)**: 이미 trade_executions 에 기록된 pending 을 재시작 후 reconcile → 중복 기록 안 됨(exchange_order_id dedup) green
- [ ] **취소 창(Critical③)**: placeOrder 후 durable pending upsert 가 NonCancellable 안에서 완료(취소가 끼어들어도 주문↔pending 정합) green
- [ ] **halt(#19)**: getOrder+balance 둘 다 실패 N회 → ticker halt + ERROR + processTicker 게이트로 재시도 차단, pending clear 후 재매수 금지, 재시작 후 halt 유지, 수동 해제(authz — 타인 봇 차단) 후 reconcile 재개 green
- [ ] **boughtToday/buyDate**: durable 복원 → 재시작 후 당일 1회 진입 유지 + **9AM reset 후 재시작 정합**(재차단 없음) green
- [ ] **peakPrice**: durable 복원 → 재시작 전후 트레일링 스톱 판정 연속성 green
- [ ] **실패정책 2분리**: pending 기록 실패 → 신규진입 차단 + ERROR / meta 기록 실패 → warn+재시도(비차단) — 각각 green
- [ ] **부분 복원 격리**: 손상 JSON row 1개 → 해당 ticker 만 빈 상태 격리, 나머지 ticker 복원 진행 green
- [ ] **스냅샷 저장+복원**(소비는 Phase 2): 진입 시점 파라미터 JSON 저장 → 재시작 복원 시 state 에 실림 green (소비 청산 판정은 이 PR 밖)
- [ ] **dead positions 정리**: V14 적용 후 positions 없음, PositionEntity/PositionRepository 삭제, 앱 기동·기존 테스트 무영향
- [ ] Flyway+R2DBC slice/smoke test: trading_states 스키마·entity 매핑·exchange_order_id unique 실 DB 검증(mockk 사각 보완)
- [ ] `./gradlew test` + `./gradlew compileKotlin` 전체 green

# Blockers

- ~~order-state-integrity 머지 선행~~ · ~~engine-lifecycle 머지 선행~~ — **둘 다 해소**(#39·#43 머지·rebase 완료).
- ~~(2026-07-25) base drift~~ · ~~code-review REQUEST CHANGES~~ — **둘 다 해소**(2026-07-26: rebase 완료, Critical 5 + Major 7 fix). 남은 게이트는 **재리뷰**뿐.
- **(원문 보존) base drift**: origin/main 이 base(1ecee2e) 이후 3커밋 전진(#42·#47·#48). **#48 UpbitWebSocketClient 제거**가 내 변경(TradingEngine·UserTradingManager)과 충돌 → 재개 시 최신 origin/main rebase + 충돌 해결 필요. #47 은 processTicker internal 3-arg seam·회귀테스트 추가(정합 필요).
- **(2026-07-25 신규) code-review REQUEST CHANGES**: 아래 Review Disposition 의 Critical 5건 fix 전까지 머지 불가. 권장 머지 순서: **이 plan**(fix 후) → strategy-evolution Phase 2.

# Review Disposition

- arch Critical(markBought 이중계상) → **fix**(Decisions Critical①, markBought 가드 + 재현 테스트).
- plan-reviewer blocker(멱등성) → **fix**(Critical②, exchange_order_id dedup).
- plan-reviewer blocker(취소 창) → **fix**(Critical③, NonCancellable 순서).
- arch Major1/plan blocker(seam) → **fix**(concrete TradingStateService+userId 주입).
- 양측 Major(스냅샷 소비) → **defer to Phase 2**(사용자 확정 — 이 PR 스코프 밖).
- 양측 Major(halt endpoint) → **fix**(백엔드만, 사용자 확정).
- 양측 Major(부분복원 격리·resetDaily flush·peakPrice throttle·reload 정합) → **fix**(Decisions 반영).
- codex(Acceptance↔Decisions 모순) → **fix**(실패정책 2분리).
- codex(plan jsonb↔VARCHAR 표기) → **fix**(전면 VARCHAR JSON/Jackson 통일).

## code-review 2026-07-26 2차 (code-reviewer subagent + codex 병행) — REQUEST CHANGES 처분
지적의 성격이 "이번 fix 의 버그" 가 아니라 **durable 화로 'position 만 비영속' 이 된 구조의 복원 경로 부족**이었다(복원 경로가 `syncPosition` 단일·1회성).
- **Critical: syncPosition 이 free balance 만 봄** → **fix**(`4288a8c`). 매도 주문 in-flight 중 재시작하면 코인 전량이 locked 라 free=0 → "보유 없음" 으로 동기화 성공 처리 → 손절·익절 미평가 무방비 보유 + boughtToday 해제 시 추가 매수로 노출 2배. `totalBalance()` 로 판정하고, 매도 reconcile 이 잔여를 남기는 두 분기에서도 실잔고로 position 복원.
- **Major: markBought(replace=true) 의 죽은 진입메타 상속** → **fix**(`4288a8c`). 정상 매수도 전부 이 경로를 타는데 position=false 인 신규 진입이 durable 잔재(옛 buyDate·peakPrice·entryStrategy)를 물려받아 진입 즉시 DAILY_RESET/트레일링 청산. `resuming = position` 으로 갈랐고 buyDate 도 거래일 기준 통일(Minor 의 달력일/거래일 혼용도 함께 해소).
- **Major: reload 로드 실패 방치** → **fix**(`4288a8c`). 예외만 올리면 stop 된 엔진만 남아 손절 무기한 중단(running=true 라 무증상) → 옛 엔진 재기동 + ERROR.
- **Major: 매도 reconcile 에 상한·알림 부재** → **fix**(`aab97ae`). 매수판과 같은 상한에서 1회 ERROR. halt(차단)는 쓰지 않는다 — 막힌 포지션에 필요한 건 차단이 아니라 사람의 개입.
- Minor: NoBalance 가 연속 카운터 미리셋 → **fix**. 비활성 ticker 드롭 시 pending 있으면 WARN → **fix**.
- **Major: pending 해소 ↔ audit insert 비원자** → **defer**(기존 처분 유지). 두 저장소를 걸치는 트랜잭션이 필요하고 origin/main 대비 악화는 아니다. 별도 이슈 권장.
- **Major: V14 ↔ R2DBC 매핑 통합테스트 0건** → **defer**(통합테스트 인프라 부재 — 기존 처분 유지). Testcontainers 도입은 별도 이슈.
- Minor **defer**: peak flush 1회 실패 시 재시도 부재(dirty 플래그 필요) · reconcile 확정 매도 기록가가 실체결가 아닌 reconcile 시점 시세 · `upsert` SELECT+save 2왕복(ON CONFLICT 로 개선) · 일일 리셋 flush 가 전체 upsert · `clearHalt` 의 비동기화 mutate · placeOrder↔persistPending 크래시 창(거래소 idempotency key 가 정공법).
- **판단 보류**: `updatedAt` 을 `LocalDateTime`↔`TIMESTAMPTZ` 로 쓰는 것이 repo 컨벤션(`Instant`) 위반이라는 지적 — 타당하나 감사 컬럼 타입 변경은 별도로 다루는 편이 안전. V14 의 `DROP TABLE positions` 를 생성 migration 과 분리하라는 지적도 타당하나 **사용자 확정 결정(b)** 을 뒤집는 것이라 임의 변경하지 않음 — 배포 절차(rename/archive 선행)로 대응.
- refuted 8건(스키마 정합·partial index·TIMESTAMPTZ 디코딩 가능·halt 알림 폭주·fix4/fix12 부작용 등)은 근거와 함께 반증됨.

## code-review 2026-07-25 (메인+codex gpt-5.5 high) — REQUEST CHANGES 처분
- daily-reset `lastResetDate` in-memory(Critical) → **fix**: 시작 시 현재 tradingDate 로 초기화 or lastResetDate durable화(재시작 첫 tick 오리셋 방지).
- restore loadStates 실패 미기동(Critical) → **fix**: loadStates 를 `computeIfAbsent` 밖에서 선평가, 실패 시 엔진 미등록, `containsKey`→`isRunning` 검사.
- pendingPersistFailed deadlock(Critical/Major) → **fix**: buy 초입 가드가 재기록을 막음 — 매 tick pending 재upsert 경로 추가, 성공 시에만 게이트 해제.
- halt 게이트 매도 차단(Critical/Major) → **fix**: `buyHalted`(신규 매수만) 분리, sync/reconcile/위험축소 매도는 허용.
- JSON row-drop pending 유실(Critical/Major) → **fix**: pending UUID 는 항상 복원, exit_params 만 필드 단위 기본값, 핵심 손상 시 ticker quarantine.
- processTicker 회귀테스트 8개 삭제(Major) → **false-positive**: base drift 착시(#47 이 추가한 테스트, 내 브랜치 stale). rebase 로 편입, halt 게이트 정합만 확인.
- recoverFromBalance 오탐 halt(Major) → **fix**: FILLED/NO_BALANCE/LOOKUP_FAILED 3분류, NO_BALANCE 카운트 제외.
- peakPrice 미영속(Major) → **fix**: plan 결정(값 상승 시 flush) 구현 누락분 — dirty+throttle 저장 + stop/reload 전 flush.
- pending sell 수량 미저장→phantom zero SELL(Major) → **fix**: pendingSellVolume·기준평단 durable, zero-balance 단독 audit 확정 금지.
- stale buyDate/entryStrategy 상속(Major) → **fix**: markSold 가 position-scoped 메타(exitParams 포함) 초기화, reconcile-replace 와 신규 entry 경로 분리.
- clearHalt 허위성공(Major) → **fix**: durable commit 실패를 호출자에 전파(실패 응답).
- NonCancellable timeout 부재(Major) → **defer**: R2DBC/HTTP timeout 설정 확인 후 별도(원자구간 최소화 검토).
- V14 미검증/destructive DROP(Major) → **defer**(slice test 인프라 부재) + **배포 절차 필수**: 배포 전 trade_executions 의 (user_id, exchange_order_id) 중복 확인, DROP positions 는 rename/archive 후 후속 migration 권장.
- audit 유실 창(persist→record 순서, Minor) → **fix 검토**: record insert 를 pending clear 앞으로 / 또는 wontfix(hard crash 한정, position sync 복구).
- 알림 유실(Minor)·upsert N+1(Minor) → **defer**(별도). 비활성 ticker seed(Minor)·reconcileHaltThreshold @Min(1)(Minor) → **fix**.
- `record.userId ?: 0` dead·markBought replace=false dead branch(Nit) → **fix**(simplify 단계 정리).
