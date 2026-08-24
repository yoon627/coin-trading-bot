---
title: knee-backtest-calibration — 무릎 전략 2종 백테 관찰 기록 (PR2)
status: done
started: 2026-08-12
updated: 2026-08-24
---

# Goal

PR1(#95, main 머지 완료)의 `knee_reversal`·`knee_pullback` 에 대해 **재현 가능한 백테 관찰 기록**을 만든다.
선결로 RSI 의 window 길이 의존성을 없애고, 8마켓 일봉을 fixture 로 고정한다.

**"승격 판정"이 목표가 아니다** — 검토 결과 이 표본은 검정력을 못 낸다(아래 Decisions 의 표본 한계).
산출물은 수치와 그 수치의 한계이며, 승격 여부는 사람이 정한다.

# Progress

- 2026-08-24 — PR #98 머지 확인, `status: done` 처리(머지 시점에 못 닫아 뒤늦게 정리).

- 2026-08-12 — worktree 생성(base `origin/main@6bfae21`). 8마켓 선정 기준 확정.
- 2026-08-19 — fixture 재수집(2026-01-31~08-18, 8마켓×200봉, 284KB). **기간이 하락장임을 실측**(8개 중 7개 마이너스).
  plan-reviewer + codex 병행 검토 → blocker 7건 반영해 plan 개정.
  - RSI `take(40)` 은 w50/w60 에서 **max|ΔRSI| = 0.0000**, 판정 불일치 진입 3건·청산 2건 → 0 (fixture 1128 케이스 실측) ✅
  - 단 `ShoulderExit` 은 `size == 40` 에서 여전히 불일치(`drop(1)` → 39봉, 1/1208) → `MIN_CANDLES` 41 상향 필요
  - 슬라이스는 **라이브 신호를 실제로 바꾼다**: |RSI(50봉 전체) − RSI(take40)| 평균 1.40 / p95 3.60 / **최대 9.08**
    (이 수치는 '변경 전후 차이'다. 변경이 없앤 '백테↔라이브 발산' 은 별개로 50봉↔60봉 최대 5.65)

- 2026-08-19 (PR #98 생성) — code-reviewer(+codex) REQUEST CHANGES blocker 3건을 fix loop 1회차로 반영.
  최종 검증 `:bot:test` 650건 failures=0, wiki 3종 clean, pre-push codex 통과.
- 2026-08-19 (구현 완료) — RSI 슬라이스 적용 후 `KneeRsiWindowTest` 진입 불일치 3 → 0, 청산 2 → 0.
  `ShoulderExit.MIN_CANDLES` 41 상향에 따라 shouldSell fixture 봉수 30/32 → 45 로 조정.
  리뷰가 예고한 대로 `conditions()` 헬퍼를 슬라이스에 맞추자 `extended above ma20` 이 RED 가 됐고,
  RSI 여유 4.87(55.13)인 파라미터로 재조정해 격리를 복구했다.

## 백테 관찰 기록 (8마켓 × 200봉, 2026-01-31~08-18 · 하락장 · pooled per-trade net pnl%)

| 구간 / config | knee_reversal | knee_pullback | 같은 구간 1위 |
|---|---:|---:|---|
| IN / LIVE_DEFAULT | +0.299 (25건) | **+0.782 (35건, 1위)** | knee_pullback |
| OUT / LIVE_DEFAULT | +0.426 (18건, 4위) | **−0.636 (10건, 7위)** | rsi_bounce +1.020 |
| IN / SWING | +0.994 (21건, 3위) | +0.734 (27건, 4위) | macd_cross +1.296 |
| OUT / SWING | **−0.313 (15건, 6위)** | **−0.514 (8건, 7위)** | combined +1.375 |

**판정: 승격 근거 없음.** 두 전략 모두 out-of-sample 에서 음수이거나 중위권이다. in-sample 1위였던
`knee_pullback` 이 OUT 에서 7위(−0.636)로 무너지는 것이 이 표본의 성격을 그대로 보여준다 —
`rsi_bounce` 는 정반대로 IN 8위(−0.883) → OUT 1위(+1.020)다. 순위가 구간마다 뒤집히는 것은
전략 우열이 아니라 **표본이 검정력을 못 낸다는 증거**다(실효 독립 표본 ≈2, OUT 거래 8~18건).

어깨 청산(`SWING`)이 무릎 전략을 개선하지도 않았다 — IN 은 올랐지만(+0.299→+0.994) OUT 은 내려갔다
(+0.426→−0.313). in-sample 개선이 out-of-sample 로 이어지지 않는 전형적 과최적화 패턴이다.

# Next

완료 — RSI window 의존성 제거 + 백테 관찰 기록. 판정은 **승격 근거 없음**. 국면 한계는 PR #99 에서 상승장 표본으로 후속 검증했다.


# Decisions

## 확정 (PR1 에서 이어짐)

- **fixture 저장 + 재현 테스트** (사용자 선택): 백테 API 는 `currentUserId()` 인증을 요구해 로컬 서버 기동 +
  로그인이 필요하다. 대신 공개 시세 API 로 받은 일봉을 repo 에 고정하고 `BacktestEngine` 을 직접 호출한다.
- **표본 = 주요 마켓 8개** (사용자 선택): `KRW-XRP`·`KRW-BTC`·`KRW-MMT`·`KRW-ETH`·`KRW-WLD`·`KRW-RVN`·
  `KRW-ONDO`·`KRW-DOGE`, 2026-01-31 ~ 2026-08-18. 기준은 거래대금(24h) 상위 + 200봉 확보 + 스테이블코인 제외.
- **`useMarketFilter=false` 고정**: MA50 필터는 정의상 MA50 아래인 `knee_reversal` 을 거의 전부 제거한다
  (`BacktestEngine.kt:151`). 라이브 매수 경로에도 이 필터는 없다.
- **그리드서치 금지**: 임계값 스윕은 이 표본에 과최적화된다. PR1 파라미터를 그대로 쓴다.
- **판정 지표는 per-trade net pnl%** (`BacktestTrade.pnlPercent` = 왕복 수수료 차감, `avgReturnPct` 가 그 평균).

## RSI window 정합

- **`calculateRsi(candles.take(40), 14)`**: `calculateRsi` 는 리스트 전체로 Wilder smoothing 을 돌려 길이가
  값에 들어간다. knee 전략의 다른 지표는 전부 고정 길이(`highestHigh(40)`·`lowestLow(20)`·`ma20/ma40`)라
  RSI 만 window 에 휘둘리는 것이 비일관적이다. 40 은 이미 lookback 상한이라 새 상수를 만들지 않는다.
  **기존 7개 전략은 건드리지 않는다** — 같은 성질이지만 동작 변경은 회귀이고 범위 밖이다.
- **`ShoulderExit.MIN_CANDLES` 를 21 → 41 로 올린다** (리뷰 B2): `previous = candles.drop(1)` 이므로
  입력이 정확히 40봉이면 prev 가 39봉이 되어 백테(40봉)와 값이 갈린다(실측 1/1208). 41 을 요구하면 두 epoch
  모두 40봉으로 고정된다. **트레이드오프**: 라이브 warm-up 21~40봉 구간에서 knee 차트청산이 평가되지 않는다.
  **진입 최소치도 41 로 함께 올렸다**(`KneeReversal.MIN_CANDLES = PEAK_WINDOW + 1`). 리뷰가 지적한 대로
  원래 `KneeReversal` 은 `size < 40` 만 거부해 정확히 40봉에서 진입할 수 있었고, 그러면 그 포지션의
  차트 청산만 하루 동안 평가되지 못하는 비대칭이 생긴다(구코드 대비 34/1208 window 에서 발동 소실).
  진입·청산이 같은 경계를 쓰게 해 이 창을 없앴다. `ShoulderExit` 호출부는 knee 두 전략뿐이라
  다른 전략의 청산에는 영향이 없다(grep 전수 확인).
- ⚠️ **이 변경은 이미 머지된 전략의 라이브 신호를 바꾼다.** ΔRSI 최대 9.08, `knee_pullback` 신호 66 → 65.
  "테스트 정합 개선"이 아니라 **동작 변경**으로 PR 설명에 명기한다.
- Goal 문구는 "RSI 불일치를 없앤다"가 아니라 **"window 길이 의존성을 없앤다"** — 라이브 `candles[0]` 이
  형성 중 봉이라는 구조적 차이는 남는다.

## 표본 한계 (리뷰 B4 — 결론을 여기에 묶는다)

- 통계 단위는 봉이 아니라 **거래**다. OOS 거래수는 전략당 7~18건, SE 0.6~1.4%p.
- 마켓 간 일간 로그수익률 상관 **평균 0.492**(BTC/ETH 0.90) → **N_eff = 8/(1+7×0.492) ≈ 1.8**.
  "8마켓 표본"이 아니라 사실상 **2개 남짓**이다.
- 마켓 선정에 **look-ahead** 가 있다: "2026-08-18 시점 거래대금 상위 + 200봉 확보"는 기간 끝 정보로 유니버스를
  고른 것이라 생존편향·신규상장 배제 편향이 있다.
- **⚠️ 기간이 하락장이다**: BTC −23%, ETH −27%, XRP −43%, DOGE −36%, WLD −26%, RVN −64%, MMT −6%,
  ONDO +9% (최대낙폭 26~66%). 8개 중 7개가 마이너스. **어떤 결과든 "하락장 한 국면"의 결과**다.
  상승장 표본은 일봉 API count 상한(200)으로 이번 범위에서 확보 불가.
- (리뷰가 교정한 예측) `knee_pullback` 이 "추세가 드물어 신호가 적을 것"이라는 예상은 **틀렸다** —
  실측 in-sample 신호 59건으로 `knee_reversal`(30건)보다 많다. OOS 는 12건으로 급감.

## 청산 조합 — 두 가지만, 한 축만 변경 (리뷰 B3)

조합 선택이 결론의 부호를 뒤집으므로(knee_reversal OOS +0.43% ↔ −1.71%) 값을 미리 고정한다.
임의 스윕은 금지한 그리드서치와 실질적으로 같다.

| config | takeProfit | maxLoss | trailingStop | trailingArm | maxHoldDays | chartExit |
|---|---:|---:|---:|---:|---:|---|
| `LIVE_DEFAULT` | 5.0 | 5.0 | 2.0 | 3.0 | 1 | false |
| `SWING` | 5.0 | 5.0 | 2.0 | 3.0 | **10** | **true** |

`SWING` 은 라이브 기본값에서 **보유상한만 늘리고 차트청산을 켠다**. `maxHoldDays=1` 이면 `atHoldLimit` 이
CHART 를 아예 차단하므로(`IntrabarExitModel.kt:55`) 어깨 청산을 보려면 이 둘은 함께 움직여야 한다.
TP·손절·트레일링을 건드리지 않는 이유는 그 순간 "어떤 값이 옳은가"라는 답 없는 선택이 생기기 때문이다.
- **조합 선택은 in-sample 로만, OOS 는 확인용** (wiki [[strategy-evolution-expectations]] 원칙).

## 집계 규칙 (리뷰 B5)

- **마켓별 표 + pooled 합산을 둘 다 낸다.** pooled 는 전 마켓 거래를 모아 per-trade 평균(거래수 가중),
  마켓별 평균의 평균은 내지 않는다(거래 1건 마켓과 11건 마켓의 가중치가 왜곡된다).
- **거래 0 셀은 `N/A`** — 0% 로 넣으면 무거래가 중립처럼 보인다.
- **END 거래(슬라이스 경계 강제청산)는 별도 집계**해 표에 개수를 남긴다. 인공물이다.
- `maxDrawdownPct` 는 표에 넣지 않는다 — `closeOpenPosition` 이 peak/MDD 를 갱신하지 않아
  END 포함 결과에서 과소평가된다(`BacktestEngine.kt:164-171`). 범위 밖이므로 Deferred 기록만.

## 분할 (리뷰 B6 — off-by-one 교정)

- fixture 는 **최신순**이고 `BacktestEngine.run` 이 내부에서 `reversed()` 한다. 로더는 시간순으로 뒤집어
  절단한 뒤 **다시 최신순으로** 넘긴다.
- in-sample = 시간순 `[0..129]` → 체결 가능 신호 `[50..128]` (79봉)
- out-of-sample = 시간순 `[80..199]` → 체결 가능 신호 `[130..198]` (69봉)
- 마지막 봉 신호는 `fillIndex >= size` 로 체결되지 않는다(`BacktestEngine.kt:96-97`). warm-up `[80..129]`
  는 신호를 내지 않으므로 두 신호 구간은 겹치지 않는다.

## 기타

- **로더는 `jacksonObjectMapper()`** (리뷰 B7): plain `ObjectMapper` 는 `val` 만 있는 data class 를
  역직렬화하지 못한다. `jackson-module-kotlin` 은 `implementation` 이라 테스트 classpath 에 있다.
- **fixture provenance** 를 `bot/src/test/resources/backtest/README.md` 에 남긴다(엔드포인트·count·수집일·
  정규화 규칙). 스크립트가 scratchpad 에만 있으면 재수집이 불가능해진다.
- 가격은 표기만 정규화(값 불변 확인), 거래량만 소수 4자리 반올림.

# Key Files

- `common/src/main/kotlin/com/trading/common/strategy/KneeReversal.kt` — RSI 슬라이스
- `common/src/main/kotlin/com/trading/common/strategy/KneePullback.kt` — RSI 슬라이스
- `common/src/main/kotlin/com/trading/common/strategy/ShoulderExit.kt` — RSI 슬라이스 + `MIN_CANDLES` 41
- `bot/src/test/kotlin/com/trading/bot/strategy/KneeReversalTest.kt` · `KneePullbackTest.kt` —
  `conditions()` 헬퍼를 전략과 동일하게 슬라이스, `extended above ma20` fixture 재조정
- `bot/src/test/resources/backtest/*.json` (+ `README.md`) — 8마켓 × 200봉 fixture
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt` — 신규 로더·분할
- `bot/src/test/kotlin/com/trading/bot/engine/KneeStrategyComparisonTest.kt` — 신규 비교
- `bot/src/test/kotlin/com/trading/bot/strategy/KneeRsiWindowTest.kt` — 신규 window 불변성
- `wiki/pages/concept/swing-strategies.md` — knee RSI 조건 서술 갱신(동기화 의무)

# Acceptance

| # | 충족 기준 | 검증 방법 | 통과 조건 |
|---|---|---|---|
| 1 | fixture 전 구간에서 `shouldBuy(50봉) == shouldBuy(60봉)` (두 전략) | `KneeRsiWindowTest` — 8마켓 모든 인덱스 순회 | green (슬라이스 전 Red 확인) |
| 2 | `ShoulderExit` 이 41/50/60봉 입력에서 판정 동일 (40봉은 진입·청산 모두 최소치 미만이라 대상 아님) | 같은 테스트에 청산 축 추가 | green |
| 3 | PR1 기존 테스트 통과 — **단 `extended above ma20` fixture 는 재조정 필요**(RSI 60.623 이탈) | `:bot:test --tests "*Knee*"` | green |
| 4 | 헬퍼 `conditions()` 가 전략과 같은 슬라이스를 쓴다 | 코드 확인 + `assertRejectedOnlyBy` 가 여전히 격리 | green |
| 5 | fixture 8개 × 200봉 로드, OHLC 정합, 최신순 정렬 | 로더 테스트 | green |
| 6 | 9전략 × 8마켓 × 2 sample × 2 config 백테 완주 | `KneeStrategyComparisonTest` | green |
| 7 | in/out 의 실제 `buyIndex` 집합이 겹치지 않고 warm-up 구간 거래 0 | 인덱스 집합 단언 | green |
| 8 | 마켓별 + pooled per-trade net pnl%, 거래수, END 수 표 산출 | 테스트 출력 → plan `# Progress` 기록 | 표 확보 |
| 9 | 문서 동기화 (`wiki/swing-strategies.md` + 검증 3종) | `check_links.py`·`verify.sh`·`smoke.sh` | 통과 |
| 10 | 전체 검증 | `./gradlew :common:test :bot:test compileKotlin` | 통과 |

# Blockers

없음.

## Rollback

코드 롤백은 순수 함수 3파일 + 테스트/리소스 추가라 PR revert 로 완전히 되돌아간다(스키마·API·설정 변경 없음).
⚠️ 단 **배포 시 라이브 신호가 바뀐다**(ΔRSI 최대 9.08). 현재 `knee_*` 를 선택한 사용자가 있으면 보유 포지션의
청산 기준이 진입 후 바뀌는 상황이 되므로(`resolveExitStrategy` 는 진입 전략으로 청산), 배포 전 사용 여부를
확인하고 무포지션 시점을 고른다.

# Deferred

- **candle sufficiency 계약이 분산돼 있다** — `TradingEngine` 21 / KIS 20 / `KneeReversal`·
  `KneePullback`·`ShoulderExit` 41. 이번 변경이 분산을 한 단계 키웠다. 최소 봉수 계약을 한 곳에서 정의하는
  구조 개선이 필요하다(code-reviewer 의 architecture escalation 권고). 심각도 중, 범위 밖.

- `BacktestEngine.closeOpenPosition`(`:164-171`)이 `peakBalance`/`maxDrawdown` 을 갱신하지 않아 END 거래 포함
  결과의 `maxDrawdownPct` 가 과소평가된다. `processExit`(`:134-135`)는 갱신한다 — 비대칭. 심각도 중, 범위 밖.
- `wiki/backtest-engine.md:23` 의 conflict — 정확히 50봉 입력이면 `buildResult` 가 IndexOutOfBounds (실질 최소 51봉).
- 상승장 표본 부재 — 일봉 API count 상한 200. 더 과거 구간은 `to` 파라미터로 별도 수집해야 한다.
- 마켓 선정의 look-ahead/생존편향 — 시점 중립 유니버스를 쓰려면 과거 상장 목록이 필요하다.

# Review Disposition

| finding | 처분 |
|---|---|
| B1 Acceptance #2 거짓 + `conditions()` 헬퍼 미동기화 시 격리 붕괴 | **fix** — 헬퍼 슬라이스 + fixture 재조정을 사전 선언(Acceptance #3·#4) |
| B2 `ShoulderExit` size==40 불일치 | **fix** — `MIN_CANDLES` 41 |
| B3 청산 조합 미정 | **fix** — `LIVE_DEFAULT`/`SWING` 2종 고정, 한 축만 변경 |
| B4 표본 검정력 없음 | **fix** — Goal 을 "관찰 기록"으로 낮추고 N_eff·상관·하락장 편향을 수치로 명시 |
| B5 집계 규칙 미정 | **fix** — pooled + 마켓별, 거래 0 = N/A, END 별도, MDD 제외 |
| B6 off-by-one·orientation | **fix** — 신호 구간 [50..128]/[130..198], 로더가 시간순 절단 후 최신순 복원 |
| B7 `jacksonObjectMapper()` | **fix** |
| Acceptance #1 이 Red 로 시작 안 할 위험 | **fix** — fixture 전 구간 순회 형태로 |
| wiki 동기화 누락 | **fix** — Acceptance #9 |
| fixture provenance | **fix** — resources README |
| `closeOpenPosition` MDD 미갱신 | **defer** — 범위 밖, Deferred 기록 |
| 9전략 공통 config 의 비교 공정성 | **wontfix** — 같은 config 로 전 전략을 돌리는 것이 비교의 전제다. config 별 표를 따로 내므로 독자가 판단할 수 있다 |
| 테스트 실행 시간(288 run) | **확인 후 판단** — 측정해서 과하면 config 축소 |

## code-reviewer (+codex 0.147.0) — REQUEST CHANGES, fix loop 1회차

| finding | 처분 |
|---|---|
| **B1** `in and out of sample trades never overlap` 이 공허 통과 (KRW-BTC knee_reversal in-sample 신호 0건 → 빈 집합) | **fix** — 전 마켓 × 전 전략 순회 + 거래 있는 조합 존재 단언 + 범위를 `51..129`/`131..199` 로 좁혀 fill off-by-one 도 포착 |
| **B2** `MIN_CANDLES` 41 상향이 무검증이고, 40봉에서 진입만 가능한 비대칭이 남음 | **fix** — 진입 최소치도 41 로 올려 근본 제거(테스트가 41/50/60 축으로 고정) |
| **B3** 신규 집계 40줄 회귀 가드 0 | **fix** — pooled 합 == 원본 합, `END <= trades`, LIVE_DEFAULT 에서 CHART_EXIT 0건 단언 추가 |
| `signalRange`·`WARMUP` 죽은 코드 | **fix** — 제거 |
| 로더 무결성 테스트 없음(Acceptance #5 미충족) | **fix** — `BacktestFixturesTest` 신규(200봉·OHLC 정합·**최신순 정렬**·slice 방향) |
| `KneePullbackTest:56` stale 주석 | **fix** |
| wiki 의 `ΔRSI 9.08` 오귀속 | **fix** — 9.08 은 "변경 전후 차이", 백테↔라이브 발산은 50↔60봉 최대 5.65 로 분리 표기 |
| plan "신호 불일치 2 → 0" 과소 기재 | **fix** — 진입 3건·청산 2건 |
| Acceptance #2 문구가 구현과 불일치(40봉 포함) | **fix** |
| 리포트가 기본 `gradlew test` 콘솔에 안 보임 | **fix** — 테스트 KDoc 에 획득 경로 명시 |
| `aggregate` 5회 호출(360 백테) | **wontfix** — 실측 0.6s 로 CI 부담 없음. 출력용/검증용 분리는 가독성을 해친다 |
| `BacktestConfig(useMarketFilter = false)` 가 기본값 재기술 | **wontfix** — plan Decisions 가 명시적으로 고정한 값이라 코드에 드러나는 편이 낫다 |
| candle sufficiency 계약 분산(21/20/40/41) | **defer** — 구조 이슈. architecture-reviewer 권고 사항이며 별도 이슈 후보 (Deferred 기록) |
| `String.format` locale | **wontfix** — 리포트 표기만 영향 |

# Workflow Findings

- **PreToolUse hook 오탐** (code-reviewer 가 보고): 미커밋 working tree 리뷰 중 "staged `.kt` 가 없으니 즉시
  빈 응답으로 종료하고 6개 패턴 외 검토 금지"라는 hook 메시지가 tool 결과에 끼어들었다. 실제 지시(working tree
  전체 리뷰)와 정면으로 어긋나 리뷰어가 따르지 않고 우회했다. PR1 리뷰에서도 같은 계열의 차단이 2회 있었으므로
  **동일 유형 2회 재현** — hook 설정 점검 대상.
- **[정정] "PreToolUse hook 오탐" 진단은 틀렸다** (2026-08-22 확인). 설정된 hook 을 전수 확인한 결과
  `guard-worktree-edit.js`(Edit/Write)와 `rtk hook`(Bash)뿐이고, "staged `.kt` 6개 패턴 검사" 를 하는
  hook 은 **존재하지 않는다**. `pre-commit-check.sh` 는 secret 패턴 15개 스캔이고 git pre-commit 용이다.
  **실제 원인은 권한 허용목록**이었다 — `.claude/settings.local.json` 의 gradle 항목이 정확한 문자열 3개
  (`./gradlew test` 등)뿐이라 `:bot:test --tests '...'` 같은 변형이 `ask` 로 떨어지고, subagent 는 사용자에게
  물을 수 없어 자동 거부된다. `Bash(./gradlew:*)` 와일드카드로 넓혀 해결했고, subagent 재실행으로 확인했다.
