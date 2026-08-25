---
title: 일일리셋 반사실 측정 결과 — 신호 지속성의 가치는 작고 표본에 취약하다
category: query
created: 2026-08-25
updated: 2026-08-25
claim_state: current
verified: 2026-08-25 — `RUN_COUNTERFACTUAL=true ./gradlew :bot:test --tests "*DailyResetCounterfactualTest*"` (커밋 393845e), fixture 수집일은 `bot/src/test/resources/backtest/README.md`
sources:
  - bot/src/test/kotlin/com/trading/bot/engine/DailyResetCounterfactualTest.kt
  - bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt
  - bot/src/test/resources/backtest/README.md
  - https://github.com/yoon627/coin-trading-bot/issues/128
---

# 일일리셋 반사실 측정 결과

GitHub #128 은 운영 데이터에서 `DAILY_RESET` 매도가 **건수 1위인데 수익률 합 −0.31%** 이고, 15/18 건이 재매수됐으며 24h 내 재매수 7건은 평균 **+1.80% 더 비싸게** 샀다고 보고했다(추정 비용 −13.3%p ≈ **1.9%p/건**). 이슈 스스로 "관찰된 상관이지 인과 증명이 아니다"라고 한계를 달았고, 이 페이지는 그 반사실을 백테로 재려 한 결과다.

## 무엇을 쟀나 — 그리고 못 쟀나

기존 `BacktestEngine` 은 청산한 봉에서 진입 평가를 하지 않아 **2봉 공백을 구조적으로 강제**했다. 라이브는 KST 09:00 경계에서 `boughtToday` 가 풀려 공백이 ~0 이다([[trading-engine-loop]]). 그 divergence 를 없애려고 `ReentryMode.LIVE_SAME_BAR` 를 넣었다([[backtest-engine]]).

**하지만 이 설계가 재는 것은 "신호 지속성의 가치"이지 #128 의 헤드라인 비용이 아니다.** D1 봉에서 재진입가 = 청산가(`bar.open`)라 **가격 갭이 구조적으로 0**이기 때문이다. 더 결정적으로, 운영 전략 `volatility_breakout` 의 매수 조건은

```
target = 당일시가 + (전일고 − 전일저) × k
buy if currentPrice > target
```

이라 09:00 시점엔 `currentPrice ≈ 당일시가` 이므로 **k>0 이면 즉시 재매수가 수학적으로 불가능**하다. 라이브의 실제 재매수가 07:32·08:43·14:27 에 +2.88~3.09% 높은 가격에서 일어난 것이 이 식의 귀결이다. 백테는 "봉 D−1 이 돌파였나"를 신호로 봉 D 시가에 재진입하므로 **트리거 시점·가격·빈도가 모두 다르다**.

## 결과 (리셋 1건당 %p, 마켓 균등가중)

현행(`live-reproduction`) 대비 **양수 = 개선**. `maxHoldDays=1` 고정, fixture 200봉 전체.

| 정책 | #128 안 | `volatility_breakout` 하락장 | 상승장 | `combined` 하락장 | 상승장 |
|---|---|---|---|---|---|
| `cooldown-1` | 1안 쿨다운 | −0.093 | +0.020 | 0.000 | −0.142 |
| `cooldown-2` | 1안 쿨다운 | −0.176 | −0.015 | −0.300 | −0.142 |
| `cooldown-3` | 1안 쿨다운 | −0.321 | −0.055 | −0.416 | −0.115 |
| `conditional-reset` | 2안 대상한정 | **+0.470** | **+0.284** | −0.434 | −0.404 |
| `hold-through` | 3안 리셋제거 | +0.263 | −0.004 | −0.507 | +0.177 |

읽는 법:

1. **효과 크기가 전부 ±0.5%p/건 이하**다. #128 이 운영에서 관측한 1.9%p/건의 **1/4 이하**다. 즉 관측된 비용의 대부분은 이 모델이 구조적으로 못 재는 성분(재진입 슬리피지 + 트리거 divergence)에 있다.
2. **쿨다운(1안)은 이 모델에서 개선이 아니다** — 4개 셀 중 3개가 악화다. #128 이 "가장 직접적"이라 본 안인데 신호 지속성 축에서는 근거가 없다.
3. **조건부 리셋(2안)만 운영 전략에서 두 국면 모두 양수**다(+0.470 / +0.284). 다만 `combined` 에선 두 국면 모두 음수라 **전략 의존**이다.
4. **`combined`/하락장은 paired 4마켓(−0.090)과 전체 7마켓(+0.507)의 부호가 반대**다. 마켓 간 분산이 효과를 압도한다 — 방향 주장을 정량 근거로 쓸 수 없다.

## 결론의 강도

**설명적 분석까지만 유효하다.** 국면 2개, 마켓 상관 평균 0.49(BTC/ETH 0.90) → 실효 독립표본 ~2. 판정 기준(estimand·집계단위·유보조건)은 데이터를 보기 **전에** 고정해 테스트 코드에 인코딩했고([[strategy-evolution-expectations]] 의 기대치 규율과 같은 취지), 그럼에도 위 4번 같은 취약성이 남는다.

**처방하지 않는다.** "리셋을 없애라/쿨다운을 넣어라" 는 이 데이터로 말할 수 없다. 말할 수 있는 것은:

- 쿨다운을 우선안으로 둘 근거는 이 축에서 나오지 않았다.
- 조건부 리셋이 운영 전략에서 유일하게 부호가 일관됐다 — 다음에 볼 후보라면 이쪽이다.
- 진짜 비용(슬리피지)을 재려면 **M1 fixture 도입**(현재 `M1ReplayBiasTest` 는 API 실시간 fetch 라 스윕 불가)이나 `TRADING_MAX_HOLD_DAYS` 를 조정하는 **소액 실거래 실험**이 필요하다. 후자는 배선이 이미 돼 있어 코드 0줄이다([[deployment-stack]]).

## 미측정 성분 (결론과 반드시 함께 읽을 것)

1. 재진입 슬리피지 — D1 에서 구조적으로 0.
2. `volatility_breakout` 트리거 divergence — 위 참조.
3. 라이브 신호의 당일 부분 봉 — `MarketDataStore` 가 분봉마다 당일 D1 을 upsert 하므로 09:00:10 신호 window 에 이미 들어 있다([[marketdata-pipeline]]). 백테가 흉내내면 look-ahead 라 불가.
4. fixture 밖 티커 — #128 대표사례 4건 중 SOL·AVAX·ADA 3건이 fixture 에 없다(겹치는 건 DOGE 뿐).
