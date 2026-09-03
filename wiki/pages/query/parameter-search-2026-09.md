---
title: 1년 fixture 파라미터·아이디어 탐색 — 사전고정 게이트로는 라이브를 이기는 설정을 찾지 못했다
category: query
created: 2026-09-04
updated: 2026-09-04
claim_state: current
verified: 2026-09-04 — fixture `yearly/`(2025-09-03~2026-09-02, sha256 앞 16 `8d4984a98d0b40b0`)·`bull/`·`bear/` 위에서 `RUN_STRATEGY_SEARCH=true RUN_SEARCH_NULL=true ./gradlew :bot:test --tests "*StrategySearchRunTest*" --rerun-tasks`(JDK 21.0.9). 판정 기준은 실행 전에 커밋된 사전고정 plan(`391118e`)
sources:
  - bot/src/test/kotlin/com/trading/bot/engine/StrategySearchGrid.kt
  - bot/src/test/kotlin/com/trading/bot/engine/StrategySearchGates.kt
  - bot/src/test/kotlin/com/trading/bot/engine/StrategySearchStageA.kt
  - bot/src/test/kotlin/com/trading/bot/engine/StrategySearchStageB.kt
  - bot/src/test/kotlin/com/trading/bot/engine/RandomEntryStrategy.kt
---

# 1년 fixture 파라미터·아이디어 탐색

**질문**: 운영 8종 최근 1년 일봉에서 지금 라이브(`combined`, TP5/SL5/트레일2/arm3/hold1)보다 나은 설정이 있나. 진입(`kValue`)·청산 파라미터 51,480 좌표와, 라이브에 없는 아이디어 3종(레짐 필터 기간·ATR 가변 손절익절·부분 익절)을 같은 기준으로 판정한다.

**답**: **사전고정 기준으로는 없다.** Stage A 는 22,952 고유 행동 중 3건이 게이트를 전부 통과했지만 null 대조군의 max-statistic 을 넘지 못했고, Stage B 는 75셀 전부 첫 게이트에서 탈락했다.

## 왜 "1등"이 아니라 게이트인가

[[yearly-strategy-comparison]] 이 이미 잰 사실 — 이 fixture 에서 선택창→검증창 순위상관 ρ=0.32, 전체 1위(`combined/live`)가 검증창 11위. **한 구간의 최고점을 고르는 행위 자체가 과적합**이라 이 작업은 "1등 찾기"가 아니라 사전고정 게이트 통과 여부를 묻는다. 기준·그리드·seed 는 결과를 보기 전에 커밋했다(사전고정 plan, `391118e`).

관측치는 마켓별 **paired delta**(후보−baseline)의 중앙값과 양수 마켓 수다. `median(후보) − median(baseline)` 은 마켓 효과가 섞여 부호까지 갈릴 수 있다.

| 게이트 | 기준 |
|---|---|
| G1 선택창(193봉) | paired delta 중앙 ≥ +2.0%p **그리고** 양수 마켓 ≥ 6/8 |
| G2 검증창(122봉) | 중앙 ≥ 0 그리고 양수 ≥ 5/8 (8마켓에선 마켓 수 조건이 중앙값 조건을 흡수한다) |
| G3 plateau | 고유 좌표 이웃 ≥70% 가 G1 통과 |
| G4a bull / G4b bear | 각 국면 중앙 ≥ −1.0%p |
| G5 표본 | 창별 거래수 ≥ 8, 0거래 마켓 ≤ 1 |
| G6 낙폭 | MDD 중앙 delta ≤ +2.0%p, 최악 ≤ baseline×1.5 |
| G7 비용 | 수수료 2배·4배에서도 G1 유지 |

임계값(2.0%p·70%·1.5배)은 **운영상 최소효과·리스크 허용치**이지 통계적 유의 임계가 아니다 — 마켓 상관 0.49, 실효 독립 표본 2~3.

## Stage A — 진입·청산 스윕 (51,480 좌표)

`arm ≤ trail/(1−trail/100)` 인 조합은 트레일링 발동식상 전부 같은 동작이라 그리드에서 뺐다(Cartesian 93,600 → 51,480, −45%). 실행 후 거래 지문으로 접으니 **고유 행동 22,952**.

| 게이트 | 여기서 탈락 |
|---|---|
| G5 | 974 |
| G1 | 21,513 |
| G3 | 330 |
| G6 | 132 |
| G2·G4a·G4b·G7 | 0 |

살아남은 3건은 전부 같은 행동 계열이다 — `combined` **k0.3 / SL 7% / 트레일링 1.5%(arm 0) / hold 1 / 필터 off / TP 8~off**. 즉 *"5% 고정 익절로 끊지 말고 타이트한 트레일링으로 끌고 가되 손절은 넓게"*.

| 지표 | 값 |
|---|---|
| 선택창 paired 중앙 | **+4.92%p** (양수 7/8) |
| 검증창 | +2.13%p (5~6/8) |
| bull(시간 독립) | +6.5 ~ +7.0%p |
| bear(구간 중복 — 독립 증거 아님) | +2.60%p |
| MDD 중앙 delta / 최악 | +0.30%p / 12.6%p |
| 거래수·노출 | 118~119 · 0.08 (baseline 84 · 0.04) |
| 승률 / 실측 손익분기 승률 | 65% / 48~49% |
| 수수료 2배·4배 | +4.51 / +3.71%p |

## null 대조군이 판정을 뒤집는다

진입을 `(seed, market, 봉)` 순수 해시로 무작위화하고 **같은 exit 그리드**를 뒤진다(seed 91개, 진입 확률 = baseline 원시 신호 발생률 0.060). 상태 있는 RNG 를 쓰면 exit config 마다 진입 시점이 달라져 대조군 정의 자체가 무너진다.

| 변종 | 기준 | 1건 이상 통과한 seed | 평균 통과 수 | max-stat 95% | 13배 폭 환산 |
|---|---|---|---|---|---|
| **A (주 판정)** | 실제 라이브 `combined` | **0.0%** | 0.00 | 7.84%p | 13.60%p |
| B (진단용) | 같은 무작위 진입 | 86.8% | 262.78 | 15.64%p | 16.49%p |

**변종 B 는 계기 고장이다.** 후보·기준이 모두 무작위면 기준 자체가 형편없고 변동이 커서 delta 분포가 부푼다 — 잡음이 실제 탐색보다 500배 잘 통과한다(6.6% vs 0.013%). 그 분위수를 임계로 쓰면 사과-오렌지 비교라 A 만 판정에 쓴다.

A 의 결과는 두 방향을 동시에 가리킨다.

1. **게이트 스택은 무르지 않다** — 잡음 91회 탐색에서 통과 후보가 **한 건도** 나오지 않았다(실제 탐색은 3건).
2. **그러나 사전고정한 max-statistic 기준은 못 넘었다** — 잡음 탐색이 선택창에서 우연히 도달하는 최고 delta 의 95% 분위가 7.84%p(탐색폭 환산 13.60%p)인데 실제 후보는 4.92%p 다.

둘이 갈리는 이유는 재는 대상이 달라서다. max-stat 은 G1(선택창 효과크기) **하나만** 보고, 통과율은 G2·G3·G6 까지 요구한다. 사전고정 문구가 max-stat 기준이므로 **그것을 따른다** — 결과를 본 뒤 더 유리한 기준으로 갈아타면 사전고정이 무의미해진다.

## Stage B — 신규 아이디어 (75셀, 통과 0)

Stage A 와 같은 지표·게이트(G3 제외 — 축당 값이 3개 미만이라 이웃이 정의되지 않는다). 기준은 라이브 현행.

- **레짐 필터**(현재가 < MA 면 매수 차단): MA10 −0.53 · MA20 0.00 · **MA50 −2.20%p**. 도움이 안 된다. 라이브 매수 경로에 이 필터가 없는 현 상태를 바꿀 근거가 없다.
- **ATR 가변 손절·익절**(트레일링 끈 상태에서 측정): 선택창 전 셀 ≤ 0. `hold 365` 계열은 −4 ~ −16%p 에 MDD 20~48%p 로 크게 나쁘다. 보유를 늘리면 노출이 0.04 → 0.2~0.4 로 뛰고 그 노출이 하락장에서 그대로 손실이 된다.
- **부분 익절**: 최고가 `2%@50% / TP 12%~off / h1` 로 **+1.21%p(6/8)**, 검증창 +0.18. 방향은 Stage A 생존자와 같지만(일찍 끊지 말 것) G1(+2.0%p)에 못 미친다.

## 읽는 법

1. **"더 나은 알고리즘을 못 찾았다"가 아니라 "이 표본·이 공간에서 사전고정 기준으로는 못 가린다"** 이다. 1년 8마켓의 실효 독립 표본이 2~3 이라 +4.9%p 짜리 효과는 잡음과 분리되지 않는다.
2. 그럼에도 **두 Stage 가 같은 방향을 가리켰다** — Stage A 생존자(트레일링 1.5·TP 사실상 off)와 Stage B 최고 셀(부분익절 2%@50% + TP 넓힘) 모두 "5% 고정 익절로 일찍 끊는 것"을 완화하는 쪽이다. 가설로 삼을 만하지만 이 데이터로 확정할 수는 없다.
3. **라이브 파라미터는 바꾸지 않았다.** 리스크를 키우는 전이는 사람 승인이 필요하고([[strategy-evolution-expectations]]), 지금 근거는 그 승인을 요청할 수준이 아니다.
4. 다음으로 유효한 것은 그리드를 더 뒤지는 게 아니라 **표본을 늘리는 축**이다 — 전향적 관찰(소액 카나리아·페이퍼), 국면 fixture 추가, 또는 M1 fixture([[reset-churn-measurement]] 후속).

## 한계

- 국면 1개(하락)에 마켓 8개, 상관 0.49 → 실효 독립 표본 2~3. **생존편향**(1년을 살아남아 운용 중인 종목)은 제거 불가.
- bull 만 시간 독립 holdout 이고 bear 는 yearly 구간에 포함된다 — bear 열은 robustness 표기이지 독립 증거가 아니다.
- 엔진 한계 그대로: 단일 티커·단일 포지션·슬리피지 0·봉당 1회·intrabar 근사([[backtest-engine]]). 포지션 사이징·포트폴리오·마켓 선택은 이 엔진으로 잴 수 없다.
- 부분 익절은 한 거래로 **가중 합성**한 근사다. 노출은 부분 체결 시점을 기록하지 않아 `1 − f/2` 로 근사한다.
- null 대조군의 진입 확률은 baseline 의 **원시 신호 발생률**로 맞췄다 — 실현 진입 수는 exit 설정에 따라 달라지므로 근사다.

## 재현

```sh
RUN_STRATEGY_SEARCH=true RUN_SEARCH_NULL=true \
  JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-21.0.9/Contents/Home \
  ./gradlew :bot:test --tests "*StrategySearchRunTest*" --rerun-tasks
cat bot/build/reports/parameter-search.md
```

`RUN_SEARCH_NULL` 을 빼면 Stage A·B 만 33초에 돈다(null 은 seed 91개라 ~10분). 벤치마크(`RUN_SEARCH_BENCH=true`)는 **합성 캔들만** 쓴다 — fixture 성과를 사전고정 전에 보면 그리드 선택이 결과에 오염된다.
