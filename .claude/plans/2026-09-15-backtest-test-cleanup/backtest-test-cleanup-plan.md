---
title: backtest-test-cleanup — 백테 테스트 정리 5건 (#111)
status: done
started: 2026-09-15
updated: 2026-09-15
---

# Goal

PR #103 사후 리뷰의 저우선순위 정리 5건(#111): aggregate 중복 계산 · fixture JSON 재파싱 · 근거 문구 중복 · `String.format` locale · 국면 라벨 날짜 가드. 기능 영향 없음, 테스트 트리만.

# Progress

- 2026-09-15 — worktree 생성(base `main@604fbea`). 4건 구현. "근거 문구 6곳 중복"은 **축소**: "bull 이 4마켓인 이유" 프레이밍은 #112 재수집(BULL 8마켓)으로 이슈가 지목한 3파일(`BacktestFixtures.kt`·`KneeStrategyComparisonTest`·`BacktestFixturesTest`)에서 사라졌다(잔존 "4마켓"은 전부 #112 이전 기준 historical 서술). 생존편향 한 문장("상장폐지 종목은 404 라 표본에 못 넣는다")은 `BacktestFixtures.kt`·`resources/backtest/README.md`·wiki 에 여전히 중복 → Deferred.
- 2026-09-15 — code-reviewer(+codex): Major 1(골든 비교 `BacktestLegacyGoldenTest` 의 기본 로케일 `.format` 이 비-`.` 로케일에서 하드 실패 — Kotlin 확장 `.format` 137곳 잔존) → `tasks.test` 에 `user.language=en/US` 고정으로 근본 처리. Nit: 캐시 사본화·단일키 Map 제거 반영.

# Next

없음 — 로컬 ff-merge 로 종결. 잔여 2건은 `# Deferred`(#111 코멘트에 기록).

# Decisions

## 1) 캐시는 `ConcurrentHashMap.getOrPut`, 프로세스 수명

`Candle` 은 불변 data class 라 리스트를 공유해도 안전. `by lazy` 로 전 국면을 선적재하면 한 국면만 쓰는 테스트도 다 읽으므로 키별 지연 캐시.

## 2) 날짜 가드의 기대값은 구현 상수가 아니라 표로 독립 기재

`Regime` KDoc 의 구간을 테스트에 옮겨 적었다. 구현 상수를 참조하면 재수집 때 같이 바뀌어 통과한다(기존 roster 테스트와 같은 원칙). `Regime.entries` 와 표의 키 집합이 같아야 하므로 국면을 추가하면 표도 채워야 한다.

# Review Disposition (code-reviewer + codex, 2026-09-15)

- **fix** 로케일: `String.format` 4곳 외 Kotlin `.format` 137곳/33파일이 기본 로케일 — 골든 문자열 비교가 하드 실패 → `bot/build.gradle.kts` `tasks.test` 에 `user.language=en`·`user.country=US`(137곳 일괄).
- **fix** plan 근거 "4마켓 0건" 은 거짓(실측 24건, 전부 historical) → 문장 교정.
- **fix** 캐시가 Jackson 가변 ArrayList 를 그대로 담음 → `toList()` 사본. 단일 키 Map → nullable var.
- **defer** 생존편향 문장 3~4중복(`BacktestFixtures.kt`·`README.md`·wiki) 압축 — 이슈 항목 3 의 잔여.
- **defer** `YearlyFixtures`(544KB)·`IntradayFixtures`(28MB) 로더는 캐시 없음 — 재파싱 낭비의 큰 몫.
- **판정** wiki `swing-strategies`·`trailing-arm-finding` 이 변경 파일을 sources 로 갖지만 수치·서술 불변(같은 입력·같은 계산) → 본문 갱신 없음.

# Acceptance

1. `BacktestFixturesTest`(날짜 가드 포함)·`KneeStrategyComparisonTest` 통과.
2. `aggregate` 호출이 테스트 1·2 에서 각각 2회 줄고(단언이 리포트 표를 재사용), `load` 는 같은 (regime, market) 을 두 번 파싱하지 않는다 — 코드 확인.
3. `String.format` 4곳 전부 `Locale.ROOT` + 테스트 JVM 로케일 `en_US` 고정(`bot/build.gradle.kts`).

# Key Files

- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt` — load 캐시
- `bot/src/test/kotlin/com/trading/bot/engine/KneeStrategyComparisonTest.kt` — aggregate 공유·Locale
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixturesTest.kt` — 날짜 가드

# Deferred

- 생존편향 한 문장이 `BacktestFixtures.kt:19-20`·`bot/src/test/resources/backtest/README.md:62`·`wiki/pages/query/reset-churn-measurement.md:83`(+`RegimeExpansionTest.kt`)에 중복 — README 단일 소스로 압축. 낮음.
- `YearlyFixtures.loadAll`·`IntradayFixtures`/`IntradayCache` 는 캐시 없이 재파싱(intraday240 28MB 를 5개 테스트가 중복 로드). `BacktestFixtures` 와 관례 비일관. 낮음.

# Blockers

없음.
