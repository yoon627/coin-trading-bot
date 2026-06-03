---
title: trading-logic — 청산(매도) 로직 차트화
status: done
started: 2026-06-01
updated: 2026-06-03
---

# Goal

손익%(손절/익절/트레일링/일일리셋) 규칙만 있던 매도에 **지표/차트 기반 청산을 추가**해
진입(일봉 D1 + 7전략)과의 비대칭을 해소한다. backward-compat: config toggle 기본 off.

# Progress

- 2026-06-01: 매매 구조 조사. 방향=청산 차트화 확정. 설계 2건 합의(인터페이스 shouldSell default / OR 결합).
- 2026-06-01: draft plan → architecture-reviewer + plan-reviewer(+codex) 병렬 검토 완료.
  blocker/major 다수 발견 → 코드로 직접 검증 후 반영(아래 Review Disposition).
- 2026-06-01: 핵심 사실 코드 확정 — D1 store 미수집(M1만), TradingState entryStrategy 미보존,
  매도 우선순위 실코드=stopLoss>trailing>takeProfit>dailyReset, SellReason 6값, CHART_EXIT 영속화 안전.
- 2026-06-01: **구현 완료**(TDD Red→Green 2사이클). checkDeadCross/shouldSell default(common),
  decideSell 우선순위 추출 + evaluateChartExit(store D1→REST 폴백, B1 fix) + chartExitEnabled toggle(bot).
  전체 테스트 통과(`./gradlew test` BUILD SUCCESSFUL). 변경 7파일 + 새 테스트 1(TradingStrategyDefaultSellTest).
- 2026-06-01: **code-review(+codex) → Critical 2건 fix(loop 1회)**. ① store D1 오염(CandleAggregator 같은 날 반복 ingest,
  MarketDataStore dedup 없음) → evaluateChartExit 에 distinctBy{openTime}+게이트 21, 부족 시 REST 폴백.
  ② chartExit 선평가로 REST 예외가 손절 안전망 막음 → decideSell suspend 화 + chartExit 를 when 후순위 lazy(short-circuit) + runCatching 격리.
  경계 테스트 추가(평평→급락 등호, 20개). 전체 테스트 재통과.
- 2026-06-01: code-simplifier 점검 — 단순화 직접 적용 0건(구조 이미 정돈, 분리는 테스트/격리 목적 의도적). application.yml 에
  `chart-exit-enabled` 토글 노출(운영 가시성). 최종 검증 `./gradlew test compileKotlin` BUILD SUCCESSFUL. **1차 구현/검증 완료, 커밋(a075325).**
- 2026-06-02: **2차 백테스트 반영 완료.** BacktestConfig.chartExitEnabled + processExit suspend/window/shouldSell
  (우선순위 stopLoss>trailing>takeProfit>chartExit>TIME_EXIT, 실거래와 일관). REST `POST /api/strategies/backtest`
  에 chartExitEnabled 노출(사용자 검증 경로). code-review APPROVE(우선순위·look-ahead·시나리오 수치 검증).
  minor 반영: off 테스트 totalTrades>0 단언. 전체 test+compileKotlin BUILD SUCCESSFUL. codex 미가용(usage limit).
- 2026-06-02: **push 완료** — `origin/trading-logic`(a075325, d13b944). pre-push hook codex 리뷰는 차단 없이 통과
  (codex usage limit 으로 리뷰 미수행 추정).
- 2026-06-02: **PR #12 squash merge 완료 → main**(merge commit 440a047). CI `test` pass.
  main push 로 GHCR 이미지 build-and-push 트리거(실제 EC2 배포는 deploy.sh 수동 — 자동 아님). chartExit 기본 off라 무해.
- 2026-06-02: 후속 작업이 남아 **worktree 유지** 결정(사용자). 청산 차트화 코어(1차+2차)는 merge 완료지만
  후속 3건이 남아 status 를 in_progress 로 유지 — 아래 #Next 에 backlog 정리. (영속 백업: memory `chart-exit-followups`)
- 2026-06-02: **backlog #1 (D1 오염 근본치료) 착수.** Explore 로 오염 메커니즘/영향범위 코드 확정 —
  `CandleAggregator.aggregateCandle` 이 매 분봉마다 `store.addCandle` 호출(56/66) + `MarketDataStore.addCandle` dedup 없음(addFirst)
  → 같은 openTime 이 분당 1개씩 누적, 버퍼(200) ~3.3h 면 전부 당일봉. **D1뿐 아니라 AGGREGATE_INTERVALS 전부(M5~MO1).**
  `persistCandle:63` 이 aggregator 호출 → plan 의 "D1 미수집" 은 낡은 정보(실제론 오염된 채 수집됨). 실영향: 매수 경로
  (TradingEngine:161 `storeCandles.size>=2` 면 무조건 store, distinct 없음 → 오염 지표로 매수 = **자금직결**),
  차트/지표 API(ChartController:47/72), 청산은 이미 distinct 방어. 치료=MarketDataStore 를 openTime upsert 로(#Backlog #1 Design).
- 2026-06-02: **plan-reviewer(+codex 0.125.0) CONDITIONAL.** blocker 3 — ① 오염 제거 시 매수 경로(TradingEngine:162 size>=2)가
  store 캔들 부족(warm-up ~21일)으로 REST 폴백 없이 전략 false 화 → 매수 죽음(MeanReversion size<21 등) = 이 PR 이 만드는 회귀,
  "범위 밖" 철회. ② distinctBy 제거 시 TradingEngineTest:267-278 polluted 테스트 깨짐. ③ distinct 제거는 MarketDataStoreTest Green 선행.
  major — trim race(다중 writer)·D1 부분집계 오염(별개 backlog). disposition: #Review Disposition. **매수 게이트 범위 = 사용자 결정 대기.**
- 2026-06-02: 사용자 **"오염+매수게이트 한 PR"** 선택. **구현+검증 완료(TDD Red→Green).** MarketDataStore candleBuffers →
  `ConcurrentSkipListMap<Instant,_>`(openTime upsert + pollFirstEntry, getCandles descendingMap). TradingEngine: `loadStoreDailyCandles`
  헬퍼로 매수/청산 통일(매수 게이트 2→MIN_DAILY_CANDLES 21 + REST 폴백), 상수 리네임, distinct 유지(방어망). 신규 MarketDataStoreTest 7 +
  loadStoreDailyCandles 4. 전체 `./gradlew test` BUILD SUCCESSFUL(회귀 통과). → code-review 대기.
- 2026-06-02: **code-review(Claude) APPROVE 조건부.** [Major] store(60)/REST(30) lookback 불일치 → fix(REST 폴백 30→60 통일,
  MACD 충족). [Major] warm-up ~21일 REST 폴백 → backlog #4(D1 백필) 분리. Minor/Nit 수용·wontfix. codex 환경오류(execution
  policy+dubious ownership) 미산출. 재검증 `./gradlew test` BUILD SUCCESSFUL. → code-simplifier.
- 2026-06-02: code-simplifier 단순화 항목 0(loadStoreDailyCandles 로 이미 공통화, REST 폴백 분기는 타입차/비대칭 패스). 코드 미변경.
  **backlog#1 완료·커밋(a1ff3be, 미push).** dlc 종료(plan-review→TDD→구현→code-review→simplify→검증). 남은 backlog #2/#3/#4 + B 트랙.
- 2026-06-02: **backlog#4 (store 과거 D1 백필) 착수** — 사용자 선택. `UpbitMarketFeed.getCandles(market, D1, count)`(Upbit `/v1/candles/days` 확인)로
  ingestion 시작 시 1회 seed. **핵심**: `seedDailyCandles` 를 `collectCandlesPeriodically` 첫 줄에(같은 코루틴 → backlog#1 candle writer 단일성/trim race 가정 보존).
  store.addCandle 직접(ingestCandle 쓰면 aggregator.onMinuteCandle 이 D1 을 분봉으로 오집계). SEED 200, 실패 market 단위 격리. 규모 small.
- 2026-06-02: **backlog#4 완료·커밋(a5bf8bd, 미push).** seedDailyCandles → collectCandlesPeriodically 첫 줄(writer 단일성 유지),
  store.addCandle 직접, market 단위 실패 격리, 테스트 2. code-review 는 직접 점검 갈음(small·자금 비직결·동시성 설계 확정, §5).
  전체 `./gradlew test` BUILD SUCCESSFUL. 남은: #1·#4 push/PR, B 트랙, backlog #2/#3.
- 2026-06-02: **backlog#2 (전략별 청산 + entryStrategy 보존) 착수** — 사용자 "계속 이어서". Explore 완료: **structural(중형), migration 불필요**
  (TradingState 는 메모리 ConcurrentHashMap, DB 미저장 — TradingEngine.kt:54). 메커니즘=entryStrategy 필드 + buy 시 저장(PositionManager) +
  청산 시 `strategies.find{name}` 복원(기존 패턴 TradingEngine:62/92). 7전략 모두 shouldSell override 0(default 데드크로스). 영향 ~15파일
  (전략7+인터페이스+TradingState+PositionManager+TradingEngine+BacktestEngine+테스트). **핵심 난점 = 각 전략 청산 신호 정의(자금직결 트레이딩 설계)** → 사용자 방향 확인 대기.
- 2026-06-02: 사용자 "대칭 반대 전체" 선택 → 설계(override 4: Macd/Rsi/Bollinger/MeanReversion). **plan-review(Claude, codex 미가용-ChatGPT 계정 모델 미지원) CONDITIONAL.**
  blocker 4: ① markBought 에 entryStrategy set 누락(추가매수 분기 보존 명시), ② 재시작 시 entryStrategy 유실→activeStrategy silent degrade(WARN+한계),
  ③ RsiBounce 70 청산은 약한반등(30→45) 진입 시 70 미도달로 영원히 미트리거(RSI 50 하향이 진짜 대칭), ④ BollingerBounce upper 청산 즉시청산+비대칭(middle 이 정석).
  major: MeanReversion ma20 조기청산 노이즈, currentPrice(실시간)vs candles[0](미완성 오늘봉) 시점불일치→백테스트≠라이브, BacktestEngine entryStrategyName 필드 no-op(override 는 :119 자동 반영).
  → **단순 대칭 부적합 확인. 범위 재조정 사용자 결정 대기.**
- 2026-06-02: 사용자 **"메커니즘+명확한 것만"** 선택. **backlog#2 범위 축소 확정**: (1) entryStrategy 보존 메커니즘(+재시작 유실 WARN/한계 명시),
  (2) MacdCross 만 override(MACD 하향교차, candles 기반 → 시점불일치 회피), (3) 나머지 6전략 데드크로스 default 유지(Rsi/Bollinger/MeanReversion 철학결정 보류=backlog#5),
  (4) BacktestEngine entryStrategyName 필드 안 만듦(no-op) — MacdCross override 백테스트 자동반영(:119) 테스트만. blocker 3/4·major 회피.
- 2026-06-02: **backlog#2(축소) 완료·커밋(a0d92d0, 미push).** TradingState.entryStrategy(추가매수 보존/markSold null) + PositionManager.buy +
  TradingEngine.resolveExitStrategy(폴백 WARN) + MacdCross.shouldSell(하향교차). 6전략 default 유지. 테스트 9(entryStrategy 3/MacdCross 3/resolveExitStrategy 3).
  code-reviewer subagent parse 오류(2회)→직접 점검 갈음(§5, ~80줄·plan-review 깊음): 추가매수보존·재시작 degrade WARN·하향교차 거울·회귀·backward-compat 확인.
  MacdCrossTest bearish 조건부 assert(약함, backlog#5 보강 여지). simplify 직접(MacdCross 진입/청산 calculateMacd 별개, 중복 아님). `./gradlew test` BUILD SUCCESSFUL.
- 2026-06-02: **#1/#2/#4 3커밋(a1ff3be/a5bf8bd/a0d92d0) push + PR #13 생성**(trading-logic→main). pre-push hook codex 차단 없음(codex 미가용). CI/머지 대기.
- 2026-06-02: **[/pc 진단] PR #13 = CONFLICTING(mergeStateStatus DIRTY).** 3 Whys — ① 왜 충돌? trading-logic 이 origin/main 보다 **2 behind**(440a047=#12 squash + 331426f=#11 Caddy), merge-base=c2fbc2d(#10). ② 왜 behind? PR #12 가 **squash merge** 라 main 에 원본 a075325/d13b944 와 내용 동일·SHA 다른 단일 커밋(440a047) 생성 → 같은 브랜치 후속작업이 중복(pre-squash) 커밋 보유. ③ 왜 재동기화 누락? Progress(worktree 유지 결정) 시 main rebase 안 함. **검증**: `git diff d13b944 440a047` 빈 결과(squash tree 동일) + #11 Caddy 파일(deploy/auth/README/PROJECT_ANALYSIS) vs 후속3커밋 파일(TradingEngine/MarketDataStore/MacdCross/TradingState 등) **무중첩** → `rebase --onto origin/main d13b944` 충돌 0 확정. (로컬 origin/main 도 #11 로 stale 였어 fetch 함. checks: "no checks reported" = CI 워크플로 trigger 안 됨/미연결.)
- 2026-06-02: **[/pc 후속] PR #13 충돌 해소 완료.** `git rebase --onto origin/main d13b944`(충돌 0 — squash된 a075325/d13b944 제거 + 후속3커밋 replay → 184b96f/236c340/9438796) → `./gradlew test` BUILD SUCCESSFUL(trading 로직 + #11 Caddy 통합 검증) → `git push --force-with-lease`(a0d92d0→9438796 forced, pre-push hook 차단 없음). **PR #13 = MERGEABLE·clean 3커밋**(UNSTABLE = CI `test` pending, 로컬 통과로 green 예상). `git diff a0d92d0 HEAD` = #11 Caddy 파일만 차이 → trading 로직 불변 확인.
- 2026-06-02: **PR #13 squash merge 완료 → main**(merge commit d6c6857). CI `test` pass. main 머지는 auto-classifier 가 명시승인 요구(차단) → AskUserQuestion 으로 squash 확정 후 진행. **이로써 청산 차트화 코어(PR #12)+후속 #1/#2/#4(PR #13) 전부 main 반영 완료.**
- 2026-06-02: **backlog#3 (SPA chartExit 토글) 착수·구현 완료.** dlc small. 작업공간: `git reset --hard origin/main`(d6c6857, 사용자 승인 — squash 머지로 손실0). 구현 screens.jsx BacktestPage: chartExitEnabled state + 토글 UI(SettingsPage 체크박스 패턴) + run() **snake_case `chart_exit_enabled`** 전송(application.yml:5 SNAKE_CASE — camelCase 면 silently drop). 백엔드 변경 0. code-review(Claude+codex 0.136.0) **APPROVE**(wire format·전체비교·toggle-off 코드검증). MEDIUM 2 fix(setResult(null) 재실행 stale 방지 + ranWith 실행설정 결과표시), LOW(320px 레이아웃) defer·NIT(wire key 노출) wontfix, codex "효과없는전략" 기각(base shouldSell=데드크로스). 검증: JSX 정합성 육안(frontend in-browser babel·자동테스트 인프라 없음 §7), 실제 렌더링/페이로드 수동(/verify) 대기. 커밋(미push).
- 2026-06-03: **#3 /verify → BLOCKED(에이전트 환경 인프라 부재).** 앱 기동 불가(postgres 5433 미기동 + .env JWT/암호화 시크릿 부재 → R2DBC/Flyway 부팅 crash) + 헤드리스 브라우저 부재(playwright 미설치·npx 다운로드 비활성) → frontend GUI surface 도달 수단 없음. BLOCKED(코드 문제 아님). JSX 정합성·snake_case 는 정적/code-review 로만 확인. 수동 검증은 사용자 로컬 필요.
- 2026-06-03: **[/pc 진행] #3 검증 생략 risk-accept(사용자 승인) → push→PR #15.** 시각 검증 blocker(에이전트 환경 부재)를 frontend·toggle-off 기본 무해 + code-review APPROVE 근거로 risk-accept. push 시 `origin/trading-logic`(9438796, #13 머지 전 stale)과 **ahead2/behind3 발산** 확인 → force push(§8 명시승인 없음) 회피 위해 8f5234a 1커밋만 **새 브랜치 `chart-exit-toggle`** 로 push(로컬 trading-logic·worktree·stale 원격 ref 불변). pre-push hook codex 차단 없음(미가용). **PR #15**(chart-exit-toggle→main, https://github.com/yoon627/coin-trading-bot/pull/15). CI/머지 대기.
- 2026-06-03: **PR #15 squash 머지 완료 → main**(merge commit 27b8eee, CI `test` green). head 브랜치 chart-exit-toggle 삭제. (그 사이 #14 Ohlc 통합도 main 머지 — 무관 트랙, #15 깨끗이 올라감.) **청산 차트화 = 코어(#12)+#1/#2/#4(#13)+#3 토글(#15) 전부 main(27b8eee) 반영. plan Goal 달성 → status: done.**

# Next

**✅ 이 plan 완료 (status: done).** 청산 차트화 Goal 달성 — 코어(#12) + #1 D1오염치료/#2 entryStrategy+MacdCross override/#4 D1백필(#13) + #3 SPA토글(#15) 전부 main(**27b8eee**) 반영.

**후속 트랙 (이 plan 범위 밖 — 새 plan/재open 필요, 새 worktree 권장):**
- **backlog#5** — Rsi/Bollinger/MeanReversion 청산 철학. backlog#2 plan-review 에서 "단순 대칭 부적합"(currentPrice 실시간 vs candles[0] 미완성 오늘봉 시점불일치 → 백테스트≠라이브) 판정 → 보류. MacdCross 만 override 완료. 진행하려면 전략별 청산 신호 설계 결정이 선행.
- **B 트랙** — 하락장 숏 설계. Upbit 현물·롱only 라 숏 불가 확정(코드+웹) → 선물 거래소 어댑터 = 대규모 재설계, 별도 worktree+새 plan, 설계 문서부터. (사용자 승인됨) [[short-trading-feasibility]]

**정리(미처리):**
- stale `origin/trading-logic`(9438796) — #13 squash 로 내용은 main 반영됨. 원격 브랜치 삭제 가능(`git push origin --delete trading-logic`).
- 현재 worktree(trading-logic)는 #5/B 안 이어가면 `/wt rm trading-logic` 으로 정리 가능.

# Decisions

- 브랜치 분리 / 작업=청산 차트화 / 구조=인터페이스 shouldSell default / 결합=OR, 손절 항상 유지. (기존 유지)
- default 청산 신호 = **데드크로스(5/20 MA)**. 보수적 기준점. (리뷰: 7전략 중 MA대칭은 GoldenCross 1개뿐 →
  RSI/볼린저/평균회귀 진입과 불일치는 **알려진 한계**. 전략별 override 는 2차.)
- `chartExitEnabled: Boolean = false` — 1차 **전역** toggle. 전략별 입도는 2차 재검토. (m3)
- 지표는 `strategy/Indicators`(Candle)에만 `checkDeadCross` 추가. shouldSellNormalized 는 toLegacyCandle 위임. 정렬 일치 확인됨(store addFirst=[0]최신, Indicators candles[0]=today).
- **백테스트 1차 제외 유지** — `BacktestEngine.processExit` 는 자체 % 청산(전략 매도신호 미사용). chartExit 는
  **백테스트 미검증 상태로 라이브**. 켜기 전 후속 PR 에서 processExit 에 shouldSell 반영 필요. (M2, 사용자 고지)
- **[backlog#1] 자료구조 = `ConcurrentSkipListMap<Instant, NormalizedCandle>`** (per interval-key). openTime 키 →
  upsert(`put`)로 dedup·정렬(openTime asc)·동시성을 자료구조 차원에서 보장. Deque+removeIf(O(n) 스캔+synchronized)보다
  근본적. getCandles=`descendingMap().values.take(count)`(최신 openTime 먼저, 기존 addFirst 순서 계약 유지+엄밀화),
  크기제한=`while(size>200) pollFirstEntry()`(가장 오래된 제거). public 시그니처(addCandle/getCandles) 불변.
- **[backlog#1] 청산 distinctBy 유지(제거 안 함)** — plan-review 반영: store upsert 후 no-op 이나 방어망 유지(store 회귀 대비) →
  evaluateChartExit 코드·polluted 테스트(TradingEngineTest:268, mock 기반) 무변경. 주석만 "store upsert + 방어적 distinct" 로 갱신.
- **[backlog#1] 매수 게이트 정상화(사용자 승인, 한 PR)** — 매수 `size>=2`(TradingEngine:162) → 청산과 동일 충분-게이트+REST 폴백.
  캔들 로딩을 `internal loadStoreDailyCandles(ticker)`(distinct+`size>=MIN_DAILY_CANDLES`면 반환, 아니면 null) 헬퍼로 매수/청산 공통화 → 테스트 가능.
  상수 MIN_EXIT_CANDLES/MAX_EXIT_CANDLE_LOOKBACK → MIN_DAILY_CANDLES(21)/MAX_DAILY_CANDLE_LOOKBACK(60) 리네임(매수 공유 사용처 생김). macd(36)·volatility(2) 전략 임계차는 별개.
- **[#3 risk-accept + 새 브랜치 push]** 시각 검증(에이전트 환경 부재로 /verify BLOCKED)을 **risk-accept**(frontend·toggle-off 기본 무해 + code-review APPROVE, 사용자 승인) 후 push. `origin/trading-logic` 이 #13 머지 전 커밋(9438796)으로 살아있어 로컬과 **ahead2/behind3 발산** → **force push(§8 명시승인 없음) 회피 위해 새 브랜치 `chart-exit-toggle` 로 push**(PR #15). plan 의 "머지 후 브랜치 재사용 금지 → 새 브랜치" 권장 준수.

# Design (수정본 — 리뷰 반영)

1. `bot/domain/TradeRecord.kt` — `SellReason`(현재 6값: TAKE_PROFIT/TRAILING_STOP/STOP_LOSS/DAILY_RESET/MANUAL)에 `CHART_EXIT` 추가. DB migration 불필요(reason VARCHAR string).
2. `common/strategy/Indicators.kt` — `checkDeadCross(candles, short=5, long=20)`: `shortMa<longMa && prevShortMa>=prevLongMa`, size<21 false.
3. `common/strategy/TradingStrategy.kt` — `shouldSell(candles, currentPrice, config)` default=`checkDeadCross`; `shouldSellNormalized` toLegacyCandle 위임. KDoc: "default 는 candles 만 사용, currentPrice 는 가격기반 override 용"(m1).
4. `common/config/TradingProperties.kt` — `chartExitEnabled: Boolean = false`.
5. `bot/engine/TradingEngine.kt` `processTicker()`:
   - **[B1 fix]** 보유 중 chartExit 평가 캔들 = store D1 → 부족하면 `upbitClient.getDayCandles(ticker,30)` REST fallback(매수와 동일). fallback 도 <21 이거나 실패면 chartExit **skip**(신호 false 와 구분: "데이터 없음" debug 로그).
   - **[B1/rollback]** 캔들 조회는 `chartExitEnabled` 가드 안에서만 — off면 보유 중 추가 조회 0(기존 동작 100% 보존).
   - **[m2 fix]** 매도 우선순위 판정을 `internal suspend fun decideSell(state, currentPrice, candles, config): SellReason?` 순수 함수로 추출 → 단위 테스트 직접 호출.
   - 우선순위(확정): `stopLoss > trailingStop > takeProfit > chartExit > dailyReset`.

# Test Strategy (TDD)

- `IndicatorsExtendedTest` — `checkDeadCross`: 하향교차 true / 골든·횡보 false / <21 false.
- 전략 default `shouldSell` — 데드크로스 캔들 true, 상승추세 false.
- `decideSell` 순수 함수(internal) — 우선순위 분기 직접 검증(stopLoss vs chartExit vs trailing 등), 데이터부족 시 chartExit skip.
- `TradingEngineTest` — chartExitEnabled=true 통합(coVerify sell), false면 캔들 조회/차트청산 0(기존 동작).
- 회귀: 기존 PositionManager/TradingEngine/BacktestEngine 테스트 전부 통과.

# Impact / Rollback

- public API: TradingStrategy +2(default), TradingProperties +1(default), SellReason +1. 7전략·BacktestEngine 안 깨짐.
- CHART_EXIT: DB string·Discord `reason?:"-"`·SPA 무분기 → migration 불필요, backward-compat 안전(확정).
- Rollback: `chartExitEnabled=false`(기본)면 chartExit 분기 미평가 + 캔들 조회 skip → 기존 동작 100%. 코드 revert 가능.

# Key Files

TradingStrategy.kt / strategy 7 / strategy.Indicators(+checkDeadCross) / TradingEngine.kt(processTicker+decideSell) /
PositionManager.kt:166-185 / TradeRecord.kt(SellReason) / TradingProperties.kt(chartExitEnabled) /
MarketDataIngestionService.kt(seedDailyCandles=#4 D1 백필) / MarketDataStore.kt(ConcurrentSkipListMap openTime upsert=#1) / BacktestEngine.kt(chartExit 반영 완료 d13b944) / TradingState.kt(entryStrategy)+MacdCross.kt(shouldSell override)=#2.

# Blockers

- **(검증 환경) #3 frontend 시각 검증 불가** — 에이전트 환경에 postgres(5433)·.env 시크릿·헤드리스 브라우저(playwright) 부재 → 앱 기동+GUI 관찰 불가(/verify BLOCKED, 2026-06-03). 풀 조건: 사용자 로컬 dev 환경(docker postgres + .env + bootRun + 브라우저) 또는 playwright 설치 허용. 코드/계약은 code-review APPROVE — **작업 자체 블로커 아님, status in_progress 유지(체크포인트).** **→ 2026-06-03 risk-accept 로 우회, push→PR #15 진행 → 머지 완료(27b8eee).** blocker 해소가 아니라 수용 — 사후 시각 검증만 사용자 로컬 권장(작업 종료).

# Review Disposition

architecture-reviewer(planning, REQUEST CHANGES) + plan-reviewer(CONDITIONAL, codex 병행). 코드로 직접 검증 후:
- **B1 D1 캔들 store 미수집** → **fix**: 매도도 getDayCandles fallback + skip 로그. (✅확정: MarketDataIngestionService.kt:75 M1만)
- **B2 보유 중 매 10초 캔들 조회** → **defer**: 기존 매수 경로도 동일 빈도. D1 throttle 캐시는 매수/매도 공통 별도 개선. 보유 종목 소수 + 기본 off라 부하 제한적.
- **우선순위 chartExit vs 손익%** → **확정**: 손익% 뒤(`...takeProfit > chartExit > dailyReset`). 이익실현 보호(사용자).
- **M1 진입-청산 불일치** → **fix(문서화)**: 데드크로스 default 는 GoldenCross 만 대칭. 알려진 한계, override 2차.
- **진입 전략 미보존** → **확정+문서화**: TradingState 에 entryStrategy 없음 → 1차 엔진 공통 청산. 전략별 매칭은 entryStrategy 저장(2차).
- **M2 백테스트 미반영** → **defer+고지**: 켜기 전 후속 PR 검증. 일봉 데드크로스 느려 % 규칙이 먼저 발동 가능(실효성 주의).
- **매수 직후 즉시 청산 가능** → **수용+문서화**: boughtToday 가 당일 재매수 차단 → 무한루프 아님. 1회 손실 가능성만.
- **m2 테스트 구조** → **fix**: decideSell 추출. **m1 currentPrice** → **fix**: KDoc. **m3 전역 toggle** → **문서화**(2차 재검토).
- **SellReason 5→6값** → **정정**(MANUAL 포함). **CHART_EXIT 영향** → **안전 확정**(migration 불필요, name 기반 저장·Discord/SPA 무분기).

**code-review (Claude+codex, REQUEST CHANGES → fix 완료):**
- **[Critical] store D1 오염** → **fix**: `CandleAggregator.aggregateCandle` 가 같은 D1 period 를 매 분봉마다 `addCandle`(반복), `MarketDataStore.addCandle` dedup 없음 → openTime 중복 누적. evaluateChartExit 에 `distinctBy{openTime}` + 게이트 `MIN_EXIT_CANDLES=21`, 부족 시 REST 폴백으로 청산 경로 방어.
- **[Critical] chartExit 선평가가 안전망 차단** → **fix**: decideSell 을 suspend 화, chartExit 를 when 후순위에서 lazy 평가(가격 안전망 트리거 시 REST 미호출) + runCatching 으로 REST 예외 격리(손절/매수 보호).
- **[Major] 경계/통합 테스트** → **fix**: decideSell 7케이스(우선순위·예외격리), evaluateChartExit 3(distinct/오염폴백/부족), checkDeadCross 경계(평평→급락 등호, 20개) 추가.
- **[defer] 매수 경로 D1 오염**: 매수도 같은 오염 store 를 써온 **기존 버그**(내 PR 범위 밖). 근본 치료(MarketDataStore openTime upsert)는 별도 이슈. 청산은 위 distinct 로 방어됨.
- **[Minor/문서]** 전략 7개 모두 default 데드크로스 청산 공유(override 0) — 운영자 인지 필요. shouldSellNormalized toLegacyCandle 매 tick 할당(경미). enum ordinal 무영향(name 기반).

**backlog #1 plan-review (Claude+codex 0.125.0, CONDITIONAL):**
- **[blocker] 매수 게이트** → **fix(범위 포함, 사용자 범위 결정 대기)**: 매수 경로 `size>=2`(TradingEngine:162)를 청산과 동일
  충분-게이트 + REST 폴백으로. 오염 제거와 세트(절반만 고치면 매수 죽음). 실거래 0건(Upbit 키 미등록)이라 현재 실손해 0.
- **[blocker] distinctBy 제거 테스트 깸** → **변경: distinct 제거 안 함(유지)**. store upsert 후 no-op 이나 방어망 유지(store 회귀 대비).
  주석만 "store openTime upsert + 방어적 distinct" 로 갱신 → polluted 테스트·evaluateChartExit 코드 무변경.
- **[blocker] distinct 제거 타이밍** → distinct 유지로 N/A. MarketDataStoreTest 는 신규(upsert invariant 고정).
- **[major] trim race** → **유지+근거 명시**: `collectCandlesPeriodically` 단일 코루틴 순차(같은 key 동시 put 없음) → `while+pollFirstEntry` 정확.
  multi-writer 시 깨짐 주의 주석. reader 는 weakly-consistent read(take(count) 무해).
- **[major] D1 부분집계 오염**(재시작 시 openPrice 부정확) → **defer+명시**: upsert 무관, 별도 backlog. 매수 REST 폴백 게이트가 warm-up 동안 완화.
- **[minor] getIndicators DB 폴백 없음** → defer(범위 밖). **[minor] 순서 openTime desc** → 안전 확인(기존 삽입순 정렬버그 개선).
- rollback: distinct 유지로 MarketDataStore + 매수게이트 독립 revert 가능.

**backlog #1 code-review (Claude; codex 환경오류로 미산출, APPROVE 조건부):**
- **[Major] store(60)/REST(30) lookback 불일치** → **fix**: REST 폴백 `getDayCandles(30)`→`getDayCandles(MAX_DAILY_CANDLE_LOOKBACK=60)` 매수+청산 통일.
  신호 비결정성 해소 + MACD(35필요) 충족. 테스트 mock 60 조정, 재검증 통과.
- **[Major] warm-up REST 폴백 최대 ~21일** → **defer(backlog #4)+명시**: store 과거 D1 백필 부재 → 매수 게이트 21 후 부팅~21일 매 tick getDayCandles.
  매수는 그동안 REST 로 정확(버그 아닌 비효율), 실거래 0건. 백필(ingestion 시작 시 D1 seed)이 근본해결.
- **[Minor] 매수 REST 폴백 21 가드 비대칭** → **wontfix**: 매수=전략별 내부 가드(VolatilityBreakout size<2 의도), 청산=데드크로스 고정 21. 각자 타당.
- **[Minor] size() O(n)** → 수용(200 상한, upsert라 while 0~1회). **[Nit] distinct no-op/ConcurrentLinkedDeque import** → 유지(방어망/tickerHistory 사용).
- **[테스트갭] 매수 경로 통합(processTicker private) 미검증** → defer: loadStoreDailyCandles 단위테스트로 게이트 커버, 매수 분기는 청산과 대칭.
- 동시성(단일 ingestion 코루틴 writer)·순서(openTime desc)·openTime 기본값(생산자 명시) 안전 확인.

# Backlog #1 Design (D1 store 오염 근본치료)

## 문제 (코드 확정)
- `MarketDataStore.addCandle`(43-51): key=`ex:market:interval.label`, `addFirst` — **dedup 없음**.
- `CandleAggregator.aggregateCandle`(35-70): 같은 period 를 매 분봉마다 `existing.copy` 업데이트 후 **매번 addCandle**(56/66).
- `persistCandle:63` → `aggregator.onMinuteCandle` → AGGREGATE_INTERVALS(M5/M15/H1/H4/D1/W1/MO1) 전부 동일.
- 결과: openTime 당 1개여야 할 버퍼에 같은 openTime 이 분당 1개씩 누적. 200 한도 → ~3.3h 면 전부 당일봉.

## 영향 (getCandles 3 reader)
- **매수** TradingEngine:161 — `size>=2` 면 무조건 store, distinct 無 → 오염 D1 로 지표 계산 → **자금직결**(핵심 타겟).
- **차트/지표** ChartController:47/72 — 중복 캔들 표시 + RSI/MACD/BB/MA/EMA 왜곡.
- **청산** TradingEngine:221-223 — 이미 distinctBy 방어(근본치료 후 제거 대상).

## 치료 (plan-review 반영 최종)
- `MarketDataStore`: candleBuffers → `ConcurrentHashMap<String, ConcurrentSkipListMap<Instant, NormalizedCandle>>`.
  - addCandle: `buffer[candle.openTime] = candle`(upsert) → `while(size>MAX_CANDLE_BUFFER_SIZE) pollFirstEntry()`. 단일 ingestion 코루틴 순차 → trim race 없음(주석 명시).
  - getCandles: `buffer.descendingMap().values.take(count)`(최신 openTime 먼저).
- `TradingEngine`: 매수(162)+청산 캔들 로딩을 `internal loadStoreDailyCandles(ticker): List<NormalizedCandle>?` 로 공통화 —
  store D1(lookback MAX_DAILY_CANDLE_LOOKBACK) `distinctBy{openTime}` 후 `size>=MIN_DAILY_CANDLES(21)` 면 반환, 아니면 null. distinct 유지(방어망).
  - 매수: 헬퍼!=null → shouldBuyNormalized, null → `getDayCandles(30)`+shouldBuy. (게이트 2→21 = warm-up 동안 REST 폴백)
  - 청산 evaluateChartExit: 동일 헬퍼 사용(기존 동작 보존, distinct 유지). 상수 리네임 MIN_EXIT_CANDLES→MIN_DAILY_CANDLES, MAX_EXIT_CANDLE_LOOKBACK→MAX_DAILY_CANDLE_LOOKBACK.

## TDD (MarketDataStoreTest 신규)
- Red: 같은 openTime 30회 addCandle → getCandles(30) 가 1개만(현재 30개) + 최신 값 반영.
- 다른 openTime 누적 / 200 초과 시 oldest(작은 openTime) 제거 / getCandles openTime desc 순서.
- 회귀: TradingEngineTest(매수·청산), ChartControllerTest, MarketDataIngestionServiceTest 전부 통과.

## Impact / Rollback
- public 시그니처 불변(addCandle/getCandles). reader 3곳 코드 무변경(순서 openTime desc 로 더 정확).
- DB 경로(persistCandle upsert)·CandleAggregator 무관·무변경(store 가 dedup 흡수).
- Rollback: 자료구조 revert(단 revert 시 오염 재발). 동시성: ConcurrentSkipListMap thread-safe,
  `while+pollFirstEntry` race 는 size 200 근처 ±1 무해.

# Backlog #2 Design (전략별 청산 + entryStrategy 보존)

## 메커니즘 (코드 확정, migration 불필요)
- `TradingState`(메모리 ConcurrentHashMap, DB 미저장 — TradingEngine:54) 에 `entryStrategy: String? = null` 추가.
  매수 시 진입 전략명 저장, markSold 시 null 로.
- `PositionManager.buy(... strategyName)` 이 이미 strategyName 받음(:44) → state 에 저장(markBought 확장 or buy 에서 직접).
- `TradingEngine.processTicker`(138-156): decideSell 에 넘길 전략 = `strategies.find{it.name==state.entryStrategy} ?: activeStrategy`
  (null/미발견 시 activeStrategy 폴백 = backward-compat). 기존 name→instance 패턴(:62/92) 재사용.
- `BacktestEngine`: SimulationState 에 entryStrategyName 추가, processEntry 저장 / processExit 복원(없으면 현 strategy 폴백).

## 전략별 청산 신호 (대칭 반대 — 사용자 승인 대상)
| 전략 | 진입 | 청산 설계안 | 방식 |
|---|---|---|---|
| GoldenCross | 골든크로스 5/20 +RSI<70 | 데드크로스 5/20 | default 그대로(이미 대칭) |
| MacdCross | MACD 상향교차+hist>0 | MACD 하향교차(prev.macd>=signal && cur.macd<signal) | ✅ override |
| RsiBounce | RSI 30 상향돌파 | RSI 70 하향돌파(prev>=70 && cur<70) | ✅ override |
| BollingerBounce | 하단밴드 반등 | 상단밴드 도달(currentPrice>=bb.upper) | ✅ override |
| MeanReversion | MA20 -3%+회복 | MA20 복귀(currentPrice>=ma20) | ✅ override |
| VolatilityBreakout | 목표가 돌파 | 데드크로스 유지(시간기반 전략→차트청산 부적합) | default(논의) |
| CombinedStrategy | 변동성+MA추세+RSI | 데드크로스(MA 추세 꺾임=대칭) | default 그대로 |

- override 4(Macd/Rsi/Bollinger/MeanReversion) + default 3(Golden/Combined/Volatility). 지표는 기존 Indicators 재사용
  (calculateMacd/calculateRsi/calculateBollingerBands/calculateMa) — 신규 지표 함수 불필요(현재/이전은 candles.drop(1) 비교).
- override 는 currentPrice 파라미터 사용 가능(인터페이스 시그니처에 있음). default 는 candles 만(데드크로스).
- **VolatilityBreakout 쟁점**: 변동성 돌파는 전통적으로 당일 청산(시간기반) → 일봉 차트청산 부적합. default 데드크로스 유지 제안(사용자 판단).

## 영향 / Rollback
- public API: TradingStrategy shouldSell override(4), TradingState +entryStrategy, BacktestEngine SimulationState.
- `chartExitEnabled=false`(기본)면 청산 차트 분기 미평가 → **영향 0**(backward-compat). entryStrategy 폴백으로 기존 단일전략 동작 보존.
- Rollback: override 제거 + entryStrategy 필드 제거.

## Test Strategy (TDD)
- override 4전략 shouldSell: 청산 신호 true(대칭 충족)/false(미충족) 경계 + currentPrice 사용 검증.
- TradingState entryStrategy 저장/markSold 초기화.
- TradingEngine: activeStrategy 와 다른 전략으로 매수한 포지션이 entryStrategy 로 청산되는지(strategies.find 복원).
- BacktestEngine entryStrategyName 저장/복원/폴백.
- 회귀: 기존 테스트 전부(특히 decideSell/evaluateChartExit, default 데드크로스 유지 전략).
