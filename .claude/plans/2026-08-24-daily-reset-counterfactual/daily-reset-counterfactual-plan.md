---
title: daily-reset-counterfactual — 백테 재진입 모델 보정 후 #128 일일리셋 반사실 측정
status: in_progress
started: 2026-08-24
updated: 2026-08-26
---

# Goal

`BacktestEngine` 이 라이브의 "일일 리셋 후 0공백 재매수"를 재현하도록 재진입 모델을 보정하고,
그 위에서 **세 대조군**(hold-through / live-reproduction / cooldown-N)을 실데이터 fixture 로 측정해
GitHub #128 개선안 결정 근거를 만든다. **라이브 전략 코드는 이번 범위가 아니다.**

**측정 대상은 "신호 지속성의 가치"로 좁힌다** (R3 반영, 사용자 확정 2026-08-25) — 보유상한이 걸렸을 때
계속 들고 가는 것과, 신호를 재평가해 신호가 유지될 때만 남는 것의 차이. **#128 이 헤드라인으로 든
재진입 슬리피지(+1.80%)는 이 설계로 측정할 수 없다** — D1 에서 재진입가 = 청산가(`bar.open`)라 가격 갭이
구조적으로 0이기 때문이다. 리포트에 "측정 못 하는 성분"으로 명시한다.

# Progress

- 2026-08-24 Explore 완료. 라이브 메커니즘·백테 divergence 확정, 범위 2건 사용자 확정, draft plan 작성·커밋(e7e78ae).
- 2026-08-24 codex plan 리뷰 완료(Critical 2 / Major 5 / Minor 4). Claude plan-reviewer·architecture-reviewer 는 세션 한도로 조기 종료 → 재실행 예정.
- 2026-08-25 리뷰 반영해 plan 전면 개정 (대조군 3팔·재진입 가격모델 한정·기본값 legacy 전환·사전 판정기준).
- 2026-08-25 **Claude plan-reviewer(+codex medium) 결과 도착·반영**(`3a515e7`·`730cff1`). P0 3건 중 2건은
  위 전면 개정이 이미 선반영. 신규 3건 R1(신호 window look-ahead 구현 함정)·R2(라이브 당일 봉 전제 오류,
  ✅코드 확인)·R3(D1 은 재진입 가격 갭을 구조적으로 0으로만 표현) → `# Review Disposition` 참조.
- 2026-08-25 **사용자 확정**: R3 을 받아들여 Goal 을 "신호 지속성의 가치"로 좁히고 그대로 진행.
  구현은 Claude(메인)가 맡는다(§9). baseline `:bot:test`+`:common:test` BUILD SUCCESSFUL — 사전 실패 없음.
- 2026-08-25 **TDD Red**(`a5a09e7`) — `BacktestReentryTest` 7종 중 5종이 의도한 사유로 실패
  (legacy 2봉 공백이 그대로라 `expected: <52> but was: <54>` 등). legacy 보존 가드·A2b 는 예상대로 통과.
  전체 726 tests 중 실패가 정확히 그 5개뿐이라, `BacktestConfig` 필드 추가가 기존 테스트를 하나도
  깨지 않음을 확인(**A3b legacy 보존 증거**).
- 2026-08-25 **구현·Green**(`25750aa`) — `simulateTrades` 에 재진입 예약 상태머신(`reentryDueAt`/
  `reentryDoneAt`) 추가, `processExit` 이 청산 사유를 반환하고 `processEntry` 가 진입 여부를 반환하도록 변경.
  `:bot:test`+`:common:test`+`compileKotlin` BUILD SUCCESSFUL, 신규 7종 `tests=7 failures=0`.
  **A3c** parity 가드에 `reentryMode`·`reentryCooldownBars` 명시 assert + 이유 주석 추가.
- 2026-08-25 code-reviewer(+codex) 검토 중.
- 2026-08-26 **최종 검증**(메인, 강제 재실행) — 내가 검증한 적 없던 커밋 `905fbac`·`902d781`·`393845e`·`31d63b8`
  포함 상태에서 `:bot:test :common:test compileKotlin --rerun-tasks` **BUILD SUCCESSFUL**,
  wiki 3종 clean / 30 pages / pass=10 fail=0. (직전 캐시 실행이 `8 up-to-date` 로 테스트를 건너뛰어 재실행함.)
- 2026-08-26 code-review 처분 + simplify 체크 완료 → `# Review Disposition`. **A1~A6 전 항목 증거 충족.**
- 2026-08-25 plan-reviewer 2차(gap) 처분 → 측정전략 `volatility_breakout` 고정(운영 실측), estimand 를 이벤트 단위로, 지표 규칙·판정기준 확정.
- 2026-08-25 **A3b 실증**: base `db48763` 와 HEAD 를 fixture 12 × 전략 2 로 돌려 trade 단위 바이트 동일 확인 → `BacktestLegacyGoldenTest` 로 영구 고정(`905fbac`).
- 2026-08-25 **버그 발견·수정**(`902d781`): 재진입 실패 시 그 봉의 통상 진입 기회를 삼켜 `cooldown-N` 이 legacy 보다 계통적으로 덜 거래했다. `cooldown-2 ≡ legacy` 를 실 fixture 로 가두는 회귀 추가. code-review 가 독립적으로 같은 결함을 Critical-1 로 확인(모델 2000/2000).
- 2026-08-25 `conditional-reset` 팔(#128 2안) + 측정 하네스 완성(`393845e`), 결과를 wiki `reset-churn-measurement` 로 영속화(`31d63b8`).
- 2026-08-26 code-review 반영(`e412bba`): dead guard 제거·config 검증·테스트 구멍 3종(재진입 실패 경로·window 길이·TP 짝). A4d/A3d 보강(`6d661d7`).
- 2026-08-26 **최종 검증**: 736 tests / 0 failures, wiki 3종 clean, 측정 결과 정리 전후 불변.

# Next

측정·검증·문서까지 끝났다. 남은 것은 머지 절차뿐이다.

1. PR 생성 → 머지 (머지 시점에 `status: done`)
2. #128 에 결과 코멘트 (wiki `reset-churn-measurement` 링크 + 핵심 3줄)
3. 후속 이슈 2건 등록 — (a) 조건부 리셋 소액 카나리아 (b) M1 fixture 도입해 슬리피지 측정

# Decisions

1. **접근 = 백테 재진입 모델 보정 후 측정** (사용자 확정 2026-08-24).
   근거: `BacktestEngine.simulateTrades` 가 `if (position) processExit else processEntry` 라
   청산 봉에서 진입 평가를 하지 않는다 → 청산 봉 `i` → 신호 `i+1` → 체결 `i+2` = **2봉 강제 공백**.
   라이브는 09:00 리셋 매도 후 ~10초 뒤 재매수(#128 KRW-SOL 0.0h) = **0공백**.
   현 백테 baseline 은 이미 #128 1안(쿨다운)에 가까워 이슈가 지목한 비용을 측정할 수 없다.

2. **종점 = 반사실 결과까지** (사용자 확정 2026-08-24).
   라이브 변경(쿨다운 도입 / 리셋 대상 한정 / 리셋 제거)은 결과를 보고 별도 worktree 에서 결정·구현.

3. **대조군은 3팔** (codex C1/M1 반영 — 개정).
   기존 draft 의 "쿨다운 × maxHoldDays 그리드" 는 *매도 자체의 효과*와 *재진입 지연 효과*를 분리하지 못했다.
   | 팔 | 정의 | 필요한 기계장치 |
   |---|---|---|
   | `hold-through` | 일일리셋 청산 없음. 보유 지속, 나머지 게이트만 작동 | **없음** — `maxHoldDays=999` 로 기존 엔진 그대로 |
   | `live-reproduction` | 리셋 청산 + 0공백 재진입 (= 현 라이브) | **신규** (Decision 4) |
   | `cooldown-N` | 리셋 청산 + N봉 재진입 차단 (N=1,2,3) | 신규 (같은 기계장치) |
   | `conditional-reset` | **수익 중일 때만** 리셋 청산, 손실이면 보유 유지 | 신규 (G2 지적 — #128 2안) |
   `maxHoldDays` 는 primary 비교에서 **라이브 값 1 로 고정**. maxHoldDays 스윕은 별도 민감도 분석으로 분리한다(교락 회피).

4. **재진입 모델 = TIME_EXIT 한정 same-bar 재진입** (codex C2 반영 — draft 에서 축소).
   - **적용 대상**: `TIME_EXIT`(= 라이브 `DAILY_RESET`) **만**. 청산가가 `bar.open` 이라 look-ahead 없이 확정된다.
   - **제외 대상**: `STOP_LOSS`/`TAKE_PROFIT`/`TRAILING_STOP`. `IntrabarExitModel` 이 내는 값은 실제 체결가가 아니라
     **게이트 임계가**이고, 청산 시각·재진입 시각을 D1 봉에서 알 수 없다. 봉의 high/low 를 본 뒤 같은 봉에 재진입하면
     미래 정보 사용이다. 이들은 기존 규약(다음 봉 신호 → 그 다음 봉 시가)을 유지한다.
   - **신호 window**: **직전 봉(`D-1`) 종가까지** — 봉 `D` 를 제외한다.
     ⚠️ **구현 함정(R1)**: `BacktestEngine.kt:91` 의 기존 `window` 는 `subList(max(0, i-(MIN_CANDLES-1)), i+1)` 로
     **봉 `i` 를 포함**한다(현행은 체결이 `i+1` 시가라 무해). same-bar 재진입에 그 변수를 그대로 재사용하면
     봉 D 의 종가·고저를 보고 봉 D 시가에 사는 look-ahead 다 → 재진입 신호는 `subList(..., i)` 로 별도 계산.
     ⚠️ **라이브와의 divergence(R2, ✅확인)**: "라이브도 09:00 엔 당일 봉 미형성"은 **사실이 아니다**.
     `loadStoreDailyCandles`(`TradingEngine.kt:370-375`)가 진행 중 당일 봉을 포함해 반환하고
     `MarketDataStore`(`:51`)가 분봉마다 그 봉을 upsert 한다. 거래량 조건 전략(`MeanReversion.kt:36`
     `currentVolume >= avgVolume * 0.8`)에서 신호가 갈릴 수 있다. 백테가 부분 봉을 흉내내는 건 look-ahead 라
     불가 — **알려진 한계로 A5·리포트에 명시**한다.
   - **체결가**: `bar.open` (= 청산가). 라이브가 ~10초 뒤 재매수하므로 근사 타당.

5. **재진입한 포지션은 같은 봉의 청산 게이트를 받는다** (내 추가 발견 — codex 미지적).
   통상 진입 규약은 "체결 봉 `X`.open → 봉 `X` 에서 게이트 평가"다(`processEntry(fillIndex=i+1)` → 다음 반복 `holdDays=0` → intrabar 평가).
   same-bar 재진입에서 `buyIndex=D` 로만 두고 넘어가면 다음 반복이 `holdDays=1` → 곧장 `atHoldLimit` TIME_EXIT 이라
   **봉 D 의 intrabar 게이트가 새 포지션에 한 번도 평가되지 않는다** → 리셋 churn 포지션만 손절·익절 보호가 사라져 편향.
   라이브는 09:00 재매수 후 당일 장중 청산이 가능하므로(`boughtToday` 는 이미 해제됨) 봉 D 게이트를 평가해야 맞다.
   look-ahead 없음: 진입 신호(`D-1` 종가)와 체결가(`D`.open) 모두 봉 D 의 high/low 사용 **전에** 확정된다.
   **✅ 봉 경계 검증 (2026-08-25)**: Upbit 일봉의 `candle_date_time_kst` 가 `T09:00:00` 이다
   (`bot/src/test/resources/backtest/bear/KRW-BTC.json` 실측 — 전 레코드 09:00).
   즉 봉 `D` 는 **09:00 KST day D → 09:00 KST day D+1** 구간이고 `openingPrice` 가 곧 일일리셋 시각이다.
   `TradingDay` 의 09:00 경계가 Upbit 일봉 경계와 같게 설계돼 있어, 봉 D 시가에 재진입한 포지션은
   봉 D 의 high/low **전 구간을 실제로 겪는다** → "겪지 않은 저점으로 손절되는" 팬텀 문제는 성립하지 않는다.
   같은 이유로 리셋 churn 포지션은 라이브에서도 정확히 open-to-open 24h 보유라 백테와 1:1 대응한다
   (최초 진입만 라이브가 장중 체결이라 ~19h — 기존 백테 divergence, 이번 범위 밖).
   - **봉당 재진입 ≤ 1회** 로 제한(라이브 `boughtToday` 등가). 무한 churn 방지 가드.
   - 재진입 포지션이 봉 D 에서 청산되면 그 뒤는 기존 규약(다음 봉 신호)으로 복귀 — Decision 4 와 일관.

6. **`reentryMode` 기본값 = `LEGACY_NEXT_BAR` (기존 동작 보존)** — draft Decision 5(기본 0) **철회**.
   철회 근거(코드 실측):
   - `M1ReplayBiasTest.kt:66` 가 `BacktestConfig()` 를 쓴다 → 기본값을 바꾸면 D1 trade set 이 달라져
     **편향 측정의 모집단 자체가 바뀐다**. 그 테스트의 "진입 변수 격리" 전제가 깨진다.
   - `ParameterSweepTest.kt:70` baseline, `KneeStrategyComparisonTest.kt:45` `liveDefault` 도 기본값 상속.
   - `/backtest` REST 에서 파라미터 생략한 기존 호출자의 결과가 전부 달라진다(public 계약 변경).
   - 이번 범위는 *측정*이지 public API 의미 변경이 아니다(Decision 2).
   대신 **침묵하지 않게** 한다: `BacktestEngineTest.config defaults match live trading defaults` 에
   `reentryMode` 명시 assert + 이유 주석을 추가한다(`useMarketFilter` 선례와 동일 형식) — "라이브와 다르게 두는 중"임을 CI 가 들고 있게.
   기본값 전환은 라이브 변경을 결정하는 후속 worktree 에서 함께 판단한다.

7. **세 가지를 분리해 선언한다** (codex M3 — 순환논증 회피).
   - *구현 검증*: 백테가 라이브 순서를 재현하는가 (A1~A3)
   - *효과 측정*: 팔 간 paired 차이는 얼마인가 (A4)
   - *판정 기준*: 어떤 효과크기·불확실성에서 정책 변경을 고려하는가 (A5, 사전 고정)
   "라이브가 0공백이므로 그게 baseline" 은 **재현성 결정**이지 0공백이 옳다는 근거가 아니다.

8. **결론 대상은 DAILY_RESET churn 으로 한정** (codex M5).
   라이브는 `boughtToday` 가 09:00 에 풀리므로 SL/TP/트레일링 청산 후에도 같은 거래일 재진입이 가능하다.
   그러나 Decision 4 대로 D1 백테는 그 경로를 모델링하지 않는다 → 해당 표본은 **"측정하지 않음"으로 명시 제외**하고
   결론에 넣지 않는다. 관찰만 하고 판단은 별도 이슈(`# Deferred`).

9. **측정 전략을 고정한다 — primary `volatility_breakout`, secondary `combined`** (G1 blocker 반영 2026-08-25).
   근거: 운영 배포는 `TRADING_STRATEGY=volatility_breakout`(`deploy/vultr/.env` 실측)이고 `combined` 는
   코드 기본값(`TradingProperties.strategy`)일 뿐이다. 기존 하네스 두 개가 `combined` 하드코딩이라
   (`ParameterSweepTest.kt:70`, `M1ReplayBiasTest.kt:68`) 선례를 복사하면 **라이브가 돌리지 않는 전략으로
   "live-reproduction" 을 측정**하게 된다.
   **둘 다 사전 등록해 둘 다 보고한다** — 전략 선택은 A5 가 막지 못하는 미등록 자유도이므로, 사후 선택을
   원천 차단하려면 사전 고정 + 전량 보고가 유일한 방어다.
   ⚠️ **`volatility_breakout` 의 라이브 divergence (✅공식 확인, R3 의 귀속 교정)**:
   `Indicators.calculateTargetPrice` = `candles[0].open + (candles[1].high − candles[1].low) × k`,
   매수 조건 `currentPrice > target`. 라이브 09:00:10 은 store 가 당일 봉을 포함해(R2) `candles[0].open` 이
   당일 시가이므로 `currentPrice ≈ 시가` 에서 `currentPrice > 시가 + k×전일레인지` 는 **k>0 이면 불가능**.
   즉 **라이브는 09:00 에 재진입하지 못하고**, 장중 target 돌파 시점에 더 비싸게 산다(#128 의 07:32·08:43·14:27,
   +2.88~3.09% 가 이 식의 귀결). 백테는 봉 D-1 돌파를 신호로 봉 D 시가에 재진입하므로 **트리거 시점·가격·빈도가
   모두 다르다**. 방향이 단순 부호로 정해지지 않으므로 "알려진 미측정 성분"으로 리포트에 명시한다.
   (R3 이 이 현상을 `MeanReversion` 거래량 게이트로 귀속한 것은 오귀속 — MeanReversion 은 라이브 전략이 아니다.)

10. **primary estimand = 이벤트 단위 (%p / 리셋 1건)** — A5a 의 "총수익률 차이" 에서 변경 (G5 반영).
    근거: 총수익률은 경로의존 복리(`state.balance *= (1 + netPnl/100)`)라 Decision 8 이 요구하는
    "특정 표본 제외" 가 **기계적으로 불가능**하다(그 경로가 만든 잔고가 이후 모든 거래 사이즈를 바꾼다).
    이벤트 단위는 제외가 정의되고, #128 의 "−13.3%p / 7건 ≈ 1.9%p/건" 과 **직접 대조**된다.
    총수익률·거래수는 보조 지표로 함께 싣되 결론의 근거로 쓰지 않는다.

11. **A4 지표·구간 규칙** (G3 반영).
    - **팔 간 비교 허용 지표**: 이벤트당 %p, 총수익률, 거래수, 누적 수수료. **끝.**
    - **팔 간 비교 금지**: `maxDrawdownPct`(청산 시점에만 갱신 — `hold-through` 의 미실현 낙폭이 안 보인다),
      `sharpeRatio`(거래당·비연율화 — 보유기간이 다른 팔끼리 단위가 다르다). 팔 **내부** 참고로만 표기.
    - **END trade 는 전 팔에서 제외 후 재계산**하고 개수·기여를 별도 컬럼으로 병기.
      `hold-through` 는 보유가 길어 END 가 한쪽 팔에만 계통적으로 붙는다(선례: `M1ReplayBiasTest.kt:77`).
    - **구간은 200봉 전체**(in/out 분할 없음). 파라미터 적합이 없어 분할 이유가 없고, 명시하지 않으면
      재현자마다 다른 표가 나오는 미등록 자유도가 된다.
    - **누적 수수료를 팔별로 분리 보고**한다 — churn 팔의 차이 상당 부분이 기계적 수수료 성분이라
      분해하지 않으면 "리셋 비용 X%p" 가 해석 불가능해진다.

12. **`cooldown-N` 의 의미 = "N봉 차단 후 legacy 규약 복귀"** (code-review Open question 1 처분, 2026-08-26).
    재진입 시도가 실패하면 그 봉의 통상 진입 기회(신호=봉 `i` 종가 → 체결 `i+1` 시가)로 폴백한다.
    근거: 이 정의에서만 `cooldown-2 ≡ legacy` 가 성립하고, 그 등가성이 실 fixture 12×2 로 검증된다
    (`BacktestReentryEquivalenceTest`). 폴백이 없으면 만료봉에서 신호 1개가 소실돼 쿨다운을 줄였는데
    재진입이 더 늦어지는 비단조가 생긴다(code-review 모델: cd=1 → 봉 55, cd=2 → 봉 54).
    ⚠️ 대가: TIME_EXIT 봉만 진입 평가 2회(시가 + 종가신호), 가격게이트 청산 봉은 0회라 사유별 비대칭이 있다.
    라이브는 청산 후 그날 내내 매수 창이 열려 있는데 D1 은 두 점만 표현할 수 있으므로, 2회가 1회보다
    라이브에 가깝다고 보고 채택했다. 팔 간에는 대칭이라(모든 팔이 만료봉에서 2회) 비교는 공정하다.

# Key Files

변경 대상:
- `bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt` — `simulateTrades` 루프 + `BacktestConfig.reentryMode`/`reentryCooldownBars`.
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestEngineTest.kt` — A1/A2/A3 회귀 + parity assert 추가(Decision 6).
- (신규) `bot/src/test/kotlin/com/trading/bot/engine/DailyResetCounterfactualTest.kt` — A4 측정 하네스(`@EnabledIfEnvironmentVariable`).

영향 받되 변경 최소(기본값 보존 덕분 — Decision 6):
- `bot/src/main/kotlin/com/trading/bot/api/StrategyController.kt` — 새 파라미터 노출 여부 결정 필요. **미노출이 기본**(측정은 테스트 하네스로 충분).
- `bot/src/main/kotlin/com/trading/bot/engine/M1ReplayEngine.kt` — config 공유하나 재진입 미모델링. 기본값 보존이므로 모집단 불변(A3 로 확인).
- `bot/src/test/kotlin/com/trading/bot/engine/{M1ReplayBiasTest,ParameterSweepTest,KneeStrategyComparisonTest,KneeStrategyBacktestTest}.kt` — 기본값 보존 확인용(무변경 기대).

참조 전용(무변경):
- `bot/src/main/kotlin/com/trading/bot/engine/{IntrabarExitModel,DailyResetManager,TradingEngine}.kt`
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt` — BEAR 8마켓 / BULL 4마켓, paired, in/out-of-sample.

문서 동기화 대상:
- `wiki/pages/concept/backtest-engine.md` — 재진입 갭 서술 보정(현 서술은 `boughtToday` 만 언급, 2봉 공백 누락).
- `wiki/pages/concept/exit-gates.md` — 라이브/백테 차이 표에 재진입 갭 추가.
- `bot/src/test/resources/backtest/README.md` — 해석 한계에 재진입 모델 항목 추가 여부 판단.
- `M1ReplayBiasTest.kt:13-19` docstring — "진입 변수 격리" 서술이 여전히 정확한지 확인.

# Acceptance

**구현 검증 (Decision 7)**

| # | 무엇이 충족되나 | 어떻게 검증 | 통과 기준 |
|---|---|---|---|
| A1 | TIME_EXIT 후 0공백 재진입이 라이브를 재현한다 | 단위테스트: `reentryMode=LIVE_SAME_BAR`, 신호 true 인 합성 캔들 | 재진입 trade 의 `buyIndex` == 직전 trade 의 `sellIndex`, `buyPrice` == 그 `sellPrice` == `bar.open` |
| A1b | 재진입 포지션이 같은 봉 게이트를 받는다 (Decision 5) | 봉 D 에 손절 도달 low 를 심은 합성 캔들 | 재진입 포지션이 봉 D 에서 `STOP_LOSS` 로 청산됨 |
| A1c | 봉당 재진입 ≤ 1회 | 위 시나리오에서 봉 D 재청산 후 | 봉 D 에 세 번째 진입 없음 |
| A2 | 쿨다운 N 이 정확히 N봉 막는다 | `reentryCooldownBars=1,2,3` 회귀 | 재진입 `buyIndex` == 청산 `sellIndex` + N (초과·미달 모두 실패) |
| A2b | 가격게이트 청산엔 same-bar 재진입이 없다 (Decision 4) | SL/TP/트레일링 청산 시나리오 | 재진입 `buyIndex` >= 청산 `sellIndex` + 2 (기존 규약) |
| A3a | 기존 테스트 suite 통과 | `:bot:test` 전체 | green |
| A3b | **legacy 결과 보존** — 기본값 변경 없음 확인 | 변경 전/후 `BacktestConfig()` 로 동일 fixture 실행, trade 리스트 비교 | trade 수·buyIndex·sellIndex·reason·pnl 전부 동일 |
| A3c | parity 테스트가 `reentryMode` 를 들고 있다 | `config defaults match live trading defaults` | `reentryMode` 명시 assert + 이유 주석 존재 |
| A3d | 말미 경계 안전 | 마지막 봉 재진입 시나리오 | `fillIndex` 범위 초과 없음, `closeOpenPosition` 정상 |
| A3e | 상태 초기화 (codex m1) | 재진입 후 상태 검사 | `peakPrice`·`buyIndex`·수수료·balance 가 새 포지션 기준으로 초기화, trade 중복기록 없음 |

**효과 측정 (Decision 7·10·11)**

| # | 무엇이 충족되나 | 어떻게 검증 | 통과 기준 |
|---|---|---|---|
| A4 | 네 팔이 실데이터로 측정된다 | 측정 하네스를 BEAR 8 / BULL 4 fixture × {`volatility_breakout`, `combined`} 에 실행 | 팔 × 마켓 × 국면 × 전략 표. `maxHoldDays=1` 고정, 200봉 전체. **마켓별 원값 포함**(집계만 내지 않는다) |
| A4b | 지표 규칙 준수 (Decision 11) | 리포트 컬럼 | 비교지표는 이벤트당 %p·총수익률·거래수·수수료만. MDD/Sharpe 는 팔 내부 표기. END 제외 재계산 + 개수 병기 |
| A4c | 결과가 gitignored 밖에 영속된다 (G7) | `build/` 는 `.gitignore` 대상 | 표 + 한계 + fixture 수집일 + 전략명 + 커밋 sha 를 `wiki/pages/query/` 페이지로 커밋 |
| A4d | maxHoldDays 민감도는 분리 보고 | 별도 표 | primary 결론과 섞지 않음 |

**판정 기준 (사전 고정 — 사후 체리피킹 방지)**

| # | 항목 | 사전 고정값 |
|---|---|---|
| A5a | primary estimand | 마켓별 **리셋 이벤트 1건당 %p** 차이: `live-reproduction` − `hold-through` (Decision 10) |
| A5b | 집계 단위 | **마켓 균등가중** (trade 가중 금지 — 회전율 높은 팔에 가중이 쏠린다) |
| A5c | 불확실성 | 마켓 across 95% CI **병기**. 실효 독립표본 ~2(상관 평균 0.49, BTC/ETH 0.90)이므로 **참고값이며 게이트가 아니다** |
| A5d | 방향성 인정 조건 | **국면 비교는 `PAIRED_MARKETS` 4종(XRP/BTC/ETH/DOGE)으로만** — BEAR 8 vs BULL 4 는 마켓 구성 교락. 두 국면 모두 같은 부호면 "방향성 있음" |
| A5e | **국면별 부호가 갈리면** | 자동 유보 **아님** → "**국면 의존 효과**" 로 결론하고 국면 필터 검토를 후속 이슈로 넘긴다. (A5c 의 CI 는 게이트가 아니므로 유보 사유가 될 수 없다 — 옛 A5e 의 A5c 모순 해소) |
| A5f | 유보 조건 | 리셋 이벤트 수가 마켓당 3건 미만이면 그 마켓 제외, 남은 마켓이 국면당 2개 미만이면 **판정 유보** |
| A5g | 결론 강도 상한 | 실효표본 ~2 → **설명적 분석**까지. "리셋을 없애야 한다" 류 처방 금지. 그리드 최선 조합을 결론으로 뽑지 않는다 |
| A5h | 미측정 성분 명시 | 재진입 슬리피지(R3), 라이브 당일 부분봉(R2), `volatility_breakout` 트리거 divergence(Decision 9), fixture 밖 티커(SOL/AVAX/ADA) — 리포트에 **반드시** 병기 |

**문서 동기화**

| # | 무엇이 충족되나 | 어떻게 검증 | 통과 기준 |
|---|---|---|---|
| A6 | wiki·README 동기화 | `check_links.py` / `verify.sh` / `smoke.sh` | 3종 통과 + `backtest-engine`·`exit-gates` 갱신 + fixture README·`M1ReplayBiasTest` docstring 판정 |

# Blockers

없음. (G1 blocker 는 Decision 9 로 처분 완료 — 측정 전략 고정)

# Deferred

- 라이브는 TIME_EXIT 뿐 아니라 SL/TP/트레일링 청산 후에도 같은 거래일 재진입이 가능하다(`boughtToday` 는 09:00 해제).
  D1 백테로는 모델링 불가(Decision 4·8) → 이번 결론에서 명시 제외. 동일 churn 이 다른 사유에도 있는지는 별도 이슈. (중간·전략)
- `wiki/pages/concept/backtest-engine.md` 의 "라이브는 boughtToday 제약을 받지만 백테는 받지 않는다" 는 불완전 —
  일봉 기준으로는 백테가 **더** restrictive(2봉 공백). (경미·문서) → A6 에서 교정.

- `volatility_breakout` 의 라이브 재진입은 장중 target 돌파 시점이라, 재려면 **intrabar 진입 모델**(봉 D high 가
  target 을 넘으면 target 가에 체결)이 필요하다. `IntrabarExitModel` 과 같은 근사 철학이라 구현은 가능하나
  통상 진입 규약까지 바꾸는 별개 작업이다. (중간·측정정확도)
- `TRADING_MAX_HOLD_DAYS` 를 운영에서 조정하는 **코드 0줄 실거래 실험**이 슬리피지 성분을 얻는 유일하게 싼 방법
  (배선 완료: `deploy/vultr/.env`·`deploy.sh:167`). Decision 2 종점 밖 — 별도 판단. (중간·운영)

# Review Disposition

codex plan 리뷰 (2026-08-24):
- C1 스윕이 반사실에 답 못함 → **fix** (Decision 3, 대조군 3팔)
- C2 same-bar 재진입 가격모델이 모든 사유에 적용되면 look-ahead → **fix** (Decision 4, TIME_EXIT 한정)
- M1 maxHoldDays × cooldown 교락 → **fix** (Decision 3, maxHoldDays 고정)
- M2 기본값 0 의 파급·rollback 부족 → **fix** (Decision 6, legacy 기본값으로 철회)
- M3 순환논증 / estimand 미정의 → **fix** (Decision 7)
- M4 A5 가 표본한계 대비 약함 → **fix** (A5a~A5f 사전 고정)
- M5 Deferred 2번째가 측정 의미를 결정 → **fix** (Decision 8, 명시 제외)
- m1 A1/A2 상태전이 검증 부족 → **fix** (A1b·A1c·A3d·A3e)
- m2 A3 "회귀 없음" 이 기본값 변경과 모순 → **fix** (A3a~A3e 분리. 단 Decision 6 으로 기본값 불변이 되어 모순 자체가 해소)
- m3 Key Files 누락 → **fix** (Key Files 3분류로 재작성)
- m4 문서 동기화 대상 확인 필요 → **fix** (A6 에 fixture README·docstring 추가)
- plan untracked 지적 → **fix** (e7e78ae 커밋)

메인 추가 발견:
- 재진입 포지션의 진입 봉 게이트 누락 편향 → **fix** (Decision 5, A1b)

Claude plan-reviewer + codex medium (2026-08-25, 세션 한도 후 재실행분 — 결과 도착):
- P0-1 same-bar 재진입 look-ahead → **선반영됨** (Decision 4 의 TIME_EXIT 한정이 이미 닫음).
  단 **구현 함정이 남는다** → 아래 R1.
- P0-2 진입 봉(`holdDays=0`)에서 재진입하면 봉당 진입 2회 → **선반영됨** (Decision 4 제외 + Decision 5 "봉당 ≤1회").
- P0-3 Goal↔A5 모순, 사전 판정규칙 부재 → **선반영됨** (A5a~A5f).
- **R1. 신호 window 구현 함정 — Acceptance 항목 필요 (신규)**
  Decision 4 는 "신호 window = 직전 봉 종가까지"라고 선언하지만, `BacktestEngine.kt:91` 의 기존 `window` 는
  `subList(max(0, i-(MIN_CANDLES-1)), i+1)` 로 **봉 `i` 를 포함**한다(현행은 체결이 `i+1` 시가라 무해).
  same-bar 재진입은 봉 `D` 시가에 체결하므로, 구현에서 그 `window` 변수를 그대로 재사용하면
  **봉 D 의 종가·고저를 보고 봉 D 시가에 사는** look-ahead 가 된다. 재진입 신호는 `subList(..., i)` 로
  별도 계산해야 하고, 그것을 검증하는 Acceptance 항목이 지금 없다(A1 은 체결가만 본다).
- **R2. Decision 4·5 의 "라이브도 09:00 엔 당일 봉 미형성" 전제가 틀렸다 (신규, ✅코드 확인)**
  `TradingEngine.loadStoreDailyCandles`(`:370-375`)는 store D1 을 **진행 중인 당일 봉까지 포함**해 반환하고,
  `MarketDataStore`(`:51`)는 `openTime upsert` 로 분봉마다 그 봉을 갱신한다. 즉 09:00:10 라이브 신호 window 에는
  거래량이 막 쌓이기 시작한 **당일 봉이 이미 들어 있다**. 백테의 "직전 봉 종가까지"와 다르므로
  거래량 조건(`vol >= avg`)을 쓰는 전략에서 신호가 갈릴 수 있다 → `live-reproduction` 팔의 충실도 문제.
  **처분 필요**: (a) 이 divergence 를 알려진 한계로 A5/문서에 명시하거나, (b) 영향 받는 전략을 식별해
  측정에서 분리하거나, (c) 백테 재진입 신호에 부분 봉을 흉내내는 건 look-ahead 라 **불가**.
- **R3. D1 백테는 #128 의 헤드라인 비용(+1.80% 재진입 갭)을 측정할 수 없다 (신규, ✅코드+데이터 확인)**
  재진입가 = 청산가(`bar.open`)로 고정되므로 **재진입 가격 갭은 구조적으로 0**이다. 봉 내 다른 가격
  (high/low)을 쓰는 건 look-ahead 라 Decision 4 가 이미 배제했고, M1 replay 는 fixture 가 아니라 API
  실시간 fetch(`M1ReplayBiasTest.fetchM1Range`)라 스윕에 못 쓴다.
  실측도 이를 뒷받침한다 — #128 의 24h 내 재매수 7건 중 공백 0.0h 는 **1건뿐**이고 나머지는
  7:32·8:43·14:27 로 **장중 몇 시간 뒤**다(그래서 가격이 +2.88~3.09% 움직였다). 라이브의 실제 재진입은
  "청산과 같은 가격"이 아니다. 09:00:10 에 재진입이 잘 안 되는 이유도 설명된다 — `MeanReversion` 의
  `currentVolume >= avgVolume * 0.8`(`:36`)이 거래량이 막 쌓이기 시작한 당일 봉에서 막는다(R2 와 같은 뿌리).
  **따라서 이 설계가 실제로 재는 것은** "보유상한이 걸렸을 때 계속 들고 가는 것 vs 신호를 재평가해
  신호가 유지될 때만 남는 것"의 차이(= 신호 지속성의 가치)이지, 재진입 슬리피지가 아니다.
  `live-reproduction` 과 `hold-through` 는 **신호가 계속 true 인 구간에서는 수수료 차이로 수렴**한다.
  **처분 필요**: Goal·A5 문구를 이 범위로 좁히고, 리포트에 "측정 못 하는 성분"을 명시. 슬리피지까지
  재려면 M1 fixture 도입이 선행돼야 하며 그건 별도 작업이다.
- 참고: 리뷰어가 `TRADING_MAX_HOLD_DAYS`(`deploy/vultr/.env:66`, `deploy.sh:167` 배선 완료)를
  **코드 0줄 실거래 실험** 경로로 제시했다. Decision 2(종점=측정)와 별개 선택지라 `# Deferred` 에 남긴다.
  R3 을 감안하면 이 경로가 슬리피지 성분을 얻는 **유일하게 싼 방법**이다.

Claude plan-reviewer 2차 — gap 탐색 (2026-08-25, 구현 후 도착):
- **G1. 측정 전략이 plan 에 미고정, 운영은 `volatility_breakout`** (blocker) → **fix** (Decision 9).
  ✅ 내가 직접 확인: `deploy/vultr/.env` 의 `TRADING_STRATEGY=volatility_breakout`,
  `Indicators.calculateTargetPrice` = `candles[0].open + k×전일레인지` → 09:00 즉시 재매수 수학적 불가.
  리뷰의 R3 오귀속(`MeanReversion` 거래량 게이트) 교정 포함.
- **G2. #128 2안(리셋 대상 한정)에 대응하는 팔이 없다** (major) → **fix** (Decision 3 `conditional-reset` 추가).
- **G3. `hold-through` 의 END 비대칭 + MDD/Sharpe 팔 간 비교 불가** (major) → **fix** (Decision 11).
- **G4. A5c↔A5e 모순(CI 가 참고값이자 게이트), A5d 가 국면의존 결과를 자동 유보로 보냄, BEAR8 vs BULL4 마켓 교락**
  (major) → **fix** (A5c 게이트 아님 명시 / A5e 국면의존 결론 / A5d `PAIRED_MARKETS` 한정).
- **G5. A5a 복리 총수익률과 Decision 8 의 표본 제외가 기계적으로 양립 불가** (major) → **fix** (Decision 10, 이벤트 단위).
- **G6. A3b 골든 스냅샷 순서** (major) → **fix** (`# Next` step 3 — base `1e78a18` 대조. 구현이 이미 끝나
  base 덤프가 필요하지만 `BacktestEngine.run` 은 시각·난수 비의존이라 재현 가능).
- **G7. 결정 근거가 gitignored `build/` 에만 남는다** (major) → **fix** (A4c, `wiki/pages/query/`).
- **G8. `# Blockers: 없음` 이 R1~R3 미처분과 불일치** (blocker 주장) → **partial**. R1 은 구현(`25750aa`)에서
  `subList(max(0, i-MIN_CANDLES), i)` 로 닫혔고 R2·R3 은 처분됐다(Goal 축소). Disposition 문구만 stale 이었다 → 갱신.
- **G9. 리뷰가 plan 을 잘못 인용한 부분** — "2026-08-25 codex 가 이 개정본에 실행됨" 은 사실이 아니다
  (codex 는 개정 **전** draft 에 1회). `# Review Disposition` 의 codex 항목은 C1/C2/M1~M5/m1~m4 다. → **false-positive**.
- minor 수용: A4 구간 200봉 명시(Decision 11), fixture 밖 티커 명시(A5h), 팔별 수수료 분리(Decision 11),
  `cooldown-2 == legacy` 무료 회귀(→ `# Next` step 3 에 포함).
- minor `reentryMode × reentryCooldownBars` 조합 검증(`init require`) → 처음엔 **defer**(측정 하네스가
  유일한 호출자) 였으나, code-review 가 음수 cooldown 은 조용히 `cooldown=1` 처럼 동작하고 거대값은
  잔여 구간을 전면 차단한다는 구체적 실패를 들어 재제기 → **fix** (`e412bba`, `BacktestConfig.init` require 2종).
  둘 다 "결과가 그럴듯해 보여서 측정이 조용히 망가지는" 부류라 호출자 수와 무관하게 막는 게 옳다.

Claude code-reviewer (2026-08-25, 세션 한도로 조기 종료):
- 유일하게 보고된 지적은 "쿨다운 재진입 실패 시 그 봉의 통상 진입 기회를 잃는다" → **이미 fix 됨**
  (`902d781`, 병렬 수정). 리뷰어 자체 확인. 그 외 지적 없이 종료.

simplify 체크 (2026-08-26, 메인 직접 — dlc 13):
- 테스트 헬퍼 중복 없음 — 캔들 빌더는 `BacktestReentryTest` 1곳뿐이고 나머지 3개 테스트 파일이 재사용.
- **`reentryDoneAt` 가드가 도달 불가(죽은 분기)** — `BacktestEngine.simulateTrades` 의
  `if (i == reentryDoneAt) continue`. 재진입 성공 시 항상 `continue` 하고 봉 인덱스는 단조증가라
  같은 `i` 로 되돌아오는 경로가 없다(`902d781` 의 fallthrough 경로 포함해 확인). "봉당 ≤1회" 불변은
  그 `continue` 가 이미 보장하고 A1c 테스트가 덮는다.
  **제거하지 않고 제안만 남긴다** — 동작 보존 변경이지만 이 파일을 다른 에이전트가 동시 편집 중이라
  충돌 비용이 이득을 넘는다(dlc "불확실하면 보류"). 후속에서 정리 가능.
  → **처리됨(`e412bba`)**: code-review 가 같은 항목을 Major 로 올렸고, 편집이 끝난 뒤 제거했다.
  측정 결과·legacy 골든 모두 불변 확인.

code-review 재실행분 (2026-08-26):
- **Critical-1 재진입 실패 시 진입 기회 소실** → **fix**. 메인이 실측으로 먼저 발견해 `902d781` 로 수정,
  리뷰가 상태머신 모델 2000회로 독립 확인. 방향 일치.
- **Major reentryDoneAt dead guard** → **fix** (`e412bba`). "봉당 1회" 는 구조가 보장하므로 플래그 삭제 + 근거 주석.
- **Major A1c 테스트가 dead guard 를 검증(vacuous)** → **partial**. 단언 자체("봉 D 진입 1회")는 구조 불변식이라
  유효하므로 유지하고, 주석을 구조 근거로 교체. 실질 구멍이던 **재진입 실패 경로**는 신규 테스트로 덮었다.
- **Major 테스트 미커버 5종** → **fix 4 / defer 1**. 실패 경로·window 길이·TP 짝·마지막 봉 경계는 추가.
  A3e(`peakPrice` 리셋 단언)는 **defer** — 리뷰가 legacy 진입과 동일 코드경로임을 반증으로 확인했고
  A1 의 `buyPrice`/pnl 단언이 실질을 덮는다.
- **Major A3b 근거 불충분** → **해소됨**. `905fbac` 골든이 `db48763` 대조로 닫음(리뷰도 그렇게 판정).
- **Minor cooldown 무검증** → **fix** (`BacktestConfig.init` require 2종).
- **Minor 공허 통과 `if (next != null)`** → **fix** (assertNotNull).
- **Minor `AlwaysBuy.seen` 죽은 필드** → **fix** — window 길이 단언에 실제로 쓰이게 했다.
- **Minor wiki `backtest-engine.md:24` 모드 의존** → **fix** (`31d63b8`).
- **Minor 두 번째 `processExit` 반환값 버림** → **wontfix**. `effectiveMaxHoldDays` 가 최소 1 이라
  `holdDays=0` 에서 TIME_EXIT 이 나올 수 없다(리뷰도 무해로 확인). 주석 추가는 하지 않았다 — Decision 5 가 이미 근거다.
- refuted 7종(chartExit look-ahead·트레일링 arm·무한 churn·이중진입·인덱스 초과·예약 미해소·legacy no-op)은
  리뷰가 스스로 반증했고 메인도 동의 — 조치 없음.
- **Open question 1(cooldown 의미)** → Decision 12 로 확정. **Open question 2(cd=0 진입 2회)** → Decision 12 에 함께 기록.
  **Open question 3(수수료 분리)** → Decision 11 이 이미 요구, 리포트 원표에 팔별 수수료 컬럼 존재.


- Claude subagent 2개(plan-reviewer·architecture-reviewer) 동시 실행이 세션 한도로 전멸(2026-08-24).
  memory `project_subagent_quota_deaths` 의 3회 실측 패턴 재현 — 동시 2개도 위험. 이후 1개씩 순차 실행으로 전환.
