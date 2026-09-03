---
title: strategy-search-yearly — 1년 fixture 위 알고리즘 탐색(파라미터 스윕 + 신규 신호 아이디어), 사전고정 게이트로 판정
status: in_progress
started: 2026-09-03
updated: 2026-09-04
---

# Goal

운영 8종 1년 fixture(`yearly/`, 2025-09-03~2026-09-02) 위에서 **지금 라이브(`combined`, TP5/SL5/트레일2/arm3/hold1)보다
나은 설정이 있는지**를 찾는다. 축은 셋 — ① 기존 전략 9종의 진입(`kValue`)·청산 파라미터 스윕 ② 라이브에 없는 신규 아이디어(레짐
필터·ATR 가변 손절/익절·부분 익절) ③ 비용 민감도. **선택은 선택창에서만, 판정은 검증창·타 국면·null 대조군으로만** 하고, 판정 기준은
결과를 보기 **전에** 이 plan 에 못박아 git 에 커밋한다(`.claude/plans/` 는 이 repo 에서 tracked).

**이 설계의 비대칭성을 먼저 인정한다**: "통과 0건"은 지금 설계로 정직하게 낼 수 있지만, "통과 후보 있음"은 null 대조군 분위수를 넘어야만
주장할 수 있다. 후자를 주장하려면 G1~G7 **전부** + null max-statistic 95% 분위수 초과가 필요하다.

종착점은 리포트 + wiki query 페이지 + 권고이며 **라이브 파라미터는 바꾸지 않는다**(승격은 사람 승인 — [[strategy-evolution-expectations]]).

# Progress

- 2026-09-03 — worktree 생성(base `origin/main@8b0dc41`), Explore 완료. 재사용 자산 확인:
  `YearlyStrategyComparison`(고정 노셔널 Σpnl%·봉단위 MDD·노출, FULL/SELECT/VALIDATE 창), `YearlyFixtures`(365봉 8마켓),
  `BacktestFixtures`(bear/bull, `PAIRED_MARKETS` 3종), `ParameterSweepTest`(구식). 제약: ATR 지표 없음 · `SimulationState.position` 단일 · `useMarketFilter` 기본 off.
- 2026-09-03 — 빌드 확인: JDK25 로는 Gradle 8.12 가 죽는다(`IllegalArgumentException: 25.0.2`).
  `JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-21.0.9/Contents/Home` 프리픽스로 `:bot:test --tests "*YearlyStrategyComparisonTest*"` BUILD SUCCESSFUL(21s).
- 2026-09-04 — plan-reviewer + codex(medium) + architecture-reviewer(planning) 검토. Critical 9(중복 제외)·Major 14.
  방법론을 무너뜨리는 4건(bear⊂yearly / 전략축소가 FULL 순위 유래 / arm×trail alias 37.5% / paired 미사용)을 포함해 전면 개정 — 아래 `# Decisions` 는 개정본이고 `# Review Disposition` 에 처분을 남겼다.
- 2026-09-04 — 사전고정 커밋(`391118e`) 후 Stage A 하네스 구현·실행(`4aeb519`). 합성 벤치 0.185ms/run, 전체 실행 33초.
  그리드 51,480 좌표(Cartesian 93,600 대비 arm alias 제거로 −45%) → **고유 행동 22,952**.
  게이트별 탈락: G5 974 · G1 21,513 · G3 330 · G6 132 · G2/G4a/G4b/G7 0 → **통과 3건**(전부 같은 행동 계열:
  `combined` k0.3 / SL7 / 트레일1.5·arm0 / hold1 / 필터off / TP 8~off). 선택 +4.92%p · 검증 +2.13%p · bull +6.5~7.0%p ·
  MDD Δ중앙 +0.30%p · 수수료 4배에서도 +3.71%p.
- 2026-09-04 — **null 대조군이 판정을 뒤집었다**(Decisions 14). 최초 설계(후보·기준 둘 다 무작위)는 seed 86.8% 가 통과하는
  고장난 계기였고, 기준을 실제 라이브 baseline 으로 고쳐 다시 돌리니 **91 seed 중 0건 통과**. 다만 사전고정한 max-statistic
  기준(잡음 탐색 최고 delta 95% 분위 = 7.84%p, 탐색폭 13배 환산 13.60%p)을 실제 후보(4.92%p)가 **넘지 못했다** →
  사전고정 규칙대로 **"발견 없음"으로 보고**한다.

# Next

Stage B 구현 — 신규 노브(레짐필터 기간·ATR 가변 손절익절·부분익절)를 Decisions 5~8 의 4중 가드와 함께 넣고 같은 게이트로 측정.
그 다음 Stage C(wiki query 페이지 + 권고).

# Decisions

## 0) 검토에서 뒤집힌 것 (초안 대비 변경점 요약)

| 초안 | 개정 | 이유 |
|---|---|---|
| G1~G6 을 `median(후보) − median(baseline)` 로 | **마켓별 paired delta** 의 중앙값 + 양수 마켓 수 | 8마켓 공통이므로 paired 가 마켓 효과를 제거, 검정력↑ |
| G4 = bear·bull 교차검증 | **bull = 시간독립 holdout / bear = 구간 robustness(독립 증거 아님)** | bear(2026-01~08) ⊂ yearly(2025-09~2026-09). 같은 데이터 재사용 |
| 런타임 초과 시 "전체 창 상위 5 전략" | **9전략 유지**, 축소는 SELECT 창 순위로만(사전고정) | FULL 창은 검증창을 포함 — 축소 규칙이 사전고정을 깬다 |
| Cartesian 그리드 5,760 | **conditional grid + trade fingerprint dedup 후 고유 행동 수** | arm ≤ trail/(1−trail/100) 은 전부 alias → 37.5% 중복 |
| null 대조군 seed 1개 | **사전 커밋한 seed 100개** + max-statistic 분위수 | seed 1개는 분포가 아니라 관측 1개 |
| `kValue` 제외(캐시 사정) | **`kValue` 포함**(캐시 키에 넣으면 그만) | 전략이 신호에서 읽는 유일한 config 필드. 빼면 "출구 탐색"이지 알고리즘 탐색이 아니다 |
| ATR 임계를 evaluate 에 nullable 2개 주입 | **`IntrabarExitModel.exitLevels()` 단일 순수함수** + M1 `require` 거부 | D1/M1 이 다른 정책으로 갈라지는 것을 구조로 차단 |
| 부분익절 "MDD 보수적이라 안전" | 진짜 위험은 **intrabar 순서(pnl 낙관)와 노출 미보정** | 동일봉 SL·부분TP 충돌 시 순서가 pnl 을 직접 부풀린다 |
| `marketFilterMaPeriod {20,50,100}` | **{10,20,50}** + 50 초과는 `init` 에서 거부 | 엔진 window 가 항상 50봉이라 100 은 조용히 50이 된다 |

## 1) 사전고정 판정 게이트 (결과를 보기 전에 확정, 실행 전 커밋)

**baseline** = `combined` + `BacktestConfig()` 기본값에서 **`reentryMode` 만 `LIVE_SAME_BAR` 로 덮은 것**(기본값 자체는 `LEGACY_NEXT_BAR`).
하네스 상수 `BASELINE_STRATEGY`/`BASELINE_CONFIG` 로 한 곳에 두고 모든 게이트·리포트·null 이 그것만 참조한다.

**관측치 = 마켓별 paired delta.** 창 W, 마켓 m 에 대해 `Δ(m) = Σpnl%(후보, m, W) − Σpnl%(baseline, m, W)`.
후보 요약은 `median(Δ)` 와 `#{m : Δ(m) > 0}` 두 값이다.

| 게이트 | 기준 | 성격 |
|---|---|---|
| **G1** 선택창(193봉) | `median(Δ) ≥ +2.0%p` **그리고** 양수 마켓 ≥ 6/8 | 운영 최소효과(통계적 유의 아님) |
| **G2** 검증창(122봉) | `median(Δ) ≥ 0` **그리고** 양수 마켓 ≥ 5/8 | 방향 일관성. 기대 통과율 ≈50% 이므로 단독 근거로 쓰지 않는다 |
| **G3** plateau | **고유 config** 이웃(각 축 ±1 스텝, alias 제거 후) 중 ≥70% 가 G1 통과 | 단일 peak 배제. Stage A 전용 |
| **G4a** bull(시간독립) | 8마켓 `median(Δ) ≥ −1.0%p` 그리고 `PAIRED_MARKETS`(XRP·BTC·SOL) 에서도 동일 | **유일한 시간 독립 holdout**(2023-11~2024-06, roster 완전 상이) |
| **G4b** bear(구간 robustness) | 동일 기준 | ⚠️ bear ⊂ yearly 라 **독립 증거가 아니다**. 리포트에 그렇게 표기 |
| **G5** 표본 | 창별 거래수 ≥ 8, 0거래 마켓 ≤ 1 (국면 창 150거래봉에도 동일) | 기존 `MIN_TRADES_FOR_RANK` 관례 |
| **G6** 낙폭 | 마켓별 MDD delta 중앙값 ≤ **+2.0%p** 그리고 최악 마켓 MDD ≤ baseline 최악 × **1.5** | MDD 는 예산 100 기준 **절대 %p**(비율 아님). 최악값은 극단 순서통계량이라 중앙값을 주 기준으로 |
| **G7** 비용 민감도 | G1~G6 통과 후보를 `feeRate` {0.0005, 0.001, 0.002} 로 재실행해 **G1 유지** | 거래수 3배 후보는 왕복비용 0.05%p 만 올라도 우위가 사라진다 |

2.0%p·70%·1.5배·+2.0%p 는 **운영상 최소효과·리스크 허용치**이지 통계적 유의 임계가 아니다. 데이터에서 유도한 값이 아니라는 사실을 리포트에 명시한다.

## 2) 다중비교 통제 — null 대조군(사전 커밋 seed 100개)

전략 신호를 **무작위 진입**으로 대체한 대조군에 **같은 그리드·같은 게이트**를 적용한다.

- **순수 해시 진입**(상태 있는 RNG 금지): `entry(seed, market, candleDateTimeKst) = hash(...)/2^64 < p`. exit config 를 바꿔도 같은 봉이면 같은 답 → 캐시 동치성·게이트 판정과 양립한다. 순차 RNG 를 쓰면 exit config 마다 호출 순서가 달라져 대조군 정의 자체가 무너진다.
- `p` = baseline 전략의 **원시 신호 발생률**(flat 여부와 무관하게 전 봉에서 `shouldBuy` 를 평가해 계산). 실현 진입 수는 exit 설정에 따라 달라지므로 이 매칭은 근사이며 그 사실을 리포트에 적는다.
- seed 목록은 **실행 전 이 plan 에 커밋**한다: `1..100`(고정, 재현 가능).
- null 은 **축소 그리드**(아래 3) 의 coarse grid)로 돌린다 — 전체 그리드 ×100 은 비현실적. 축소 규칙도 사전고정.
- 보고: (a) seed 별 "G1~G6 을 1건 이상 통과" 비율(≈FWER), (b) 통과 수의 null 분위수, (c) **max-statistic**(seed 별 최고 `median(Δ)`)의 95/99% 분위수.
- **판정 규칙**: 실제 통과 후보의 `median(Δ)` 가 null max-statistic 95% 분위수를 넘지 못하면 **"발견 없음"으로 보고**한다. 통과 후보가 있어도 그렇다.
- 블록 부트스트랩(paired 일별 equity-delta 를 7/14/28일 moving block 재표집)은 더 정교하지만 이번 범위 밖 → `# Deferred`.

## 3) 그리드 — conditional 생성 + alias dedup, 사전고정 후 실행

**Cartesian 금지**(비활성 축 조합이 분모를 부풀리고 plateau 를 무력화한다). 축과 조건:

| 축 | 값 | 조건 |
|---|---|---|
| strategy | 9종 전부 | — |
| reentryMode | `LIVE_SAME_BAR` 고정 | 통과 후보만 `LEGACY_NEXT_BAR` 재확인 |
| kValue | {0.3, 0.5, 0.7} | `volatility_breakout`·`combined` 만. 나머지는 0.5 단일 |
| takeProfitPct | {2, 3, 5, 8, 12, **OFF**} | OFF = 하네스 상수 `1000.0`(절대 미발동). 프로덕션 필드 추가 없음 |
| maxLossPct | {2, 3, 5, 7, 10} | — |
| trailingStopPct | {**OFF**, 1.5, 2, 3, 5} | OFF = `1000.0` |
| trailingArmPct | {0, 2, 3, 5} 중 **실효값만** | trail OFF 면 arm 축 생성 안 함. 아니면 `arm > trail/(1−trail/100)` 인 값 + 대표 하한 1개만 |
| maxHoldDays | {1, 2, 3, 5, 10, 365} | — |
| marketFilter | {off, MA10, MA20, MA50} | Stage B 노브(아래 4). Stage A 는 {off, MA50} 만 |

- **alias dedup**: 실행 결과를 `(market, buyIndex, sellIndex, reason, round(pnl,6))` fingerprint 로 묶어 **고유 행동 수**를 센다. 다중비교 분모·plateau 이웃 수는 고유 기준, 리포트엔 명목 config 수와 나란히 적는다.
- **coarse grid**(null 대조군 + 런타임 초과 시 폴백, 사전고정): strategy 9 × kValue 0.5 고정 × TP{3,5,12,OFF} × SL{3,5,10} × trail{OFF,2,5} × arm(실효) × hold{1,3,365} × filter{off,MA50}.
- **런타임 예산**: Stage A 벽시계 **30분**. 초과하면 위 coarse grid 로 전환(전략 축은 자르지 않는다).
- **전략 축소가 불가피하면** SELECT 창 순위로만 자른다. [[yearly-strategy-comparison]] 의 FULL 순위는 검증창을 포함하므로 **축소 근거로 쓰지 않는다**.
- **벤치마크는 성과를 산출하지 않는 경로로만**: synthetic candle(합성 시계열)로 run/초를 재고, fixture 성과 수치는 사전고정 커밋 전에 출력하지 않는다.

**신호 캐시**: `shouldBuy` 는 (전략, 마켓, kValue, 봉) 의 함수이고 exit 축과 무관하다.
- 키 = `(strategyName, market, kValue, window 최신봉 candleDateTimeKst, window.size, currentPrice)`.
  ⚠️ **마켓이 반드시 키에 있어야 한다** — yearly 8종은 같은 365개 날짜를 공유하므로 마켓 없는 키는 BTC 신호를 ETH 에 재생한다.
  (초안의 "경계에서 window 크기가 달라진다"는 **사실 오류** — window 는 항상 50봉이고, 통상/재진입 경로는 최신봉 시각으로 갈린다.)
- 데코레이터는 `name`·`minCandles`·`shouldSell`·`*Normalized` 를 **전부 위임**한다(`name` 을 안 넘기면 `engine.run` 의 전략 조회가 실패).
- 캐시 수명은 **전략 × 마켓 × fixture 세트** 로 한정하고 순차 실행한다(동시성 도입 안 함).
- 동치성 테스트에 **마켓 충돌 재현**(같은 날짜, BTC/ETH 가 다른 신호)을 필수 케이스로 넣는다.

## 4) Stage 구성

- **Stage A — 기존 전략 진입·청산 스윕.** 프로덕션 코드 변경 0(하네스만). 위 그리드에서 filter 는 {off, MA50}.
- **Stage B — 신규 아이디어.** 프로덕션 3파일에 nullable/기본 off 노브 추가(아래 5~7). base config 집합은 **사전고정**: baseline + Stage A 통과 후보(있으면) + `{hold 1,3,365} × {TP 5, OFF} × {trail OFF}` 소집합(ATR·부분익절이 실제로 발동하는 조건 — 트레일링이 항상 먼저 평가되므로 trail OFF 가 없으면 ATR 손절이 한 번도 안 걸린다).
  **G3(plateau)는 Stage B 에 적용하지 않는다**(축당 값이 3개 미만이라 이웃 정의 불가). 대신 전 셀을 표로 보고한다.
- **Stage C — 리포트 + wiki query 페이지 + 권고.** 라이브 반영 없음.

Stage A 가 0건이어도 Stage B 는 돈다 — "기존 파라미터 공간엔 없다"와 "새 구조에도 없다"는 다른 결론이다.

## 5) 레짐 필터 — 기간 노브 추가, 50 초과는 거부

`useMarketFilter`(현재가 < MA50 이면 매수 차단)는 백테 전용 opt-in·기본 off 이고 **라이브 매수 경로엔 없다**([[backtest-engine]]).
- `BacktestConfig.marketFilterMaPeriod: Int = 50`(기본 = 현행 동작 그대로). 그리드 {10, 20, 50}.
- **`init { require(marketFilterMaPeriod in 1..MIN_CANDLES) }`** — 엔진 window 가 항상 50봉이라 51 이상은 `min(50, size)` 로 **조용히 50 이 된다**. 조용한 절삭 대신 즉시 실패(`reentryCooldownBars` 선례).
- 50 초과 기간을 보려면 window 폭을 넓혀야 하고 그러면 워밍업이 바뀌어 `Window` 계약(`tradeRange.first − inputRange.first == 50`)과 선행 측정 비교 가능성이 깨진다 → `# Deferred`.
- ⚠️ 이 필터가 이기면 **라이브 코드 변경**(매수 경로에 필터 추가)이지 파라미터 변경이 아니다 — 권고에 명시.

## 6) ATR 가변 손절·익절 — 임계 산출을 단일 순수함수로

- `Indicators.calculateAtr(candles, period = 14)` 신규(`common`). KDoc 에 셋을 명시: **SMA 방식(Wilder 아님)** · 입력은 **최신순** `List<Ohlc>` 이고 True Range 가 이전 종가를 쓰므로 **`period + 1` 봉 필요** · **현재 라이브 소비자 없음**(백테 리서치 전용). 테스트는 관례대로 `bot/src/test/.../strategy/`.
- `BacktestConfig.atrStopMultiplier: Double? = null`, `atrTakeProfitR: Double? = null`. `init` 에서 `atrTakeProfitR != null && atrStopMultiplier == null` 을 거부.
- **임계 산출은 `IntrabarExitModel.exitLevels(buyPrice, config, entryAtr): ExitLevels(stopPrice, takeProfitPrice)` 하나**로 모으고 `evaluate` 가 그걸 받는다. nullable 가격 2개를 evaluate 에 따로 주입하면 "multiplier 는 있는데 가격은 안 넘어온 상태"가 표현 가능해져 조용히 % 경로로 떨어진다.
- **`M1ReplayEngine.replayExit` 진입부에 `require`** 로 ATR·부분익절 config 를 거부한다(`require(!config.holdLimitOnlyWhenProfitable)` 선례가 같은 파일 38행에 있다). 안 걸면 D1 은 ATR, M1 은 % 로 **서로 다른 정책을 돌면서 컴파일도 테스트도 통과**한다 — `IntrabarExitModel` 이 존재하는 이유가 그것.
- ATR 은 **진입 신호 시점까지의 완결봉**으로 계산해 `SimulationState` 에 고정한다(체결봉 OHLC 를 보면 look-ahead). 추적형 ATR 스탑은 `# Deferred`.
- `atr == 0.0`(무변동 구간)이면 임계 = 진입가 → 즉시 청산 사고. `exitLevels` 에서 방어하고 테스트로 고정.

## 7) 부분 익절 — 가중 pnl 근사 유지, 단 거짓말을 격리한다

엔진은 `position: Boolean` 단일이라 진짜 분할은 `swingEquityCurve`(동시 오픈 2거래 추적 불가)·거래수 2배(G5·순위 비교 불가)·승률/PF 정의 변경까지 번진다 → **근사가 옳다**. 대신:

1. 1차 익절선 도달 시 비중 f 청산, 잔여 (1−f) 를 기존 게이트로 계속 → `pnlPercent = f×pnl₁ + (1−f)×pnl₂` 한 레코드로 합성.
2. **`BacktestTrade.partialFraction: Double? = null`** 추가 — 합성 레코드를 식별 가능하게. 기본 null 이라 골든·기존 소비자 무영향.
3. **동일봉 충돌 순서를 명시**: `트레일링 → 전량 SL → 부분 TP → 전량 TP → CHART → TIME`. 부분 TP 를 SL 보다 먼저 인정하면 (high 가 +2%, low 가 −5% 를 함께 친 봉에서) pnl 이 직접 부풀려진다 — 기존 계약이 순서 불명 시 SL 우선인 것과 같은 이유로 보수 쪽을 택한다.
4. **노출을 잔여 비중으로 보정**: `Σ(잔여비중 × 봉수)/거래봉`. 안 하면 "낙폭이 작은 게 실력인지 노출 탓인지" 를 가르는 열이 부분익절 후보에서만 무의미해진다.
5. `reason` 은 기존 vocabulary 유지(새 값 금지 — `M1ReplayBiasTest` 의 reason confusion matrix 가 미지 값에 무방비).
6. 단위 테스트: **partial 노브가 null 이면 모든 trade 에서 `pnlPercent ≈ (sell−buy)/buy − 수수료` 불변식 성립**(지금 암묵인 계약을 명시화) · 부분+SL 동시봉 · 부분+TP 동시봉 · gap open · END 청산 · 수수료 이중차감 없음.
- MDD 방향: 근사 곡선은 익절선 주변에서 실제를 1/(1−f) 배 증폭하므로 거래 내부 낙폭은 **실제 이상**(보수적)이다. 다만 그게 "안전하다"는 근거는 아니다 — 진짜 위험은 위 3·4다.

## 8) 신규 노브를 넣을 때 매번 거는 4중 가드

nullable 기본값만으로는 **이번 커밋만** 지켜진다. 신규 노브마다 같은 커밋에서:
1. `BacktestConfig.init` 정합성 `require`(범위·상호의존).
2. `BacktestEngineTest.config defaults match live trading defaults` 에 `assertNull`/`assertEquals` 한 줄(현 가드는 필드 나열식이라 exhaustive 하지 않다).
3. `M1ReplayEngine.replayExit` 의 `require` 거부(청산 의미에 영향 주는 노브 한정 — 진입측 `marketFilterMaPeriod` 는 M1 이 청산만 replay 하므로 불필요, 구현 시 확인).
4. `/backtest` **미노출**. `StrategyController` 는 `BacktestRequest` DTO 에서 필드 단위로 조립하므로 필드 추가가 REST 스키마를 **바꾸지 않는다**(계약 안전 확인됨). 리서치 노브를 공개 API 에 올리면 검증·하위호환 부담만 생긴다.

## 9) `ParameterSweepTest` 는 대체·삭제한다

네트워크 의존(`WebClient` 로 Upbit 직접 조회)·단일 마켓(BTC)·**금지된 순위 지표**(all-in 복리 `totalReturnPct` — `YearlyStrategyComparison` KDoc 이 전략 줄세우기에 쓰지 말라고 명시)라, 신규 하네스와 **상충하는 순위 기준을 가진 스윕이 같은 패키지에 둘** 남는다. `/backtest` API 회귀를 커버하지도 않는다(엔진 직접 호출).
→ 신규 하네스가 통과하면 삭제하고, `wiki/pages/concept/backtest-engine.md` 의 "기본값을 바꾸면 안 된다 — `ParameterSweepTest`…" 언급을 함께 갱신한다.

## 10) 하네스 공용 추출은 leaf 수학이 아니라 측정 단위로

`YearlyStrategyComparison` 은 Window·측정·후보목록·순위통계·리포트 5책임을 한 클래스가 진다. 새 스윕이 그 리포트 클래스에 의존하면 안 되고, `median()` 은 file-private 라 복제될 판이다.
→ test source 에 **`SwingMetrics.kt`** 를 신설해 `AnalysisWindow`·`swingEquityCurve`·`maxDrawdownPct`·`median`·상수(`EQUITY_BASE`·`ROUND_TRIP_FEE_PCT`·`MIN_TRADES_FOR_RANK`)와 **`measure(engine, strategyName, market, chronological, window, config): Row`** 를 옮긴다. `YearlyStrategyComparison` 은 `config = BacktestConfig(reentryMode = mode)` 로, 신규 하네스는 `config = 그리드 좌표` 로 **같은 함수**를 부른다. 순위·상관 통계는 옮기지 않는다(신규 하네스가 안 쓴다).

## 11) 게이트는 그리드 좌표 위의 순수 함수로 분리한다

평평한 `List<BacktestConfig>` 에서는 "각 축 ±1 스텝 이웃"이 정의되지 않아 G3 가 for 루프에 눌러붙고 단위 테스트가 불가능해진다.
→ `Grid(axes)` / `GridPoint(axisIndices)` 명시 타입 + `Grid.neighbors(point)` 순수 함수(boolean·OFF 축의 이웃 정의를 한 곳에 못박는다).
→ `perCandidateGates(metrics, baseline): GateResult`(G1·G2·G4~G7 — 창별 지표만 입력, 엔진·fixture 무의존) / `plateauGate(grid, point, passing): Boolean`(G3 — 결과 집합 전체가 입력). 둘 다 인공 데이터로 경계 테스트 가능.

## 12) 손익분기 승률은 이론식이 아니라 trade 분포에서 실측한다

초안이 인용한 `[[trade-performance-analysis]]` 는 **wiki 에 없는 페이지**다(plan 이지 wiki 가 아니다) — 죽은 링크 제거.
`SL/(TP+SL)` 식은 손익이 ±TP/SL 두 값뿐이고 비용 0 일 때만 성립하는데, 엔진은 왕복 수수료를 빼고 TRAILING/CHART/TIME/END 청산가가 섞인다.
→ 통과 후보의 손익분기 승률은 **실제 trade 분포**(평균이익·평균손실·payoff ratio·수수료 반영)에서 계산하고, `TAKE_PROFIT`/`STOP_LOSS` 로만 끝난 거래 비율을 함께 실어 그 식이 얼마나 적용되는지 독자가 판단하게 한다.

## 14) null 대조군은 두 변종으로 나눈다 (실행 중 발견 — 2026-09-04)

최초 설계(후보·기준 **둘 다** 무작위 진입)를 돌린 결과 seed 91개 중 **86.8%** 가 게이트를 통과하는 후보를 냈다(seed 당 평균 262.8건,
3,960 중 6.6%). 실제 탐색은 22,952 고유 행동 중 3건(0.013%)만 통과했으므로, 잡음이 실제보다 500배 잘 통과한 셈이다.
이건 게이트가 무르다는 뜻이 아니라 **기준(baseline)이 잘못 잡혔다**는 뜻이다 — 무작위 진입 baseline 은 성과가 형편없고 변동이 커서
delta 분포 자체가 부푼다. 그 분위수를 실제 탐색의 임계로 쓰면 사과-오렌지 비교다.

→ 대조군을 둘로 나눈다.
- **변종 A (주 판정)**: 후보만 무작위, **기준은 실제 라이브 baseline**. 실제 탐색이 던지는 질문과 같은 질문이다.
- **변종 B (진단용)**: 기존 설계. 게이트 스택이 순수 잡음 환경에서 어떻게 움직이는지 보여줄 뿐 임계로 쓰지 않는다.

**두 판정이 갈릴 때의 규칙**: 사전고정한 max-statistic 규칙이 우선한다. 실측에서 변종 A 는 통과율 0/91 로 "게이트가 잡음을 거른다"를
지지하지만, max-stat 분위(7.84%p / 환산 13.60%p)는 실제 후보(4.92%p)보다 높다. 두 통계가 재는 대상이 다르기 때문이다 —
max-stat 은 G1(선택창 효과크기) 하나만 보고, 통과율은 G2·G3·G6 까지 요구한다. **사전고정 문구가 max-stat 기준이므로 그것을 따른다**
(결과를 보고 더 유리한 기준으로 갈아타면 사전고정이 무의미해진다). 이 긴장 자체를 리포트·wiki 에 남긴다.

## 13) 이 작업이 바꾸지 않는 것

- 라이브 파라미터(`TradingProperties`·compose·`.env`).
- `BacktestConfig` **기존** 필드의 기본값(`M1ReplayBiasTest`·`BacktestLegacyGoldenTest`·`KneeStrategyComparisonTest`·`/backtest` 모집단이 조용히 달라진다).
- `BacktestFixtures.Regime`·`YearlyFixtures.MARKETS` 구성.
- `YearlyStrategyComparison` 의 산출 결과(추출 리팩터는 동작 보존 — 골든 대조로 확인).

# Key Files

- `bot/src/test/kotlin/com/trading/bot/engine/YearlyStrategyComparison.kt` — 지표 단일 소스. `SwingMetrics` 로 추출 후 재사용.
- `bot/src/test/kotlin/com/trading/bot/engine/YearlyFixtures.kt` · `BacktestFixtures.kt` — fixture 로더(yearly / bear·bull·paired).
- `bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt` — `BacktestConfig`(신규 노브·`init` require), `simulateTrades`(50봉 window), `processEntry`(marketFilter MA 클램프), `processExit`.
- `bot/src/main/kotlin/com/trading/bot/engine/IntrabarExitModel.kt` — 게이트 단일 소스. `exitLevels()` 신설 지점.
- `bot/src/main/kotlin/com/trading/bot/engine/M1ReplayEngine.kt` — `evaluate` 의 다른 호출자. `require` 가드 추가 지점(38행에 선례).
- `bot/src/main/kotlin/com/trading/bot/api/StrategyController.kt` — `/backtest` 는 `BacktestRequest` DTO 경유(신규 필드 미노출 확인).
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestEngineTest.kt` (185~203) — parity 가드(신규 노브마다 한 줄 추가).
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestLegacyGoldenTest.kt` — 기본 경로 trade 단위 골든.
- `common/src/main/kotlin/com/trading/common/strategy/Indicators.kt` — ATR 추가 지점.
- `bot/src/test/kotlin/com/trading/bot/engine/ParameterSweepTest.kt` — 대체·삭제 대상(Decisions 9).
- `bot/src/test/resources/backtest/README.md` · `wiki/pages/concept/backtest-engine.md` · `wiki/index.md` — 문서 동기화 대상.

# Blockers

없음.

# Acceptance

1. **사전고정이 감사 가능하다** — G1~G7·그리드·seed 목록·런타임 예산·축소 규칙이 담긴 이 plan 이 **결과 실행 전에 커밋**됐고(git 이력), 게이트가 순수 함수로 구현돼 각 경계가 단위 테스트로 고정된다.
2. **신호 캐시 동치성** — 캐시 유/무가 trade 단위 동일. **마켓 충돌 케이스**(같은 날짜, BTC/ETH 상이 신호) 포함.
3. **null 대조군이 순수하다** — 같은 (seed, market, 봉) 이면 exit config 와 무관하게 같은 진입. 단위 테스트로 고정.
4. **Stage A 산출물** — `build/reports/strategy-search-yearly.md` 에 명목 config 수 / **고유 행동 수** / 게이트별 탈락 수 / 통과 후보표(선택·검증·bull·bear·MDD·거래수·노출·손익분기승률) / null 분위수·max-statistic 이 있다. **통과 0건이어도 `0 / N` 과 게이트별 탈락 수를 출력**(테스트로 강제).
5. **Stage B 산출물** — 레짐필터 기간·ATR·부분익절 각각 같은 표 형식. 신규 노브가 기본값에서 기존 결과를 바꾸지 않음을 `BacktestLegacyGoldenTest` + parity 가드로 확인.
6. **결론이 증거와 함께** — 통과 후보가 있으면 null 분위수 대비 위치·한계와 함께, 0건이면 "이 공간에 없다"를 표본·게이트와 함께. 추측 기반 권고 0건.
7. **문서 동기화** — `wiki/pages/query/` 신규 페이지 + `wiki/index.md` 등재 + fixture README 소비자 목록 + `backtest-engine.md` 의 `ParameterSweepTest` 언급 갱신. wiki 검증 3종 통과.
8. **검증** — `JAVA_HOME=<jbr-21> ./gradlew :bot:test` 통과(`common` 은 test source set 이 없어 `:bot:test` 가 지표 테스트까지 덮는다). 무거운 스윕은 env 게이트 뒤, **게이트를 켠 실행에서 "실행 N건 / skip 0"** 을 로그로 남긴다([[lesson-skip-is-not-pass]]).
9. **라이브 무변경** — `TradingProperties`·`deploy/` diff 0.
10. **롤백 기준** — 신규 필드·분기를 되돌리면 `BacktestLegacyGoldenTest` 통과 + M1 replay parity 복원. 리포트에 code SHA·fixture 해시·grid hash·seed·JVM/Gradle·실행 명령을 남겨 재현 가능하게 한다.

# Deferred

- 추적형 ATR 스탑(보유 중 ATR 재계산해 스탑 이동) — 트레일링과 축이 겹쳐 해석이 섞인다. (중간·조사)
- `marketFilterMaPeriod > 50` — 엔진 window 확장이 필요하고 워밍업이 바뀌어 선행 측정과 비교 불가가 된다. (중간·설계)
- 블록 부트스트랩(paired 일별 equity-delta moving block) — null 대조군보다 정교하지만 이번 범위 밖. (낮음·통계)
- 포지션 사이징·포트폴리오·마켓 선택 — 엔진이 단일티커·단일포지션·all-in 이라 구조상 불가. 결론 문구를 "고정 8마켓·고정 노셔널·D1 단일포지션 엔진의 진입/출구/필터 공간"으로 좁혀 서술한다. (높음·별도 엔진 필요)
- fixture 무결성 사전검사(봉 수·날짜 결측/중복·OHLC 불변식) — 3 fixture 세트를 한 하네스가 처음 동시 소비하므로 있으면 좋다. 최소형(봉 수·날짜 단조)만 이번에 넣고 나머지는 후속. (낮음)

# Review Disposition

**plan-reviewer + codex (2026-09-04)** — Critical 6 / Major 8 / Minor 6

| 지적 | 처분 | 반영 위치 |
|---|---|---|
| C1 `marketFilterMaPeriod=100` 실행 불가 | fix | Decisions 5 (그리드 {10,20,50} + `init` require) |
| C2 캐시 키에 마켓 없음 | fix | Decisions 3 (키 6요소 + 마켓 충돌 재현 테스트) |
| C3 bear ⊂ yearly | fix | Decisions 1 (G4a bull=시간독립 / G4b bear=robustness) |
| C4 전략 축소가 FULL 순위 유래 | fix | Decisions 3 (9전략 유지, 축소는 SELECT 순위만) |
| C5 arm×trail alias 37.5% | fix | Decisions 3 (conditional grid + fingerprint dedup) |
| C6 죽은 wiki 링크·손익분기식 오적용 | fix | Decisions 12 |
| M1 paired 미사용·임계 무근거 | fix | Decisions 1 (paired delta, "운영 최소효과"로 재명명) |
| M2 seed 1개 | fix | Decisions 2 (seed 1..100, max-statistic 분위수) |
| M3 G2 가 동전던지기 | fix | Decisions 1 (양수 마켓 ≥5/8 + 기대통과율 명시) |
| M4 Stage B 그리드 미정의·trail off 부재 | fix | Decisions 3·4 (trail OFF 센티널, Stage B base config 고정, G3 미적용) |
| M5 ATR 의 D1/M1 불일치 | fix | Decisions 6 (`exitLevels` + M1 require) |
| M6 부분익절 intrabar 순서·노출 미보정 | fix | Decisions 7 (순서 명시, 노출 잔여비중 보정) |
| M7 벤치마크가 사전고정 훼손 | fix | Decisions 3 (synthetic 벤치마크, 실행 전 커밋) |
| M8 `kValue` 누락·비용 민감도 부재 | fix | Decisions 3 (kValue 축) · Decisions 1 (G7) |
| Minor TP/trail OFF 미정의 | fix | Decisions 3 (하네스 상수 1000.0) |
| Minor `/backtest` 계약 안전 | fix(기록) | Decisions 8-4 |
| Minor skip 0 문언 충돌 | fix | Acceptance 8 |
| Minor `:common:test` 추가 | false-positive | `common` 에 test source set 이 없다(지표 테스트는 `bot/src/test/.../strategy/`) — Acceptance 8 에 근거 기록 |
| Minor baseline 서술 모순 | fix | Decisions 1 (BASELINE_CONFIG 상수) |
| Minor MDD 단위 | fix | Decisions 1 (예산 100 기준 절대 %p) |
| Minor 리포트 metadata | fix | Acceptance 10 |
| 누락: 0건 리포트 강제 | fix | Acceptance 4 |
| 누락: 캐시 race | fix | Decisions 3 (순차 실행, 동시성 미도입) |
| 누락: fixture 무결성·NaN | 부분 fix | 최소형만(봉 수·날짜 단조), 나머지 `# Deferred` |
| 누락: 국면 창 G5 | fix | Decisions 1 (150거래봉에도 ≥8 적용) |

**architecture-reviewer (planning, 2026-09-04)** — Critical 3 / Major 6 / Minor 3

| 지적 | 처분 | 반영 위치 |
|---|---|---|
| C1 M1 replay `require` 가드 | fix | Decisions 6·8-3 |
| C2 `marketFilterMaPeriod` 클램프 | fix | Decisions 5 (plan-reviewer C1 과 동일) |
| C3 null 대조군 순차 RNG 금지 | fix | Decisions 2 (순수 해시) |
| M1 ATR 임계 2원화 | fix | Decisions 6 (`exitLevels` 단일 함수) |
| M2 신규 노브 4중 가드 | fix | Decisions 8 |
| M3 `BacktestTrade` 불변식 | fix | Decisions 7-2·7-6 |
| M4 `SwingMetrics.measure` 추출 | fix | Decisions 10 |
| M5 `Grid`/`GridPoint` 좌표 타입 | fix | Decisions 11 |
| M6 캐시 키 우연 의존 | fix | Decisions 3 |
| m1 `ParameterSweepTest` 처분 | fix | Decisions 9 (삭제) |
| m2 baseline 상수 | fix | Decisions 1 |
| m3 ATR 을 `common` 에 (승인) | 수용 | Decisions 6 (KDoc 3줄 명시) |

# Workflow Findings
