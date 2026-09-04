---
title: fixture-regimes — 국면 fixture 2개 추가로 시간 독립 holdout 을 1개에서 3개로
status: done
started: 2026-09-04
updated: 2026-09-04
---

# Goal

[[parameter-search-2026-09]] 의 결론을 막고 있는 것은 그리드 크기가 아니라 **표본**이다 — 국면이 사실상 1개(하락)이고
시간 독립 holdout 이 `bull` 하나뿐이라, 효과가 있어도 "이 구간에서만"과 구분되지 않는다.
시점 중립 선정 규칙(`scripts/collect_backtest_fixtures.py`)을 그대로 써서 **겹치지 않는 구간 2개**를 더 수집하고,
G4(국면 게이트)를 3개 holdout 으로 확장한 뒤 Stage A·D 를 재판정한다.

`strategy-search-yearly` 브랜치 tip(`1207206`)에서 분기했다 — 하네스가 있어야 재측정이 가능하다.

# Progress

- 2026-09-04 — 수집·확장·재판정 완료(`5947abb` 이후). p2024h2 는 **강한 상승**(XRP +366%·DOGE +132%·BTC +48%),
  p2025h1 은 **혼조·약세**(8종 중 6종 마이너스)로 성격이 갈렸다. G4a 를 3-holdout 각각 통과로 강화한 뒤 재실행:
  **Stage A 생존 3건이 그대로 유지**되고 out-of-sample 성적이 선택창(+4.92%p)보다 좋았다 —
  p2024h2 +10.3~11.9%p · p2025h1 +11.2~12.5%p · bull +6.5~7.0%p. Stage D 는 여전히 통과 0.
  사전고정 규칙(선택창 통계 vs null max-stat)은 그대로라 **판정은 "발견 없음" 유지** — 규칙을 결과 본 뒤 고치지 않는다.
- 2026-09-04 — worktree 생성(base `strategy-search-yearly@1207206`). `collect_backtest_fixtures.py` 의 `REGIMES` 에
  `p2024h2`(2024-06-10~2024-12-26) · `p2025h1`(2025-01-01~2025-07-19) 추가. 네 구간이 서로 겹치지 않음을 확인:
  bull 2023-11-23~2024-06-09 / p2024h2 / p2025h1 / bear 2026-01-31~2026-08-18, yearly 는 2025-09-03~2026-09-02.

# Next

없음 — `strategy-search-yearly`·`reset-policy` 와 함께 PR 하나로 머지되어 닫혔다.

후속(별도 작업): **holdout 기반 사전고정을 새로 쓰고 소액 전향 검증**. 이번 결과가 그 근거다 —
선택창보다 out-of-sample 이 좋게 나왔으므로 null 대조군도 선택창이 아니라 holdout 통계에 걸어야 한다.

# Decisions

## 1) 구간 이름은 성격이 아니라 기간으로 붙인다

기존 `bear`/`bull` 은 결과를 본 뒤 붙인 라벨이다. 새 구간은 `p2024h2`·`p2025h1` 처럼 **기간**으로 이름 붙이고,
실현된 성격(상승/하락/횡보)은 수집 후 README 에 기술한다. 이름이 곧 결론이 되면 나중에 "상승장에서 잘 된다" 같은
사후 서사가 데이터보다 먼저 자리잡는다.

## 2) G4 확장 — 사전고정 (재실행 전에 커밋한다)

- **G4a (시간 독립 holdout)**: `bull` · `p2024h2` · `p2025h1` **각각** 8마켓 paired delta 중앙값 ≥ **−1.0%p**.
  하나라도 미달이면 탈락. 셋 다 yearly 구간(2025-09-03~2026-09-02) 밖이므로 독립이다.
- **G4b (구간 robustness)**: `bear` — 기존과 동일 기준이되 **독립 증거로 세지 않는다**(bear ⊂ yearly).
- paired 마켓 교차검사는 **네 국면 공통 마켓**이 3개 이상일 때만 적용한다. 유동 유니버스가 회전하므로
  교집합이 줄어들 수 있고, 교집합을 늘리려 선정 규칙을 손대면 그게 다시 선택 편향이다([[backtest-engine]] fixture 규약).
- 임계값(−1.0%p)은 그대로 둔다. 국면이 늘었다고 임계를 낮추면 사전고정의 의미가 없다.

## 3) 다중비교 폭은 그대로 23조합

국면을 늘리는 것은 **후보 수**를 늘리지 않는다(같은 그리드를 더 많은 창에서 검증할 뿐). 따라서
`SEARCH_BREADTH = 23` 은 유지한다. 국면 추가는 게이트를 **엄격하게** 만드는 방향이라 다중비교 부담이 늘지 않는다.

## 4) 기존 fixture 는 재수집하지 않는다

`bear`/`bull` 을 다시 받으면 선행 측정([[yearly-strategy-comparison]]·[[parameter-search-2026-09]])의 모집단이
조용히 달라진다. 새 구간만 추가한다.

# Key Files

- `scripts/collect_backtest_fixtures.py` — 선정 규칙·수집의 단일 소스. `REGIMES` 에 2개 추가.
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt` — `Regime` enum·로스터 핀·`PAIRED_MARKETS`.
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixturesTest.kt` — 로스터 핀 테스트.
- `bot/src/test/kotlin/com/trading/bot/engine/StrategySearchStageA.kt` — G4a/G4b 적용부.
- `bot/src/test/resources/backtest/README.md` — 구간·성격·한계 기술.
- `wiki/pages/query/parameter-search-2026-09.md` — 재판정 결과 반영.

# Blockers

없음.

# Acceptance

1. 새 fixture 2세트가 시점 중립 규칙으로 수집되고, 마지막 봉이 완결됐음을 확인(구간 끝 다음날 09:00 KST 이후 수집).
2. `Regime` enum·핀·테스트가 새 구간을 포함하고 `BacktestFixturesTest` 통과.
3. G4a 가 3개 holdout 각각에 대해 평가되고, 그 정의가 코드·리포트·wiki 에서 일치.
4. Stage A·D 재실행 결과가 리포트에 기록되고, 통과 후보 수 변화(있으면 그 이유)가 서술된다.
5. `./gradlew build` 통과 + wiki 검증 3종 통과.
6. 라이브 코드 무변경.

# Deferred

# Review Disposition

# Workflow Findings
