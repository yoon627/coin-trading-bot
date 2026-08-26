---
title: fixture-universe-bias — 백테 유니버스 look-ahead 편향의 크기를 먼저 잰다 (#112)
status: in_progress
started: 2026-08-26
updated: 2026-08-26
---

# Goal

백테 fixture 의 마켓 선정이 **구간 끝 정보**(수집 시점 거래대금 상위)로 이뤄져 look-ahead 편향이 있다.
그 편향의 **크기를 먼저 정량화**한다 — 시점 중립 유니버스를 구성해 현재 유니버스와 비교하고,
결론이 뒤집힐 만한지 판단할 근거를 만든다. GitHub #112.

**재수집은 이번 범위가 아니다** (사용자 확정 2026-08-26). 진단 결과를 보고 별도로 결정한다.

# Progress

- 2026-08-26 Explore 완료. 선행 외부 사실 2건 확정(D1), 파급 범위 확인(D2), 범위를 진단으로 확정.

# Next

draft plan 검토(plan-reviewer + codex) → 지적 반영 → 진단 하네스 TDD.

# Decisions

## D1. 시점 중립 유니버스는 구성 가능하다 — 단 절반만 (✅공식 문서·API 실측)

| 편향 | 닫히나 | 근거 |
|---|---|---|
| **신규상장 배제**(look-ahead) | ✅ 닫힌다 | 구간 시작 시점 캔들 존재 여부로 상장 판정 가능 |
| **과거 거래대금 랭킹** | ✅ 닫힌다 | 일봉 응답에 **`candle_acc_trade_price`**(KRW 거래대금)가 있다 (API 실측 2026-08-26) |
| **생존편향** | ❌ 안 닫힌다 | `GET /v1/market/all` 응답은 `market`·`korean_name`·`english_name`(+선택 `market_event`)뿐이고 상장일 필드도 과거 시점 파라미터도 없다. **폐지 종목은 목록에서 사라져 열거 자체가 불가**(공식 문서 확인) |

생존편향은 비공식 공지 엔드포인트나 외부 데이터셋이 필요하다 → **이번 범위 밖, 한계로 명시**한다.
"편향을 없앴다"가 아니라 **"신규상장 배제 편향만 없앴고 생존편향은 남는다"**로 보고한다.

## D2. 파급 범위 — 유니버스를 바꾸면 커밋된 숫자가 전부 바뀐다

`BacktestFixtures` 를 참조하는 테스트 7개:
`BacktestLegacyGoldenTest`(trade 단위 골든 `legacy-golden.txt` 377줄) · `KneeStrategyComparisonTest` ·
`DailyResetCounterfactualTest`(#128 측정 하네스) · `BacktestReentryEquivalenceTest` ·
`BacktestFixturesTest` · `KneeRsiWindowTest`.

즉 재수집은 **골든 재생성 + #128 측정 재실행 + wiki `reset-churn-measurement` 갱신**을 동반한다.
크기를 모르는 채 그 비용을 치르지 않는다 — 그래서 진단이 먼저다(#128 에서 배운 규율: 측정 먼저, 처방 나중).

## D3. 진단 설계 — 2단계, 2단계 트리거는 사전 고정

**1단계 (네트워크 1회전, 결정적)**
1. `GET /v1/market/all` 로 현재 KRW 마켓 전체 목록
2. 각 마켓에 대해 **구간 시작 직전 30일** 일봉 1회 조회 (`to=<구간시작>&count=30`)
   - 응답이 비어 있지 않으면 = 그 시점에 **이미 상장**
   - `candle_acc_trade_price` 평균 = **그 시점 거래대금**
3. 기존 선정 규칙 중 유지할 것: 스테이블코인 제외, 200봉 확보 가능
4. 상위 8개 = **시점 중립 유니버스**. 국면별로 각각 산출
5. 현재 fixture 유니버스와 대조 — overlap 개수, 빠지는 마켓, 들어오는 마켓

**2단계 (조건부)** — 아래 트리거를 **1단계 결과를 보기 전에** 고정한다:
> BEAR·BULL **어느 한쪽이라도 overlap ≤ 5/8** 이면 결론 민감도까지 본다.
> 그때만 새 유니버스 fixture 를 **임시 수집(커밋 안 함)** 해 `DailyResetCounterfactualTest` 를
> 두 유니버스로 돌려 #128 결론의 **부호가 뒤집히는지** 확인한다.
> overlap ≥ 6/8 이면 "편향은 있으나 유니버스 구성은 크게 다르지 않다"로 보고하고 재수집을 권하지 않는다.

이 임계는 자의적이다 — 그래서 **사전에 적고 사후에 바꾸지 않는다**(#128 A5 규율 계승).

## D4. 하네스 위치 — 수동 전용 테스트 (기존 선례)

네트워크가 필요하므로 CI 비실행. `M1ReplayBiasTest`(`@EnabledIfEnvironmentVariable(RUN_M1_REPLAY)`,
API 실시간 fetch)·`ParameterSweepTest`·`DailyResetCounterfactualTest` 와 같은 패턴을 따른다.
새 파일 `PointInTimeUniverseTest`(가칭), `RUN_UNIVERSE_AUDIT=true` 로만 실행.

⚠️ Upbit 공개 시세 API 는 인증 불필요하지만 **rate limit** 이 있다. 마켓 수가 ~180 이므로
순차 호출 + 실패 시 backoff 로 돌리고, 전량 실패해도 부분 결과를 보고하게 만든다.

## D5. 기대치를 미리 낮춘다 — 이건 표본력 문제를 풀지 않는다

#112 를 고른 최초 근거는 "#128 결론을 묶은 표본 한계"였으나 **두 문제는 다르다**:
- #112 = **편향(타당성)** — 어떤 마켓을 골랐나
- #128 을 묶은 것 = **표본력(N_eff ≈ 2)** — 마켓 상관 0.49 라 마켓을 늘려도 실효 표본은 느리게 는다

상승장이 4 → 최대 8마켓이 되면 그 국면의 N 은 2배지만 N_eff 증가폭은 상관에 제약된다.
**"#112 를 고치면 #128 결론이 확정된다"는 기대는 틀렸다** — 리포트에 명시한다.

# Key Files

- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt` — `MARKETS_BY_REGIME`·`PAIRED_MARKETS`·`Regime` 구간 정의
- `bot/src/test/resources/backtest/README.md` — 선정 규칙·한계·재수집 절차(단일 소스)
- `bot/src/test/kotlin/com/trading/bot/engine/M1ReplayBiasTest.kt` — 네트워크 수동 하네스 선례
- `bot/src/test/kotlin/com/trading/bot/engine/DailyResetCounterfactualTest.kt` — 2단계에서 재사용할 측정 하네스
- `wiki/pages/concept/backtest-engine.md` · `wiki/pages/query/reset-churn-measurement.md` — 한계 서술 갱신 대상

# Blockers

없음.

# Acceptance

| # | 무엇이 충족되나 | 어떻게 검증 | 통과 기준 |
|---|---|---|---|
| A1 | 두 국면의 시점 중립 유니버스가 실제로 산출된다 | `RUN_UNIVERSE_AUDIT=true ./gradlew :bot:test --tests "*PointInTimeUniverse*"` 실행·출력 관찰 | 국면별 상위 8마켓 + 각 마켓의 구간시작 시점 거래대금이 표로 출력 |
| A2 | 현재 유니버스와의 차이가 수치로 나온다 | 같은 실행 | overlap 개수, 빠지는/들어오는 마켓 명시 |
| A3 | 상승장 4마켓 제약이 유니버스 탓인지 확인된다 | 같은 실행 | 2023-11 시점 상장 + 200봉 확보 가능한 KRW 마켓 수를 보고 |
| A4 | 2단계 트리거가 사전 고정대로 판정된다 | 결과와 D3 임계 대조 | overlap 임계 판정이 기록되고, 트리거 시에만 2단계 수행 |
| A5 | 재현 가능하다 | 하네스가 조회 파라미터·수집일을 출력 | 같은 명령으로 같은 유니버스가 재현(과거 구간이라 시세 불변) |
| A6 | 한계가 정직하게 남는다 | 리포트·문서 | 생존편향 미해결(D1)·표본력과 무관(D5)이 명시 |
| A7 | 회귀 없음 | `./gradlew :bot:test :common:test` | 기존 테스트 green (fixture 무변경이므로 당연 — 어기면 스코프 이탈 신호) |
| A8 | 문서 동기화 | wiki 3종 + README | `check_links.py`·`verify.sh`·`smoke.sh` 통과, fixture README 한계 절 갱신, 결과는 `wiki/pages/query/` |

# Review Disposition

# Deferred

- **생존편향** — 폐지 종목 열거 경로가 공식 API 에 없다(D1). 비공식 공지 파싱·외부 데이터셋은 별도 판단.
- **재수집 자체** — 진단 결과를 보고 결정(사용자 확정으로 이번 범위 밖).

# Workflow Findings
