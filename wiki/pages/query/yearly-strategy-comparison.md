---
title: 운영 8종 1년 전략 비교 — 하락장 1년에서 스윙은 소폭 ±, 적립·단순보유는 반토막
category: query
created: 2026-09-03
updated: 2026-09-03
claim_state: current
verified: 2026-09-03 — fixture `yearly/`(2025-09-03~2026-09-02, 수집 2026-09-03) 위에서 `RUN_YEARLY_COMPARE=true ./gradlew :bot:test --tests "*YearlyStrategyComparisonTest*" --rerun-tasks`(6건 실행/skip 0), 엔진 main `304f0b0`(적립 프로파일 #163 포함). 지표 정의는 본문·`YearlyStrategyComparison.kt` KDoc
sources:
  - bot/src/test/kotlin/com/trading/bot/engine/YearlyStrategyComparison.kt
  - bot/src/test/kotlin/com/trading/bot/engine/YearlyStrategyComparisonTest.kt
  - bot/src/test/resources/backtest/README.md
  - scripts/collect_yearly_fixtures.py
---

# 운영 8종 1년 전략 비교

**질문**: 지금 운용 중인 8종(BTC·ETH·XRP·SOL·DOGE·ADA·AVAX·LINK)의 최근 1년 일봉에서 스윙 전략 9종(라이브 기본 리스크 파라미터, 재진입 2모드), 적립 사다리(기본 5/3/3), 단순보유 중 무엇이 가장 벌었나. 앞 193봉(선택)/뒤 122봉(검증) 분할에서 순위가 유지되나.

## 지표 (세 계열을 한 표에 세우려고 하네스가 재계산)

- **수익률** = 고정 노셔널 예산 대비 순수익률(수수료 왕복 0.1% 차감). 스윙은 `Σ 거래별 net pnl%` — all-in 복리 `totalReturnPct` 는 [[strategy-evolution-expectations]] 가 전략 줄세우기에 금지했고 라이브도 `maxInvestAmount` 고정 노셔널이라 복리가 아니다(참고열로만).
- **MDD** = 봉단위 mark-to-market equity(예산=100)의 peak-to-trough. 엔진의 스윙 MDD 는 청산 시점만 봐 미실현 낙폭을 빼먹는다.
- **노출** = 예산×시간 평균 투입 비율(스윙 `Σ holdDays/거래봉`, 적립 `avgInvestedFraction`, 보유 1). 낙폭이 작은 게 실력인지 노출이 작아서인지 이 열이 가른다.
- 거래수 = 스윙 왕복 / 적립 매수+매도 / 보유 1. 스윙만 거래수 < 8 이면 순위 제외, 동점은 같은 순위. 창은 스윙 워밍업 50봉 기준으로 세 계열 동일(전체 50..364 / 선택 50..242 / 검증 243..364), 각 창은 flat 에서 시작하고 끝에서 강제 청산.

## 결과 — 전체 창(거래봉 315), 8 마켓 중앙값

| 순위 | 후보 | 중앙값 % | 평균 % | 최악 MDD % | 거래수 | 노출 |
|---|---|---|---|---|---|---|
| 1 | combined/live | +4.52 | +2.63 | 20.8 | 125 | 0.04 |
| 2 | volatility_breakout/live | +3.54 | +8.86 | 23.6 | 380 | 0.12 |
| 3 | knee_pullback/live | +1.72 | +0.28 | 13.1 | 108 | 0.04 |
| 4 | macd_cross/live | +1.57 | +1.74 | 12.8 | 103 | 0.03 |
| 5~14 | knee_pullback/legacy · golden_cross(2모드 동점) · combined/legacy · volatility_breakout/legacy · macd_cross/legacy · knee_reversal(2모드) · rsi_bounce(2모드 동점) | −1.6 ~ +0.5 | | 11~24 | | 0.02~0.11 |
| 15~16 | mean_reversion | −6.4 / −6.8 | | 18~19 | | |
| 17~18 | bollinger_bounce | −10.7 | | 23 | | |
| 19 | accumulate/5-3-3 | **−48.1** | −50.0 | 76.5 | 50 | 0.97 |
| 20 | buy-and-hold | **−50.3** | −52.5 | 81.7 | 8 | 1.00 |

마켓별 단순보유: BTC −36 · ETH −43 · XRP −49 · SOL −52 · DOGE −62 · ADA −72 · AVAX −66 · LINK −42 — **8종 전부 하락한 단일 국면**이다.

## 순위 안정성 (선택 193봉 → 검증 122봉)

- 스피어만 ρ = 0.32(양쪽 적격 20 후보 안에서 재순위, 동점은 평균순위; 기술통계 — 유의성 없음, 실효 독립 표본 2~3).
- 선택 창 상위 3(동점 포함 4: `volatility_breakout/legacy`·`volatility_breakout/live`·`golden_cross` 2모드) 중 검증 창 상위 절반(10위 이내) 잔류 3/4 — 무작위 기준선 ≈ 50%.
- 검증 창 1위는 `volatility_breakout/live`(+5.0%, 최악 MDD 22%), 전체 1위였던 `combined/live` 는 검증에서 −0.4%(11위).

## 읽는 법

1. **"가장 많이 번" 후보도 고정 노셔널 기준 연 +3.5~5% 다.** 코인당 예산 10만원이면 연 3,500~5,000원. 하락장에서 노출을 2~12% 로 낮게 유지해 잃지 않은 것이 이 수치의 실체다 — 상승 포착이 아니라 회피.
2. **적립 사다리와 단순보유는 반토막**(−48 / −50). 손절 없이 노출 0.97 로 들고 있는 전략이 −50% 시장에서 얻을 수 있는 결과이며 [[accumulate-ladder]] 의 백테(하락장 −20% 근처)보다 나쁜 이유는 이 1년이 그 fixture 의 하락장(−27~−91% 중앙 −29%)보다 길고 반등이 없기 때문이다. 적립 대비 보유 +2%p 는 "덜 잃는다"의 실측치다.
3. **순위는 안정적이지 않다**(ρ 0.32, 전체 1위가 검증 11위). 1년 한 구간의 순위로 전략을 고르면 과적합이다 — 이 표는 "무엇을 고를까"보다 "이 구간에서 무엇이 어떻게 행동했나"의 기록이다.
4. `volatility_breakout` 은 재진입 모드 차이가 유일하게 큰 전략인데 **방향이 구간마다 뒤집힌다** — 전체 창 live +3.5 vs legacy −0.1, 선택 창은 legacy 7.3 > live 6.1, 검증 창은 live 5.0 vs legacy −0.4. 즉 전체 창의 차이는 뒤 122봉이 만든 것이고 어느 모드가 낫다는 근거가 아니다(다른 전략은 두 모드가 거의 같다). [[reset-churn-measurement]] 가 잰 "신호 지속성의 가치"와 같은 축이다.
5. 스윙의 복리(참고) 열은 고정 노셔널 값과 부호가 갈리는 후보가 있다(`combined/legacy` +0.18 vs −0.06) — 줄세우기에 쓰지 않는 이유가 이것이다.

## 한계 (결론과 함께 읽을 것)

- 국면 1개(하락), 마켓 8개지만 상관이 높아 독립 표본 2~3. 생존편향(운용 중인 종목만).
- 스윙 백테는 봉당 1회·intrabar 근사(`IntrabarExitModel`), 슬리피지 0. 라이브는 10초 tick.
- 122봉 검증 창은 창 끝 `END` 강제 청산 1건의 비중이 크고, 선택/검증 경계에 걸친 포지션은 두 창에서 다르게 처리돼 전체 ≠ 선택+검증.
- 검증 창에서 스윙 4후보(knee_pullback 2모드·bollinger_bounce 2모드)는 8마켓 중 1마켓이 0거래다 — 그 0% 가 −50% 시장에서 중앙값을 위로 당긴다(보고서 `0거래 마켓` 열).
- 적립은 프로덕션 OFF 인 기본값 5/3/3 의 백테이지 라이브 관측이 아니다.
- 파라미터 스윕은 하지 않았다(라이브 기본값 고정).

## 재현

```sh
python3 scripts/collect_yearly_fixtures.py --write   # END_DATE 이후에만(형성 중인 봉 방지)
RUN_YEARLY_COMPARE=true ./gradlew :bot:test --tests "*YearlyStrategyComparisonTest*" --rerun-tasks
cat bot/build/reports/yearly-strategy-comparison.md
```
