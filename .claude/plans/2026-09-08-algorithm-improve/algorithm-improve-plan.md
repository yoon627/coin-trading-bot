---
title: algorithm-improve — combined 전략·09:00 전량매도 재검토 + 트레일링 폭 240분봉 사전고정 판정 + 경계 재매수 stale-window 가드
status: in_progress
started: 2026-09-08
updated: 2026-09-08
---

# Goal

사용자 요청: "combined 전략 알고리즘 개선, 09:00 무조건 전량매도 규칙 재검토, 1년 데이터·실매매 내역에서 개선점 확인".

선행 조사([[hold-limit-policy-2026-09]]·[[exit-resolution-verdict-2026-09]]·[[trailing-arm-finding-2026-09]])가 이미 답한 것은
답을 재인용하고, **아직 열려 있는 것 두 가지**만 이 작업이 한다.

1. **#184 — 트레일링 폭 재판정(240분봉, 사전고정).** 승격값 1.5 는 격자 하한이고 D1 에서는 1.0 쪽이 단조로 좋았다.
   그 단조성이 라이브 의미론 계기에서도 남는지 판정한다.
2. **09:00 경계 재매수 stale-window 가드.** 경계 직후 매수 판정이 어제 일봉 window 로 이뤄져 방금 판 포지션을
   같은 가격에 되사는 경로가 코드에 열려 있다(#128 의 SOL 0.0h 사례). 백테는 이 churn 을 모델하지 않는다.

라이브 파라미터(env)는 바꾸지 않는다. 실매매 DB 조회는 세션 분류기가 SSH 를 막아 사용자가 직접 돌릴 SQL 로 넘긴다.

# Progress

- 2026-09-08 — worktree `algorithm-improve` 에서 착수. wiki 6페이지·엔진·전략·백테 하네스 코드 읽음.
- 2026-09-08 — **경계 stale-window 메커니즘 코드로 확정**: `MarketDataIngestionService` 는 M1 을 60초마다 폴링하고
  `CandleAggregator` 가 그 M1 로 D1 을 만든다. 09:00 직후 새 날의 첫 M1 이 오기까지(≤60초+spacing) store 의
  최신 D1 은 **어제 봉**이다. 엔진 tick 은 10초라 `DAILY_RESET` 매도 다음 tick 의 `runSwing` 매수 판정이
  `calculateTargetPrice(어제.open + 그제 range×k)` 로 돈다 — 어제 매수를 만든 바로 그 신호가 그대로 참이라 즉시 재매수.
  `LiveSemanticsArm` 은 당일 부분봉을 dayOpen 에서 시작하므로 이 churn 을 재지 않는다.

# Next

1. plan 커밋(사전고정) → `TrailingWidthIntradayTest` 작성·실행 → 결과 그대로 기록.
2. 경계 가드: TDD(재현 테스트 Red → 가드 → Green).
3. wiki 갱신(`trailing-arm-finding` 한계 절·`trading-engine-loop`/`exit-gates` 해당 절) + 검증 3종.
4. 실매매 SQL 은 사용자에게 `!` 실행으로 넘긴다.

# Decisions

## 1) 09:00 전량매도 규칙은 이 작업에서 다시 재지 않는다

이미 세 번 쟀다 — 13정책×7창(D1), 부분/조건부/연장(240분봉, 단조로 "덜 팔수록 나쁘다"), 청산 시각(240분봉, 어느 시각도
왕복수수료를 넘는 우위 없음). 규칙이 "이상해 보이는" 것과 "측정에서 지는" 것은 다르고, 지금 근거는 후자가 아니다.
단 그 정당성은 **익절 5% 와 짝**이다(한쪽만 바꾸면 안 된다) — [[exit-resolution-verdict-2026-09]] §7.

## 2) 트레일링 폭 판정은 사전고정이다 — 아래 `# Acceptance` 를 결과 보기 전에 커밋한다

가설은 D1 yearly 선택창 스캔(#184)에서 나왔다. 그래서 **yearly 는 주 판정에서 뺀다**(가설 생성 데이터).
`bear` 는 yearly 에 포함되므로 함께 뺀다. 주 판정 창은 **10개** — `BULL`·`P2024H2`·`P2025H1` + 7국면(2020-01~2023-11).
yearly 는 진단 표기만.

## 3) 경계 가드는 "오늘 D1 이 있을 때만 매수 판정" 이다

REST 폴백으로 바꾸지 않는다 — 폴백은 tick 마다 REST 를 치고, 09:00 직후 Upbit 일봉도 첫 체결 전엔 어제 봉일 수 있다.
오늘 봉이 없으면 그 tick 은 매수 평가를 건너뛴다(≤60초 뒤 M1 이 채운다). 청산 판정은 건드리지 않는다.

# Key Files

- `bot/src/test/kotlin/com/trading/bot/engine/TrailingWidthIntradayTest.kt` — 사전고정 판정(신규)
- `bot/src/test/kotlin/com/trading/bot/engine/LiveSemanticsArm.kt` — 계기(기존)
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — `runSwing` 매수 경로 가드
- `bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt` · `stream/CandleAggregator.kt` — stale 원인(무변경)

# Blockers

- 실매매 DB(Vultr postgres) 조회 — 세션 auto 모드 분류기가 SSH 실행을 차단. SQL 을 준비해 사용자 실행으로 넘긴다.

# Acceptance

**아래 1~5 는 결과를 보기 전에 커밋하는 사전고정이다. 실행 후 문구를 고치지 않는다.**

1. 계기 = `LiveSemanticsArm`(진입·청산 라이브 의미론, 240분봉). 기준 = 현행 라이브 `currentLivePoint()`(트레일 1.5 / arm 0).
2. 후보 = 트레일링 **0.75 · 1.00 · 1.25**(arm 0, 나머지 축 불변). 참고 행 = 구 라이브 2.0/arm3(판정 대상 아님).
3. 주 통계량 = 10창(§Decisions 2) pooled 격차 `G = Σ(후보) − Σ(기준)`, 청산 달력일 블록 부트스트랩(`DateBlockBootstrap`, B=20,000, seed 고정).
4. 발견 선언 = `P(G ≤ 0) ≤ 0.0170`(후보 3개 Šidák) **그리고** 5% 하한 > 0. 둘 다 만족해야 한다.
   여러 후보가 통과하면 **P 가 가장 작은 것이 아니라 격차/거래 가 가장 큰 것**을 후보로 보고한다.
5. 결과가 어느 쪽이든 그대로 보고한다. 창별·yearly 수치는 진단 표기이며 판정에 쓰지 않는다.
   **240분봉은 조인 트레일링에 유리한 방향으로 편향**된다([[exit-resolution-verdict-2026-09]] §4) — 통과해도 "승격"이 아니라
   "사람 승인 대상 후보" 이며 보고에 그 편향을 병기한다.
6. 경계 가드: 재현 테스트가 가드 없이 Red(어제 D1 만 있는 store 에서 매수 발생), 가드 후 Green(오늘 D1 없으면 매수 안 함, 있으면 기존과 동일).
7. `./gradlew build` 통과(실행/skip 건수 기록) + wiki 검증 3종 통과 + `TradingProperties`·`deploy/` diff 0.

# Deferred

- ⏳ 실매매 내역 분석(사유별 손익·재매수 공백·0h churn 빈도) — SQL 준비, 사용자 실행 대기. (높음)
