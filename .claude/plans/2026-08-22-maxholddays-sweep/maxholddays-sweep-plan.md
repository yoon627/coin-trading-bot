---
title: maxholddays-sweep — 청산 보유상한(maxHoldDays)이 손익을 지배하는지 실거래 반사실로 검증
status: done
started: 2026-08-22
updated: 2026-09-02
---

# Goal

라이브 실측에서 청산의 **57%가 DAILY_RESET**(17/30)이라는 관측을 받아, 보유상한이 실제 손익에 얼마나 기여했는지 **실거래 반사실**로 확인한다. 백테 스윕은 국면 편향으로 답이 미리 정해지므로 폐기했다(아래 Decisions). **관찰 기록이며 파라미터 변경 근거가 아니다.**

# Progress

- 2026-08-22: 운영 DB(Vultr, 읽기전용) 실측 — SELL 30건(2026-06-02~08-22). 청산사유 DAILY_RESET 17(평균 -0.047%)·TAKE_PROFIT 9(+5.044%)·TRAILING_STOP 3(+1.172%)·STOP_LOSS 1(-3.272%). per-trade net 평균 +1.495%, sd 2.902, t=2.82(df 29). 소급 페어링 결과 combined 29건 +11,999원 / rsi_bounce 1건 +844원.
- 2026-08-22: Explore — `IntrabarExitModel.evaluate` 의 `atHoldLimit`→`TIME_EXIT`(bar.open)이 라이브 `DAILY_RESET` 대응축. `BacktestEngine.closeOpenPosition`(`:164-171`)이 미청산분을 `"END"` 로 강제청산해 결과에 포함.
- 2026-08-22: 국면 실측 — 라이브 200봉(2026-02-04~08-22) B&H 8개 중 6개 마이너스(평균 −6.6%; ADA −25.1 ~ LINK +20.9).
- 2026-08-22: **plan-reviewer(+codex medium) CONDITIONAL**, 강한 우려 7건. 메인이 원문 직접 검증해 **전부 사실 확인** — `backtest/README.md` "상관 평균 0.49(BTC/ETH 0.90) → 실효 독립 표본 2개 남짓" / `BacktestFixtures.kt` 8마켓 fixture + `inSample()`·`outOfSample()` 실재 / `knee-backtest-calibration-plan.md:92` `N_eff≈1.8`, `:201` "B4 표본 검정력 없음 → **fix** — Goal 을 '관찰 기록'으로" (PR #98 머지) / `StrategyController.kt:90` `coerceIn(1,365)` 로 999 도달 불가.
- 2026-08-22: 검증 중 reviewer 지적보다 근본적인 문제 확인 — **가용 백테 표본이 전부 하락장 한 국면**이라 "보유 늘리면 나빠진다"가 데이터가 아니라 산술로 결정된다. 사용자 결정으로 **실거래 반사실로 전환**.
- 2026-08-22: 반사실 실행 완료. DAILY_RESET 17건 + 대응 BUY 를 DB 에서 추출, Upbit 일봉으로 "안 팔았다면"을 계산. **sanity check 16/17 통과**(매도일 일봉 시가 ≈ 실제 매도가, 오차 <0.5%; DOGE 1건만 −0.96% — 원 단위 호가 104→103). balanced panel + 게이트 적용 2종 보정 후 결론 도출(아래 Findings).
- 2026-09-02: 브랜치에만 남아 있던 이 plan 을 main 에 보존하며 종결. Findings 는 2026-09-02 세션에서 사용자에게 보고했고, 후속(표본 축적 후 재판정)은 `2026-09-02-trade-performance-analysis` plan 이 승계한다 — 그쪽 실측(SELL 42건)에서 DAILY_RESET 은 25건·평균 −0.731% 로, 여기서의 "손익 중립(−0.047%)" 이 8/22 이후 8건에서 음수로 기울었다.

# Findings

## 1. 보유상한은 손익을 지배하지 않는다 (회계적 사실, 근사 0)

DAILY_RESET 17건 = 전체 청산의 57% 인데 **실제 순손익 합계 −729원**, per-trade 평균 −0.047%. 총 순이익 +12,843원 대비 기여도 ≈ 0.
→ **수익은 전부 TAKE_PROFIT(+5.044%, 9건)·TRAILING_STOP(+1.172%, 3건)에서 나왔고 DAILY_RESET 은 손익 중립**이다. 다만 거래의 57%·자본 회전을 소모한다.

## 2. "더 들고 있었으면 벌었을까" — 판정 불가 (결론이 n=3 에 좌우)

balanced panel(같은 표본만 비교) × 게이트 적용(실제 봇은 TP/SL/트레일링을 계속 평가):

| 연장 | +3일 패널(13건) 순보유 / 게이트 | +7일 패널(10건) 순보유 / 게이트 |
|---|---|---|
| +1일 | +0.76 / **+0.82** | +0.11 / **+0.25** |
| +2일 | +2.31 / **+1.29** | +0.13 / **−0.06** |
| +3일 | +2.77 / **+1.25** | −0.51 / **−0.10** |
| +5일 | — | −1.68 / **−0.22** |
| +7일 | — | +0.19 / **+0.26** |

- **게이트가 상방을 자른다.** 순보유 +18.92%p(SOL id 44)가 게이트 적용 시 +7.75%p. "보유 늘리면 대박"은 TP 5%·트레일링이 있는 실제 봇에서 일어나지 않는다.
- **두 패널의 부호가 갈린다.** 같은 +2·+3일인데 13건 패널은 +1.3%p, 10건 패널은 −0.1%p. 차이는 최근 3건(08-20~22 급등에 걸린 SOL·BTC·DOGE)뿐이다. **단일 이벤트 n=3 이 결론을 만든다 → 판정 불가.**

## 3. 방향성 관찰 — 보유 연장은 TIME_EXIT 을 STOP_LOSS 로 바꾼다

게이트 적용 시 청산사유(관측 가능 건만): 연장 +1일 TIME_EXIT 9 / SL 1 → +3일 TIME_EXIT 2 / SL 4 → +7일 TIME_EXIT 0 / SL 5. 이 하락 국면에서 보유 연장은 손절 노출을 늘렸다.

# Next

없음 — 종결. 후속은 `2026-09-02-trade-performance-analysis` plan(`# Next` 재조회 트리거) 참조. `# Deferred` 의 항목 중 "매도 기록에 전략이 안 남는다" 는 V21 backfill(그쪽 plan Key Files)로 해결됐고, "7월 3건 보유상한 초과" 는 그쪽 `# Deferred` 로 이관.

# Decisions

- **백테 maxHoldDays 스윕은 폐기.** 이유: 가용 표본(fixture·라이브 200봉)이 전부 하락장 한 국면이라 "보유 늘리면 나빠진다"가 국면의 산술로 미리 결정된다. 그 위에 `N_eff≈1.8`(마켓 상관 0.49) 이라 CI 로 방어할 수도 없다. `knee-backtest-calibration` 이 같은 이유로 Goal 을 하향한 선례(PR #98)를 따르되, 여기서는 **설계 자체를 교체**했다.
- **실거래 반사실로 전환** (사용자 승인 2026-08-22). 매도 시각이 전부 00:00 UTC = 09:00 KST = Upbit 일봉 경계이므로 "N일 더 보유" = **D+N 일봉 시가 청산**이고, 이는 `IntrabarExitModel` 의 `TIME_EXIT = bar.open` 과 동일 시맨틱이다 — 순보유 반사실은 **근사가 0**이다. 진입 집합이 고정이라 reviewer 의 S4(pairing 불성립)·S5(END)·S6(intrabar 축별 교란)이 소멸한다.
- **보정 2종 필수**: ① balanced panel — 지평선마다 표본이 달라지면(N 이 클수록 최근 거래가 빠진다) 열 비교가 무효다. ② 게이트 적용 — 순보유만 보면 TP 5% 에서 잘릴 것을 +20%로 계산해 상방을 과대평가한다. 둘 다 없으면 "+2일이 최고(+4.23%p)"라는 허구가 나온다(실제로 1차 계산에서 그렇게 나왔다).
- **게이트 적용분은 intrabar 근사**(SL@low·TP@high·무슬리피지)가 들어간다 — 순보유(근사 0)와 **함께** 읽어야 한다. 트레일링 peak 은 매수일~매도일 일봉 고점으로 근사(실제 `peakPrice` 는 청산 시 소멸).
- **범위 밖 — 파라미터 실제 변경은 이번 작업이 아니다.** 앱 코드만 고치면 안 되고 배포 계층(`deploy/vultr/docker-compose.prod.yml` 의 `${VAR:-기본값}`)까지 확인 후 재배포·로그 검증이 필요하다(PR #74/#75 실사고).

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/IntrabarExitModel.kt` — 반사실 게이트 판정이 재현한 원본(순서 TRAILING→SL→TP→TIME_EXIT, 한도봉 open 특례)
- `bot/src/main/kotlin/com/trading/bot/engine/DailyResetManager.kt` — 라이브 DAILY_RESET 판정
- `common/src/main/kotlin/com/trading/common/config/TradingProperties.kt` — `maxHoldDays=1`, `takeProfitPct=5.0`, `trailingStopPct=2.0`, `trailingArmPct=3.0`
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt` + `bot/src/test/resources/backtest/README.md` — 재현 가능 fixture·국면 한계 기록(백테 재개 시 여기서 시작)
- 분석 스크립트(scratchpad, 미커밋): `counterfactual.py`(순보유) / `counterfactual2.py`(balanced panel + 게이트) / `daily_reset.tsv`(DB 추출 17건)

# Blockers

없음. 원래 설계의 blocker(국면 편향)는 설계 교체로 해소.

# Acceptance

- [x] DAILY_RESET 17건의 실제 손익 기여를 근사 없이 산출 — **−729원 / per-trade −0.047%** (DB 실측)
- [x] 반사실의 데이터 정합 검증 — sanity check 16/17 통과(매도일 일봉 시가 ≈ 실제 매도가, DOGE 1건 −0.96%는 원 단위 호가 기인으로 설명됨)
- [x] 표본 편향 보정 — balanced panel 2종(13건·10건)으로 열 비교 가능하게
- [x] 실제 봇 동작 반영 — TP/SL/트레일링 게이트 적용분 병기, 순보유와 대조
- [x] 청산사유가 어떻게 바뀌는지 산출 — TIME_EXIT→STOP_LOSS 전이 확인
- [x] 판정 불가면 유보로 결론 — 결론이 n=3(단일 급등 이벤트)에 좌우됨을 근거와 함께 명시
- [x] 해석 한계 명시 — 게이트분 intrabar 근사, 하락 국면 단일, 17건, 재진입 기회손실 미반영

# Deferred

- **7월 3건이 보유상한을 3거래일 초과했다** — `maxHoldDays=1` 인데 id 11(BTC, 매수 07-17 → 매도 07-21), id 13(ETH, 07-19 → 07-22), id 14(BTC, 07-21 → 07-24)이 3거래일 뒤에 DAILY_RESET 으로 청산됐다. 8월 건들은 전부 1거래일로 정상. 봇 중지·장애·배포 공백 가능성. 심각도 중(보유상한이 의도대로 안 걸린 구간이 있다는 뜻). 파일: `bot/src/main/kotlin/com/trading/bot/engine/DailyResetManager.kt`.
- **매도 기록에 전략이 안 남는다(P0)** — `PositionManager.buildSellRecord()` 가 `strategy` 인자를 누락해 SELL 30건 전부 `strategy=NULL`. `/api/strategies/performance` 가 `?: "unknown"` 으로 흡수해 은폐 — 이 API 는 만들어진 이래 전략별 손익을 보여준 적이 없다. 과거분은 티커별 BUY→SELL 페어링으로 100% 소급 복구 가능(교차위반 0건 실측).
- `trade_executions.pnl_amount`·`fee` 가 항상 NULL/0 — `TradeExecutionService.saveAudit()` 이 값을 안 넘긴다.
- `ParameterSweepTest` 가 all-in 복리 `totalReturnPct` 로 상위 조합을 정렬 — `strategy-evolution-expectations` 의 "per-trade net pnl% 로 통일, 복리 총수익 비교 금지" 원칙과 어긋남.
- 백테 경로를 재개한다면 reviewer 지적 반영 필요: `BacktestFixtures` 사용(라이브 fetch 는 미완성봉으로 재현 불가), IS/OOS 분할, END 분리, `999` 대신 유한 축(API `coerceIn(1,365)`), 통계 유틸은 test source `internal object` 추출 + 단위 테스트.
