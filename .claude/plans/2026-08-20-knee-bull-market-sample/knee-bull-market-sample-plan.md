---
title: knee-bull-market-sample — 상승장 표본 추가로 무릎 전략 국면 의존성 판정 (PR3)
status: done
started: 2026-08-20
updated: 2026-08-22
---

# Goal

PR2(#98) 판정의 최대 한계였던 **"하락장 한 국면"** 을 푼다. 상승장 표본을 추가하고, 두 국면에 모두 존재하는
마켓으로 **paired 비교**를 해서 마켓 효과와 국면 효과를 분리한다.

물음은 하나다 — **무릎 전략이 원래 안 좋은가, 아니면 하락장에서만 안 좋았나?**

# Progress

- 2026-08-20 — BTC 일봉을 200봉씩 거슬러 스캔해 국면을 확인하고 상승장 구간을 특정
  (2023-11-23~2024-06-09: BTC +96%, ETH +89%, DOGE +102%, XRP −16%).
  4마켓 fixture 수집, `BacktestFixtures` 를 2국면 구조로 확장, 비교 테스트에 paired 축 추가. 결과 확보.

## 결과 — 국면 탓이라는 설명은 지지되지 않는다

**paired 비교 (같은 4마켓, out-of-sample, pooled per-trade net pnl%)**

| config | 전략 | BEAR(하락장) | BULL(상승장) |
|---|---|---:|---:|
| LIVE_DEFAULT | knee_reversal | +0.306 (2위) | **−0.288 (6위)** |
| LIVE_DEFAULT | knee_pullback | −0.084 (5위) | +0.204 (4위) |
| SWING | knee_reversal | −0.847 (5위) | −0.416 (4위) |
| SWING | knee_pullback | **−2.527 (9위)** | −0.928 (5위) |

⚠️ **먼저 표본 크기**: 위 셀의 거래 수는 4~18건이다. 특히 `knee_pullback` BEAR/SWING 의 −2.527 은
**4건 평균**이라 한 거래만 달라져도 크게 흔들린다. 아래 서술은 그 한계 안에서 읽어야 한다.

1. **"하락장이라 나빴다"는 이 데이터로 지지되지 않는다.** `knee_reversal` 은 상승장에서도 음수(−0.288,
   15건)이고 순위가 2위 → 6위로 내려간다. 국면을 바꿔도 개선되는 징후가 없다 — "기각"이라 단정할 만큼
   표본이 크진 않지만, **국면 탓이라는 설명을 뒷받침하는 증거도 없다.**
2. **어깨 청산(SWING)은 네 조합 모두에서 성과를 낮췄다**
   (+0.306→−0.847, −0.084→−2.527, −0.288→−0.416, +0.204→−0.928). 부호가 4/4 일치한 것은 눈에 띄지만,
   네 조합은 같은 마켓·겹치는 기간을 공유해 **독립 시행이 아니다**(실질 독립은 2개 미만). 따라서
   "일관되게 해롭다"가 아니라 **"악화 방향의 신호가 반복 관찰됐다"** 로만 말할 수 있다. 크기는 신뢰하지 않는다.
3. 두 전략의 부호가 국면별로 엇갈린다(`reversal` 은 BEAR 에서, `pullback` 은 BULL 에서 양수). 서로 다른
   국면을 노린 설계 의도와 **반대 방향**이라 신호라기보다 노이즈로 보는 편이 타당하다.

**결론: 승격 근거 없음.** PR2 판정을 유지한다. 달라진 것은 "하락장이라서"라는 유보가 더는 유효하지
않다는 점이고, 새로 얻은 것은 **어깨 청산이 성과를 낮추는 방향이라는 반복 관찰**이다(확정 아님).

# Next

완료 — PR #99 머지(`fa69354`). 사후 정적 리뷰 지적은 `knee-review-followup` 브랜치에서 반영했다.

# Decisions

- **상승장 구간 = 2023-11-23 ~ 2024-06-09**: BTC 일봉을 200봉씩 6구간 거슬러 스캔해 고른다
  (직전 두 구간은 −23%/−22% 로 하락, 그 앞이 +15%/+48%/+96%/+31%). +96% 구간이 상승 폭·최대낙폭(20%)
  균형이 가장 좋다.
- **상승장은 4마켓뿐**: `KRW-XRP`·`KRW-BTC`·`KRW-ETH`·`KRW-DOGE`. `MMT`·`WLD`·`RVN`·`ONDO` 는 그 시기
  **미상장**(0봉)이다. 현재 거래대금 상위 8개 중 절반이 2년 전엔 존재하지 않았다는 뜻으로,
  PR2 가 지적한 **유니버스 look-ahead/생존편향의 실증**이다. 이 사실 자체를 테스트로 고정한다.
- **paired 비교를 판정 근거로 삼는다**: BEAR 8마켓 vs BULL 4마켓을 직접 비교하면 국면 효과와 종목 효과가
  섞인다. 두 국면에 모두 있는 4마켓만으로 비교해야 차이가 국면에서만 온다.
- **fixture 를 `bear/`·`bull/` 로 분리**: 국면이 늘어날 수 있으므로 디렉토리로 구조화하고 로더가
  `Regime` enum 으로 고른다. 기존 8개는 `git mv` 로 옮겨 이력을 보존한다.
- PR2 의 원칙을 그대로 유지: `useMarketFilter=false`, 그리드서치 금지, 청산 조합은 `LIVE_DEFAULT`/`SWING`
  두 가지만, 거래 0 은 `N/A`, in-sample 선택 / out-of-sample 판정.
- **RSI window 테스트도 두 국면을 순회**하게 확장했다 — 검사 케이스가 늘수록 window 의존성 회귀를 확실히 잡는다.

# Key Files

- `bot/src/test/resources/backtest/bull/*.json` — 신규 4마켓 × 200봉 (146KB)
- `bot/src/test/resources/backtest/bear/*.json` — 기존 8개 이동
- `bot/src/test/resources/backtest/README.md` — 두 국면 출처·한계 갱신 필요
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt` — `Regime` enum, `PAIRED_MARKETS`, `loadPaired`
- `bot/src/test/kotlin/com/trading/bot/engine/KneeStrategyComparisonTest.kt` — 2국면 표 + paired 비교
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixturesTest.kt` — 국면별 무결성·마켓 구성 고정
- `bot/src/test/kotlin/com/trading/bot/strategy/KneeRsiWindowTest.kt` — 2국면 순회
- `wiki/pages/concept/swing-strategies.md` — 판정 결과 반영 필요

# Acceptance

| # | 충족 기준 | 검증 방법 | 통과 조건 |
|---|---|---|---|
| 1 | 상승장 fixture 4개가 200봉·최신순·OHLC 정합 | `BacktestFixturesTest` | green |
| 2 | 국면별 마켓 구성이 고정된다(BULL 은 4마켓, paired 는 두 국면 공통) | 같은 테스트 | green |
| 3 | 두 국면 × 2 sample × 2 config 백테 완주 | `KneeStrategyComparisonTest` | green |
| 4 | paired 비교표 산출 (같은 4마켓, 두 국면) | 테스트 출력 → plan 기록 | 표 확보 |
| 5 | RSI window 불변성이 두 국면 전 구간에서 유지 | `KneeRsiWindowTest` | green |
| 6 | in/out 겹침 없음이 두 국면 모두에서 성립 | 겹침 테스트 | green |
| 7 | 문서 동기화 (resources README + wiki + 검증 3종) | `check_links.py`·`verify.sh`·`smoke.sh` | 통과 |
| 8 | 전체 검증 | `./gradlew :bot:test compileKotlin` | 통과 |

# Blockers

없음.

# Deferred

- PR2 에서 넘어온 항목 유지: candle sufficiency 계약 분산(21/20/40/41), `closeOpenPosition` 의 MDD 미갱신,
  `wiki/backtest-engine.md:23` 의 51봉 conflict.
- **어깨 청산이 성과를 낮추는 방향이라는 반복 관찰**(4/4 조합, 단 독립 시행 아님)은 후속 조사 대상이다. `ShoulderExit` 임계(RSI 70,
  볼밴 상단)가 너무 이른 이탈을 만드는지, 아니면 차트 청산 자체가 이 봇 구조에 안 맞는지는 이번 범위 밖.

# Review Disposition

| finding | 처분 |
|---|---|
| 결과 서술이 표본 크기에 비해 과했다 (거래 4~18건인데 "기각"·"일관되게 해롭다") | **fix** — 자체 재검토로 표현 완화. 셀당 거래수를 앞에 명시하고, 4/4 부호 일치는 "독립 시행 아님(실질 <2)"을 붙여 관찰로만 기술 |
| `bear/` 8개가 순수 이동인지 | **확인 완료** — `git diff -M` 이 8개 전부 `R100`(내용 불변) |
| `bull/` fixture 무결성 | **확인 완료** — 4개 전부 200봉·2023-11-23~2024-06-09·최신순·OHLC 정합 |
| paired 비교의 구조적 타당성 | **확인 완료** — 두 국면 모두 200봉·동일 분할(`inSample`/`outOfSample`)·동일 config 를 쓰므로 마켓 구성만 통제되면 차이는 국면에서 온다 |

⚠️ **code-reviewer 는 세션 한도로 두 번 중단됐다**(49 tool_uses 후 종료). 위 항목은 메인이 직접 검증했고,
정적 리뷰(관례·죽은 코드·vacuous 단언 등)는 **미수행**이다. 후속 세션에서 `/code-review` 로 보완할 것.

# Workflow Findings

- **dlc 단계 이탈 — draft plan·plan-reviewer 를 건너뛰고 구현 먼저 진행**(2026-08-20). PR2 구조를 그대로
  확장하는 작업이라 설계 리스크가 낮다고 판단했으나, 규모표상 medium 은 plan 을 선행해야 한다.
  plan 을 소급 작성해 보강했고 code-reviewer 는 정상 수행한다. 같은 이탈이 반복되면 게이트 강화 검토.
- **PreToolUse hook 오탐 (누적 3회)**: PR1·PR2 리뷰에서 "staged `.kt` 가 없으니 종료" 메시지가 working-tree
  리뷰를 차단했다. PR2 plan 에도 기록했으며 **동일 유형 3회 재현** — hook 설정 점검이 필요하다.
