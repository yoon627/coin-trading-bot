---
title: 완결 봉 MA/RSI 변형 — combined 의 MA·RSI 에서 진행 중 당일봉을 빼면 5분봉에서 나아지지 않는다(3셀 후보 0, 규모 맞춘 null 유효), 현행 부분봉 포함 유지
category: query
created: 2026-09-23
updated: 2026-09-23
claim_state: current
verified: 2026-09-23 — `RUN_COMPLETED_BAR=true BACKTEST_CACHE_DIR=~/.cache/coin-trading-bot/backtest-cache ./gradlew :bot:test --tests "*CompletedBarSignalTest*" --rerun-tasks`(JDK 21.0.9) → `bot/build/reports/completed-bar-indicators.md`. 규칙은 결과 전 커밋(`6bf68c7`, 하네스 KDoc). 배관: 기준 핀 240/15/5분 1,058/+235.93 · 1,659/−124.84 · 1,767/−219.36 재현, 항등 셀·전부-false null(실제 조회 경로) = 기준, 셀이 계산한 완결 값 vs 사전계산 66,908건 불일치 0, (마켓, 진입일) 유일
sources:
  - bot/src/test/kotlin/com/trading/bot/engine/CompletedBarSignalTest.kt
  - bot/src/test/kotlin/com/trading/bot/engine/CompletedBarCombined.kt
  - bot/src/test/kotlin/com/trading/bot/engine/LiveSemanticsArm.kt
  - common/src/main/kotlin/com/trading/common/strategy/CombinedStrategy.kt
  - common/src/main/kotlin/com/trading/common/strategy/Indicators.kt
---

# 완결 봉 MA/RSI 변형

## 질문

#27 감사(2026-06, 일봉 백테 시절)는 "MA/RSI/cross 계열이 미완성 당일봉을 포함해 계산된다 — `drop(1)` 로 제외"를 Structural 항목으로 남겼다.
2026-09-23 코드 확인으로 **전제가 바뀌었다**: 라이브(`CombinedStrategy` → `Indicators.isMaUptrend`·`calculateRsi`)는 window 맨 앞의 진행 중 당일 D1(close ≈ 현재가)을 포함하고,
판정 계기 `LiveSemanticsArm`(5분봉)도 **직전 일중봉까지 누적한 당일 부분봉**을 맨 앞에 넣어 같은 의미론으로 돈다(look-ahead 없음). 라이브↔계기 불일치(결함)가 아니라 설계 선택이다.
남은 질문: **MA5>MA20·RSI14∈[30,70] 을 전날까지의 완결 봉만으로 계산하면 거래당 손익이 나아지는가.**

## 방법 (결과 전 커밋 `6bf68c7` — `CompletedBarSignalTest` KDoc)

- 셀 3: MA 만 완결(`window.drop(1)`) · RSI 만 완결 · 둘 다. 돌파선은 항상 당일 시가(부분봉 포함). 기준 = 현행 라이브(k0.5/TP5/SL5/트레일1.5/arm0/h1).
- 계기·통계량은 선행 판정과 같다: 10창, frame 1,500일, **주 판정 5분봉**, 15분 수렴, 진입일 단위 기여, 이동블록 5일·B=20,000·단일단계 maxT, 비관 트레일링 브래킷.
- **null 을 셀 규모에 맞췄다.** 완결 조건은 전날까지 일봉만의 함수라 (마켓, 거래일) 게이트 `C(m,d)` 다. 판정 null = 셀이 바꾸는 지표 자리에서 부분 조건을
  **전이일 마스크** `D = [C(d) ≠ C(d+1)]`(그날 종가까지 넣으면 완결 조건이 뒤집히는 날)를 k = 17..74 거래일 순환 이동해 XOR 한 것 — 뒤집는 비율·군집은 보존하고 가격 정렬만 깬다.
  plan-reviewer 가 처음 설계(완결 조건 값 자체를 이동)를 기각했다: 그 null 은 진입을 셀의 5~8배 바꿔 "필터 정보를 없애면 이기는가"를 재므로 셀과 무관하게 계기 무효가 날 공산이 컸다([[external-signal-gate-2026-09]] 의 무정보 게이트 7.7% 통과). 그 설계는 진단 null 로만 남겼다.
- 후보 = 5분 통과 ∧ 15분 수렴 ∧ ≥ 0.10%p/기준거래 ∧ 기여 1위 이름 제외 단측 하한 > 0 ∧ 노출 정규화 부호 ∧ 비관 브래킷 통과 ∧ null 유효(N0 ≤ 6/60).
- 결과 전 예측: 돌파 시점의 부분봉 close 는 높다 → 현행은 MA5 가 올라가 MA 조건이 잘 통과하고 RSI 는 올라가 >70 거부가 는다. 따라서 MA 완결은 진입 감소, RSI 완결은 진입 추가, BOTH ≈ 합. 검정력 낮음(후보 0 이 가장 유력).

## 결과 — 통과 0 · 후보 0 · null 유효

| 셀 | 5분 진입 N (기준 1,767) | 격차 Σ%p | /기준거래 | 동시 95% 하한 | marginal p | 15분 /기준거래 | 비관 격차 |
|---|---|---|---|---|---|---|---|
| MA 완결 | 1,574 | −8.4 | −0.005 | −95.9 | 0.57 | +0.006 | +18.2 |
| RSI 완결 | 1,913 | −64.1 | −0.036 | −127.9 | 0.98 | −0.023 | −100.7 |
| 둘 다 | 1,717 | −71.9 | −0.041 | −179.6 | 0.91 | −0.016 | −80.8 |

판정 null N0 = **0 / 60**(한도 6) → 계기 유효. null 이 바꾼 진입 수(대칭차 MA ≈ 195·RSI ≈ 150·BOTH ≈ 340)는 셀(283·146·426)과 같은 규모였다 — 규모 맞춤이 의도대로 작동했다.

| 5분 진입 집합 분해 | 제거 N · 기준 Σpnl | 추가 N · Σpnl | 같은 날 다른 체결 N · Σpnl 변화 |
|---|---|---|---|
| MA 완결 | 238 · −8.3 | 45 · −23.4 | 10 · +6.7 |
| RSI 완결 | 0 | 146 · **−50.6** | 23 · −13.5 |
| 둘 다 | 238 · −8.3 | 188 · −71.4 | 32 · −8.8 |

1. **예측한 방향은 전부 맞았고 효과는 없거나 음수다.** MA 완결이 지운 238건은 합 −8.3(건당 −0.03)으로 손익 중립이다 — 부분봉 MA 가 추가로 통과시키는 진입은 나쁘지도 좋지도 않다.
2. **현행 부분봉 RSI 는 실제로 나쁜 진입을 거른다.** RSI 완결이 되살린 146건(현행이 "부분봉 RSI > 70" 으로 거부하던 진입)은 합 −50.6, 건당 −0.35%p 다. 돌파 직후 RSI 가 과열인 날의 진입은 손해라는 뜻이다.
   이 방향(현행이 낫다)은 사전고정 검정의 대상이 아니다 — 개선 여부만 판정했다. 근거로 읽을 수 있는 것은 "완결로 바꿀 이유가 없다" 까지다.
3. 불일치율(그 지표가 그날 처음 평가된 봉에서 부분 ≠ 완결)은 MA 5.0%·RSI 7.1% — 돌파일의 소수만 갈린다. 교란이 작아 검정력은 원래 낮았다.
4. 진단 null(완결 조건 값을 이동 — 필터 정보 무작위화)은 MA 셀에서 20 seed 중 4번 통과했다. 대칭차가 1,750~1,940 으로 진입의 대부분을 바꾸는 교란이라, [[external-signal-gate-2026-09]] 와 같은 "음수 기준선 위의 덜 거래" 효과다. 판정에는 쓰지 않는다.

## 이것이 뜻하는 것

- **#27 의 "미완성 당일봉 신호 제외"는 닫는다.** 라이브·계기 일치가 코드로 확인됐고, 완결 봉으로 바꾸는 세 변형 모두 5분봉에서 개선이 없다. 라이브 변경 없음.
- 진입 조건 축의 세 번째 소진이다([[breakout-entry-filters-2026-09]] 진입 필터 9셀, [[external-signal-gate-2026-09]] 외부 게이트 13셀에 이어). 5분 기준선 −219%p 는 진입 필터·게이트·지표 창으로는 고쳐지지 않는다.
- **규모 맞춘 null 이 쓸 만하다**: 셀이 "기준 조건의 작은 교란"일 때는 조건 값을 통째로 이동하는 null 이 아니라, 셀이 조건을 뒤집는 날(전이일)을 이동하는 null 이 대조 대상에 맞다. 다음 교란형 사전고정도 이 방식을 쓸 수 있다(`CompletedBarGate`).

## 한계

- 라이브 window 는 최대 60봉(`TradingEngine.MAX_DAILY_CANDLE_LOOKBACK`), 계기는 50봉이라 RSI Wilder 누적 길이가 다르다(완결이면 59 vs 49봉). 모든 셀에서 RSI 창이 50→49봉으로 줄어드는 작은 혼입이 있다.
- 5분 봉 시작 시점의 부분봉은 라이브 10초 tick 부분봉보다 늦다 — 계기의 부분 조건은 라이브보다 약간 보수적이다.
- 판정은 "완결이 더 나은가" 단측이다. 2번 문장(현행 RSI 가 거르는 진입이 손해)은 진입 집합 분해의 관측이지 검정된 주장이 아니다.
- 비관 브래킷 기준선(5분 −458.21)은 선행 핀이 없어 보고만 했다.

## 재현

```bash
RUN_COMPLETED_BAR=true BACKTEST_CACHE_DIR=~/.cache/coin-trading-bot/backtest-cache \
  ./gradlew :bot:test --tests "*CompletedBarSignalTest*" --rerun-tasks   # → bot/build/reports/completed-bar-indicators.md
./gradlew :bot:test --tests "*CompletedBarCombinedTest*"                 # 변형 의미·사전계산·마스크 이동(합성)
```
