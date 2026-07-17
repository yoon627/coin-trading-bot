---
title: strategy-evolution-loop — 자기 방어를 갖춘 반자동 리서치 파이프라인 (지속 백테스팅→후보 발굴→통계 게이트→승인→카나리아→추적/강등)
status: in_progress
started: 2026-07-10
updated: 2026-07-18
---

# Goal

봇이 **주기적으로 백테스팅을 돌려 수익 후보(전략×파라미터)를 발굴하고, 통계 게이트와 사람 승인을 거쳐 실거래에 반영하며, champion 의 노화를 감지해 자동으로 리스크를 줄이는 루프**를 구축한다.

**기대치 명문화(설계 합의)**: 이것은 "완전 자동 자기성장"이 아니라 **자기 방어가 있는 반자동 리서치 파이프라인**이다. D1 스윙·사실상 단일 마켓·거래당 10만원 상한(application.yml:37-38)에서 통계적으로 정당한 승격은 **연 0~2건이 정상**이다. 더 빠른 성장을 원하면 루프 정교화가 아니라 표본을 늘리는 방향(전략 클래스·타임프레임·마켓 폭·투자 사이즈)을 바꿔야 하며, 그 결정은 루프가 아니라 사용자 몫이다.

# Progress

- 2026-07-10: 기존 자산 조사(BacktestEngine run/compareAll, ParameterSweepTest 1,800조합 1회성 스윕 이력, StrategyController 백테 API, #31·#32·#33) — "지속 루프"는 미고려 상태임을 확인. 독립 설계 2안(A: 통계 엄밀·사람 승인 / B: champion-challenger 자동화) + 적대 비판 워크플로 실행, 비판 권고(뼈대 A + B 이식 + 공통 결함 보정)로 종합해 plan 작성.
- 2026-07-10: plan-review(Claude subagent + codex 병행) 반영 — 실행 전제 4건 교정: ① 카나리아 investRatio 1/3 은 maxInvestAmount 캡(PositionManager.kt:291-294 minOf)에 흡수돼 무효 → 캡 동시 스케일, ② SHADOW paper 실행체가 코드에 없음 → 주문 포트 추상화 신설 명시, ③ "daily reset 직후 = 무포지션" 전제 오류(maxHoldDays>1 이면 포지션 존속) → position-free 실검사 게이트, ④ DB 직독 시 ASC↔최신-우선 순서 역행 위험 → parity 테스트. + 완결 D1 일일 증분 적재 컴포넌트 추가(현재 DB 적재는 M1 유일이라 백필만으론 rolling 재평가 불가), 타 plan 3개(engine-lifecycle·dead-path-cleanup·marketdata-consolidation) 파일 충돌·선후 명시, #19/#20 의존 명시. 구현 미착수.
- 2026-07-18: **Phase 0 #31 착수**. sync 진단 — plan 후 코드 미착수 확인(로컬 커밋=plan 1건, 미머지, PR 없음, worktree clean). 코드 조사: 전략이 `config` 에서 읽는 유일 신호 파라미터는 `kValue`(CombinedStrategy:17·VolatilityBreakout:16 `calculateTargetPrice`), 나머지 전략은 config 미read. BacktestEngine 은 `strategy.shouldBuy/Sell(.., tradingProperties)` 로 **live 0.5 고정** 전달 → `config.kValue`(StrategyController:87 검증·주입) 무영향이 #31 결함. **구현형태: `tradingProperties.copy(kValue=config.kValue)` 최소침습 확정**(신호 read 필드 단일이라 SignalParams 추상화 과잉). `BacktestConfig.investRatio` 는 read 0건 진짜 dead → 제거, `kValue` 는 연결·parity 가드 추가.
- 2026-07-18: **#31 구현·검증 완료(미push)**. `signalProps = tradingProperties.copy(kValue=config.kValue)` 를 신호(shouldBuy/shouldSell)에 전달, `investRatio` 제거, parity 가드에 `kValue` 추가. 신규 회귀 테스트 2개(`volatility_breakout`·`combined`) green, `:bot:test` 전체 통과(JDK21 prefix). code-review(Claude subagent + codex 0.134.0 medium 병행) **APPROVE — Critical/Major 0**. Minor fix 반영: 테스트 highK `3.0→2.0`(API 상한 경계값), `combined` 회귀 테스트 추가(라이브 기본 전략 경로 가드). ⚠️ Phase 0 나머지(#33 intrabar 청산 모델·M1 replay 편향·engine_version)는 **미착수** — Acceptance Phase 0 항목은 #31 조각만 충족.

# Next

Phase 0 #31 구현 중(copy 최소침습, TDD). 완료 후 Phase 0 나머지: **#33 intrabar 보수 청산 모델** → **M1 replay 편향 실측**(표본수 병기) → **engine_version 기록**. 승인 채널(SPA vs Discord) 확인은 Phase 2 안건이라 Phase 0 진행에 무관.

# Decisions

설계 워크플로(2안+적대비판) 및 plan-review 의 종합 결정:

- **뼈대 = A안 승인 상태기계**: 자동화 경계는 "리스크 방향" — 리스크를 줄이는 전이(강등·safe-mode·kill)는 자동, 리스크를 키우는 전이(승격·사이즈 복원)는 반드시 사람 승인. B안의 auto-promote 와 별도-ticker 실돈 파일럿은 **폐기** (이유: 전량매도 충돌(PositionManager.kt:211, volume=account.balance)로 같은 마켓 불가 → 다른 마켓 배정 시 파일럿 성과가 파라미터 효과가 아닌 마켓 효과 — 비교 무의미한 복권이 자동 승격을 당기는 최악 조합. 파일럿 엔진 영속 부재로 재시작 시 손절 게이트 없는 방치 포지션 사고 경로도 실측 확인).
- **Phase 0 = 정합 게이트 선행(루프 신뢰의 전제)**: ① #31 신호 파라미터 config 분리, ② #33 포함 **intrabar 보수 청산 모델** — 백테 processExit 가 봉 종가에서만 SL/TP/trailing 평가(BacktestEngine.kt:113-117), 라이브는 10초 tick — 모든 가격 게이트가 체계적으로 낙관. SL 은 low, TP 는 high 판정 + 동시 충족 시 worst-case 규칙, ③ **M1 replay 편향 실측** — M1 30일 보존(DataRetentionService.kt:21) 활용해 D1 청산 모델 편향을 정량화. 단 30일 창의 청산 이벤트가 한 자릿수일 수 있음 — 리포트에 **이벤트 수·불확실성 병기, 표본 미달 시 판정 유보**(공허한 게이트 방지), ④ engine_version 기록(시맨틱 변경 시 과거 run 비교 차단 + champion 재검증 강제). (이유: 모델 오차 > 표본 오차인 동안 통계 게이트는 장식 — 2026-06 스윕 arm 축 변별 불가로 실증)
- **데이터 기반**: DB `market_candles` 에 장기 D1 없음(M1 만 영속 — MarketDataPersistenceService.kt:46-63, 시드 200봉은 메모리 전용, upsert 호출부 전수 grep 으로 확인) → ① Upbit `/v1/candles/days` `to` 페이지네이션 **1회성 백필** + ② **완결 봉만 일일 증분 적재하는 신규 컴포넌트**(현재는 신규 D1 을 아무도 DB 에 안 씀 — 백필만으론 Phase 3 rolling 재평가 데이터가 노화. 당일 미완결 봉은 제외해 스냅샷 결정론 유지). 백테는 **DB 스냅샷에서만** 읽고 data_hash 기록. ⚠️ **순서 규약**: BacktestEngine.run 은 최신-우선 입력 전제(:71 reversed()), findByTimeRange 는 ASC(MarketDataRepository.kt:34) — 변환 계층 필수 + REST/DB **parity 테스트**(동일 입력 → 동일 BacktestResult)로 조용한 시간역행 차단. retention 은 interval=1 만 삭제하므로 D1 영구 보존.
- **통계 게이트(1차 회계 = 블록 부트스트랩 + BH-FDR)**: walk-forward 에서 선택은 IS 만, 판정은 OOS 만(캔들 범위를 서비스 계층에서 잘라 구조적 강제). per-trade **net** pnl% 통일(all-in 복리 totalReturn 비교 금지). plateau 이웃 안정성 + argmax 대신 군집 중심. DSR 은 **리포트 참고 지표로 강등**(trials=1,800·OOS n≈30 에서 게이트로는 통과 불가능한 "무제안 기계"). 홀드아웃은 "run 마다 전진하는 미접촉 신규 데이터"로만 정의 + OOS 1회 판정 캐시.
- **사전등록 3종(사후 튜닝 오염 방지)**: grid_version + **gate_version(게이트 임계 변경도 버전·사유와 함께 기록)** + 홀드아웃 전진 규칙. **임계는 선험적으로 박지 않는다**: Phase 1 을 3개월 관찰 운영(리포트만)으로 돌려 통과율·wall-clock·편차 실측 후 gate_version v1 캘리브레이션.
- **SHADOW → 제안 → 카나리아 → 풀**:
  - **SHADOW 실행체는 신설**(plan-review: 코드에 paper 경로 없음 — UserTradingManager 는 항상 실 UpbitClient 생성:154-161, V13 trade_mode 는 paper 아님): 주문 포트 추상화 또는 PaperUpbitClient(getAccounts/placeOrder/getOrder 시맨틱 스텁) + **"SHADOW 는 placeOrder 절대 불호출" 테스트**. 게이트는 통계 편차가 아니라 정합 버그·파국 검출.
  - 제안 리포트는 A 정보 설계(비교표 + **반대 근거 섹션** + 만료 2주, 묵시 승인 금지). 승인 POST 는 idempotency key + 2차 확인(CSRF disabled·SameSite=Lax 환경 — SecurityConfig.kt:25).
  - **카나리아 사이징**: investRatio 1/3 만으론 무효 — 실효 투자금이 `minOf(krwBalance×investRatio, maxInvestAmount)`(PositionManager.kt:291-294)라 캡 바인딩 구간에서 흡수됨 → **investRatio 와 maxInvestAmount 를 함께 1/3 스케일**. 카나리아는 통계 검증이 아니라 운영 정합 확인(n=10 검정력 0), 자동 원복은 파국 조건(서킷브레이커) 한정. 풀 승격은 2차 승인.
- **champion 파라미터 주입·스왑**: createEngine 전체 배선(TradingEngine·PositionManager·DailyResetManager — UserTradingManager.kt:182-191, 청산 게이트는 PositionManager 안) — 부분 주입은 손절이 옛 파라미터로 도는 실돈 버그. `strategy_champions` 단일 진실 소스(env 는 부트스트랩 폴백 — **DB 유실 시 조용히 env 파라미터로 회귀함을 운영 문서에 명시**). **스왑 시점은 시간 예약이 아니라 실검사 게이트**: "전 티커 position=false ∧ in-flight 주문 없음"을 검사해 통과 시에만 스왑(plan-review: maxHoldDays>1 이면 daily reset 직후에도 포지션 존속 — DailyResetManager.kt:36-51) + **스왑 예약 중 신규 진입 차단**(게이트가 영원히 안 열리는 것 방지) + **스왑 실패 시 이전 champion 복구 절차**(새 엔진 기동 실패 → 구 config 재기동, 드릴 항목).
- **bot_configs 관계**: 신규 `strategy_champions` 가 champion 파라미터의 유일 소스. 기존 bot_configs(V12, 엔진이 읽지 않는 CRUD 전용)는 이 plan 에서 건드리지 않고 후속 정리 후보로 # Deferred (UI 설정 의미 충돌 방지 — 승인 페이지가 champions 만 읽도록).
- **추적/강등**: champion 일간 rolling 재평가(최신 창 백테 — 위 증분 적재가 전제) + 실거래 성과 추적. 강등·kill 자동(재가동 수동). **승격 N주 후 사전등록 기준 자동 회고 리포트**. candidate_stage_events 감사로그.
- **실행 격리**: 판정이 DB 스냅샷 기반이라 실행 위치 무관 — Phase 1 wall-clock 실측 후 초과 시 로컬 CLI 폴백. EC2 에선 낮은 우선순위 단일 코루틴 + 스케줄러 풀(2스레드) 비점유.
- **경제성 정직 명문화**: 현 사이즈에서 연간 기대 개선액은 엔지니어링 비용 대비 작다 — 1차 정당화는 학습·인프라 자산(주식 편입 시 재사용), 금전 정당화는 사이즈 확대 계획과 함께만 성립(사용자 결정 — Blockers).
- **스코프 경계**: #32 는 SPA 승인 페이지와 합류 가능(별도 유지). stock-quant-strategy plan 과 Phase 4 에서 캔들 공급자 추상화 통일(그 plan 은 이 repo `.claude/worktrees/stock-quant-strategy/` 에 존재 — 설계 agent 의 "별도 repo 추정"은 오인, 정정).

# Roadmap (Phase 분해)

| Phase | 내용 | 규모 | 게이트 |
|---|---|---|---|
| 0 | **정합**: #31 신호 config 분리 + intrabar 보수 청산 모델(#33 일반화) + M1 replay 편향 실측(표본 수 병기) + engine_version | M | 편향 실측 리포트 산출 |
| 1 | **기반+관찰 운영**: D1 백필 + **완결 봉 일일 증분 적재** + DB 스냅샷 직독(순서 변환+parity 테스트) + research_runs/strategy_candidates + walk-forward/부트스트랩·BH-FDR·plateau 게이트 + 주간 Discord 리포트(반영 수동) — 3개월 관찰 → gate_version v1 | M~L | 코드 acceptance 즉시 + 관찰 acceptance 장기(분리) |
| 2 | **승인 폐루프**: promotion_proposals/strategy_champions + **주문 포트 추상화(SHADOW paper 실행체)** + SPA 승인 페이지(idempotency) + ChampionConfigService(전체 배선·실검사 스왑 게이트·재시작 복원) | M~L | env 수정·재배포 없는 승격 1건 완주 |
| 3 | **카나리아+강등 상태기계**: 카나리아(ratio+캡 동시 스케일) 2차 승인, champion 일간 열화 감지, 자동 강등·kill switch, 회고 리포트 | M | 강등·스왑 실패 롤백 드릴 각 1회 |
| 4 | **표본 확대**: 멀티마켓 pooled OOS(동일 파라미터 다마켓 동시 통과), 마켓별 투자한도 모델, KIS 주식 편입(캔들 공급자 추상화 통일) | L | — |

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt` — :71(reversed 최신-우선 전제), :113-117(processExit 종가 평가), :125,:155(#31), :64,:217, :191-196(미연율화 sharpe — 게이트 미사용)
- `bot/src/test/kotlin/com/trading/bot/engine/ParameterSweepTest.kt` — :48-68(grid 이관 원본)
- `bot/src/main/kotlin/com/trading/bot/api/StrategyController.kt` — :79(REST 재조회→스냅샷 직독), :43-49(trade_records 조회 패턴)
- `bot/src/main/kotlin/com/trading/bot/persistence/MarketDataRepository.kt` — :30-36(findByTimeRange ASC), :40-59(upsert 멱등)
- `bot/src/main/kotlin/com/trading/bot/stream/{MarketDataPersistenceService,DataRetentionService,CandleAggregator}.kt` — M1 영속 유일 경로·보존 정책·집계(메모리 전용) 구조
- `bot/src/main/kotlin/com/trading/bot/marketdata/{MarketDataIngestionService,UpbitMarketFeed}.kt` — :120-130(메모리 시드), UpbitMarketFeed.kt:115-133(`to` 오버로드 지점)
- `bot/src/main/kotlin/com/trading/bot/engine/{UserTradingManager,TradingEngine,PositionManager,DailyResetManager}.kt` — :182-191(전체 배선), :176(교체 경로), :154-161(실 클라이언트 고정 — 포트 추상화 지점), PositionManager.kt:266-294(청산 게이트·investRatio/캡), DailyResetManager.kt:36-51(스왑 전제 반증)
- `bot/src/main/kotlin/com/trading/bot/auth/SecurityConfig.kt` — :25(CSRF off — 승인 API hardening 근거)
- `bot/src/main/kotlin/com/trading/bot/notification/DiscordNotifier.kt` — 제안·다이제스트 embed
- `bot/src/main/resources/db/migration/` — 신규 4 테이블. **V 번호 경합원 2곳**: stock-bot-kis 브랜치가 V14-16 선점 실재, dead-path-cleanup 도 drop migration 예고 — 착수 시 renumber
- 신규 패키지: `bot/src/main/kotlin/com/trading/bot/research/` + 주문 포트 추상화(client 계층)
- `bot/src/main/resources/static/tide-app/` — 승인 페이지(#32 합류 가능)

# Acceptance

- [ ] Phase 0: #31·intrabar 모델 회귀 테스트 green + M1 replay 편향 리포트(**이벤트 수·불확실성 병기**, 미달 시 유보 명시) + engine_version 불일치 비교 차단 테스트
- [ ] Phase 1(코드): 백필 후 **per-market "상장일 이후 전 봉 존재·gap 0"** 검증(일괄 43k rows 기준은 후기 상장 마켓에서 실패 — 재정의), REST/DB **parity 테스트**(동일 입력→동일 BacktestResult) green, 완결 봉 증분 적재가 미완결 당일 봉을 제외함을 테스트, 동일 data_hash 재실행 결정론 재현, 주간 리포트 Discord 실측 수신
- [ ] Phase 1(관찰, 장기): 3개월 통과율·wall-clock·편차 실측 → gate_version v1 기록(임계+근거)
- [ ] Phase 2: 승격 시 세 컴포넌트 모두 새 파라미터 동작(부분 주입 회귀 테스트) + 재시작 복원 + SHADOW 가 placeOrder 를 호출하지 않음 테스트 + 스왑 실검사 게이트(포지션 보유 중 스왑 불발·예약 중 신규 진입 차단) 테스트
- [ ] Phase 3: 강등 드릴(열화 조건 주입→자동 축소·통지 실측) + **스왑 실패 롤백 드릴**(새 엔진 기동 실패→구 config 복구) + kill 후 재가동 수동 확인 + 카나리아 실효 투자금이 1/3 로 줄었는지 로그 실측
- [ ] 각 Phase `./gradlew test` green + 이 plan Progress 갱신
- [ ] 문서 동기화: PROJECT_ANALYSIS.md(research 서브시스템) + README(운영 — 승인·kill·DB 유실 시 env 회귀 주의)

# Blockers

- (착수 전 사용자 확인) **승인 채널**: SPA 승인 페이지(설계 채택 — 공격면 최소) vs Discord 버튼(모바일 편의, interaction endpoint 상시 노출). 기본값 SPA 로 진행 가능.
- (Phase 2 전 결정) **경제성/사이즈**: 현 상한 유지 시 학습·인프라 정당화 — 금전 정당화는 사이즈 확대 계획 필요(사용자 결정).
- (선결 — 조율 아님) **포지션 메타 영속화**: entryStrategy·진입 시점 config 스냅샷이 메모리 전용(TradingEngine.kt:228-229) — "보유 포지션은 진입 시점 설정으로 청산" 정책과 Phase 2 재시작 복원의 **선결 조건**. order-state-integrity plan 과 소유권 협의(그 plan 의 TradingState 확장에 편승 권장).
- (타 plan 충돌 — 선후 명시) **engine-lifecycle**: UserTradingManager reload/stop 경로(:163-178)가 이 plan 의 엔진 스왑 경로와 정면 겹침 — stop join 수정이 스왑 시맨틱을 바꾸므로 **engine-lifecycle 머지 후 Phase 2 착수**. **dead-path-cleanup**: MarketDataRepository(시간범위 쿼리 신설)·DataRetentionService 재편·drop migration 이 이 plan 의 "retention 무간섭·D1 영구 보존" 전제와 접촉 — 착수 시점의 머지 상태 확인. **marketdata-consolidation**: UpbitMarketFeed·MarketDataIngestionService 공유(getCandles `to` 오버로드 추가 지점) — 파일 소유권 조율.
- (의존 이슈) **#20**(pendingBuyUuid durable): 스왑 실검사 게이트의 "in-flight 주문 없음" 판정 신뢰성이 여기 의존(현재 메모리 전용이라 재시작 직후 오판 가능). **#19**(reconcile halt 상한): 카나리아 서킷브레이커의 halt 시맨틱과 접점. 두 이슈 미해소 상태로도 Phase 0-1 은 진행 가능.
- ❌미확정(구현 직전 확정): Upbit quotation rate limit 현행 수치(docs.upbit.com), EC2 스윕+부트스트랩 wall-clock(Phase 1 실측). (#31 구현 형태는 2026-07-18 copy 최소침습으로 확정)

# Review Disposition

## #31 (2026-07-18, code-reviewer Claude + codex 0.134.0 medium, 종합 APPROVE)
- **fix**: 테스트 highK `kValue 3.0→2.0` — API 상한(`StrategyController` `0.0..2.0`) 경계값, 실제 재현값. range=450 고정이라 동일 성립. 적용.
- **fix**: `combined` 전략 회귀 테스트 추가 — 라이브 기본 전략(TradingProperties.strategy 기본 `combined`)도 `config.kValue` read, 실사용 경로 가드. 적용·green.
- **false-positive**: `investRatio` 제거 = 생성자 시그니처 변경(codex Major) — 미publish 단일 앱 모듈, 유일 생성부(StrategyController) named-arg·미참조, BacktestResult JSON 미노출. 외부 계약 영향 0.
- **wontfix(현 스코프)**: signalProps 화이트리스트(kValue만 덮음) fragility — 향후 신호가 config 의 다른 필드 read 시 재발 소지. 현재 전략셋 무해, 주석(BacktestEngine.kt:83-84)으로 제약 명시. 신호 파라미터 확장 시 매핑 명시화로 대응.

# Deferred

- bot_configs(V12) CRUD 전용 레거시의 정리(strategy_champions 도입 후 의미 중복) — severity: low
- CLAUDE.md §9 가 참조하는 `docs/codex-review.md` 가 repo 에 부재(코드·문서 모두) — codex 호출 규약(effort·출력 처리) 단일 소스 없음. 이번 리뷰는 `codex exec review --uncommitted`(커스텀 프롬프트 상호배타라 기본 리뷰만) 로 우회. severity: low, 이번 작업 무관 — 별도 이슈화 검토 (2026-07-18 발견)
