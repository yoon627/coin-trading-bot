---
title: strategy-improve — 매도·매수 전략 전체 점검 후 개선/수정 (#27 Structural)
status: in_progress
started: 2026-06-11
updated: 2026-06-11
---

# Goal

이슈 #27(수익성 감사) Structural 핵심 — **청산 구조 결함(trailing dead·R:R 0.4·1일 강제청산)을 파라미터화로 해소 가능하게 만들고, 백테스트를 라이브와 정합**시켜 파라미터 선택을 백테 게이팅으로 검증한다. 라이브 봇(KRW-BTC/`combined`) 가동 중: **코드 디폴트는 현행 행동 비트단위 보존**, 실제 동작 변경은 백테 결과 본 뒤 env 점진 + 소액 카나리아로만.

# Progress

- 2026-06-11: worktree `strategy-improve` 생성(base 2223503). dlc 시작 — 규모 structural. Explore 완료: TradingEngine·PositionManager·DailyResetManager·TradingState·CombinedStrategy·BacktestEngine·StrategyController·TradingProperties 코드 확인 — 이슈 #27 진단(trailing dead·보유 1일·백테 부정합) 전부 코드와 일치 검증.
- 2026-06-11: arch planning(REQUEST CHANGES→반영)·plan-reviewer(CONDITIONAL GO→조건 3건+minor 반영). TDD Red 확인(미구현 API 사유) → 구현 → Green(영향 4클래스 + bot 전체 통과). 커밋 06224fc (코드 16파일: ExitGates 신규·TradingProperties 2필드·DailyResetManager 일반화·BacktestConfig 정합·env 체인 5곳·README·스윕 러너). 커밋 게이트 hook 의 매직넘버 3건 수정 반영.
- 2026-06-11: 파라미터 스윕 실행(실데이터 200봉, 1,800조합) — 결과는 위 "스윕 결과" 섹션. arch 정밀·code-reviewer(codex 병행) 모두 APPROVE(Critical/Major 0) → fix loop 1회차 커밋 94eea8b. code-simplifier 적용 0건(제안: BacktestConfig dead field 후속 이슈). 최종 검증(격리 runner) `gradlew build` 통과 — 41 스위트 361 테스트 실패 0, 스킵 1(스윕 게이트).

# Next

**PR #30 머지 대기** (https://github.com/yoon627/coin-trading-bot/pull/30). 머지 후:
1. plan done 전환 + worktree 정리 (Windows long-path 주의)
2. env 적용 여부 — **사용자 결정** (보수안 `TRADING_MAX_HOLD_DAYS=3`+`TP=4`+`SL=3` / 공격안 TP8/SL5/trail3/hold999, 카나리아 `TRADING_MAX_INVEST_AMOUNT` 축소 전제, 무포지션 시점 배포)
3. 후속 이슈 등록 완료: #31(백테 신호 config 분리+dead field), #32(UI 백테 파라미터 노출), #33(highPrice peak)

# Decisions

## 코드 검증 결과 (이슈 #27 ↔ 코드 일치 확인)

1. **trailing dead branch 확정** — `decideSell` 우선순위 SL>TRAIL>TP(`TradingEngine.kt:227-234`), TP=TRAIL=2.0 동일값. 가격이 +2% 에 닿는 그 tick 에 TP 가 즉시 발화하므로(트레일은 peak 대비 -2% drop 이 필요한데 peak 형성 tick 에서 TP 선청산) 트레일링은 사실상 도달 불가. 유일한 예외는 매도주문 실패 후 가격 하락 등 엣지뿐.
2. **R:R 0.4 확정** — TP +2 / SL -5 (`TradingProperties.kt:12-14`). 손익분기 승률 ≈71%.
3. **보유지평 ≈1일 확정** — `DailyResetManager.shouldSellForDailyReset`: `buyDate < tradingDate`(KST 09:00 경계) → 다음 거래일 강제청산. 추세 절단.
4. **백테 잔여 부정합 확정** — `StrategyController.kt:89-90`: `maxHoldDays ?: 7`(라이브 1일), `useMarketFilter ?: true`(라이브 MA50 필터 없음). TP/SL/TR 는 PR #28 에서 정합됐으나 이 2개가 남음. `BacktestConfig` 클래스 디폴트(TP5/SL3/7d/filter-on)도 라이브 정반대.
5. (참고) `syncPosition` 은 buyDate/peakPrice/entryStrategy 미복원(`PositionManager.kt:32-44`) — 재시작 시 그 포지션은 DAILY_RESET 면제 + 트레일 고점 리셋. 포지션 영속화(#27 별항)는 이번 범위 밖.

## 이번 PR 스코프 (행동 불변 파라미터화 + 정합)

A. **`trailingArmPct` 도입** (`TradingProperties`, 기본 0.0 = 현행 동일)
   - `checkTrailingStop` 게이트를 일반화: 기존 `pnl > 0` 유지 + `peak 의 pnl ≥ trailingArmPct` 조건 추가. arm=0 이면 pnl>0 일 때 peak≥avg 가 자명이라 **현행과 동일**(테스트로 고정). armed 플래그 없는 무상태 판정(peakPrice 단조증가로 sticky 와 동치 — arch 승인).
   - **arm 조건식은 common 순수 함수로 공용화** (arch 권고): `ExitGates.isTrailingStopTriggered(pnlPct, peakPnlPct, dropFromPeakPct, trailPct, armPct)` — PositionManager·BacktestEngine 양쪽이 같은 함수를 호출해 "같은 조건식"을 컴파일 사실로 만든다. 전면 ExitPolicy 추출은 과추상화로 **반려**(시간청산 의미론이 라이브 KST 경계 vs 백테 인덱스 차로 다름).
   - 목적: env 로 "TP 크게 + arm 후 trailing 익절" 구성이 가능해져 trailing dead 해소 경로 확보. TP off 는 별도 플래그 대신 takeProfitPct 를 큰 값으로 env 설정(스윕 결과로 권고).
   - **arm 실효 조건(수학)**: pnl>0 ∧ drop≥trail ⇒ peakPnl > trail/(1−trail/100) 이 강제되므로 **arm ≤ trail 이면 arm 은 자동 충족(무의미)** — arm 은 `armPct > trailingStopPct` 로 설정해야 실효. 테스트 수치도 arm(5) > trail(2) 로 구성.
   - 엔진 시작 시 config 일관성 WARN: (a) `trailingArmPct >= takeProfitPct` → trailing 여전히 dead, (b) `0 < trailingArmPct <= trailingStopPct` → arm 무의미 (arch minor + 수학 검증).
B. **`maxHoldDays` 도입** (`TradingProperties`, 기본 1 = 현행 동일)
   - `DailyResetManager.shouldSellForDailyReset` 을 `ChronoUnit.DAYS.between(buyDate, tradingDate) >= maxHoldDays` 로 일반화. N=1 ⇔ 현행 `buyDate < tradingDate`(테스트로 고정). DailyResetManager 에 TradingProperties 통째 주입(기존 PositionManager 패턴, `UserTradingManager.kt:183`).
   - **Clock 주입 동반** (arch 요구): `DailyResetManager(props, clock: Clock = Clock.system(KST))` — `now(kst)` 하드코딩으론 테스트 전략 (c) 09:00 경계가 결정적으로 테스트 불가. default 로 행동 불변.
   - env 오설정 방어: 사용 지점에서 `maxHoldDays.coerceAtLeast(1)` (0/음수면 매수 당일 즉시 청산 루프 — 일 1회 왕복 수수료 손실 방지).
   - SellReason 은 `DAILY_RESET` 유지 (DB 레코드 연속성. 백테 명칭 TIME_EXIT 와 불일치는 수용 — 정합 대상은 동작).
   - 라이브-백테 동치 전제: Upbit D1 = KST 09:00 앵커라 캔들 인덱스 차 ≈ 거래일 차 (타 거래소 추가 시 재검토).
C. **백테-라이브 정합 마무리**
   - `StrategyController` 폴백: `maxHoldDays ?: tradingProperties.maxHoldDays`, `useMarketFilter ?: false`(라이브에 MA50 필터 없음). `trailingArmPct` 요청 파라미터 추가(폴백 `tradingProperties.trailingArmPct`).
   - **UI 영향 고지** (plan-review 조건 1): frontend 는 `maxHoldDays`/`useMarketFilter` 를 미전송(`screens.jsx:383` 확인) → 같은 입력의 UI 백테 결과가 "7일 보유+MA50 필터"→"1일 청산+무필터"로 전면 변경(과거 결과와 재현성 단절). **의도된 정합** — PR 본문·이슈 #27 에 고지(#28 TP/SL 선례). BacktestPage 에 신규 파라미터 입력 노출은 후속 이슈로.
   - `StrategyController.kt:82-83` 의 "클래스 디폴트는 별개" 주석은 C 로 거짓이 됨 — 구현 시 주석 갱신(plan-review #9).
   - `BacktestConfig` 클래스 디폴트도 라이브값으로 정합(TP 5→2, SL 3→5, maxHoldDays 7→1, useMarketFilter true→false, trailingArmPct 0.0 추가) — #28 의 "클래스 디폴트 별개" 결정을 변경(이유: 이번 스윕 D 가 controller 를 안 거치는 직접 생성 소비자를 실제로 만들므로 디폴트 부정합이 권고안을 오염시킴 — arch 조건부 승인).
   - **parity 테스트 추가** (arch 요구): `BacktestConfig()` 대응 필드 == `TradingProperties()` 필드 단언 — 디폴트 이중 진실의 drift 를 CI 가드(이 drift 가 #27 부정합의 근본 원인). 단 `BacktestConfig.kValue`/`investRatio` 는 엔진이 읽지 않는 dead field 라 parity 대상 제외(simplifier 단계에서 제거 검토).
   - **함정 기록**: `BacktestEngine` 의 전략 신호(`shouldBuy/shouldSell`)는 `config` 가 아닌 **live `tradingProperties` 고정** — controller 가 검증한 `kValue` 는 무시됨(기존 결함). 이번 스윕 차원(TP/SL/TR/arm/maxHold/filter)은 전부 engine-level 이라 무사하나, **진입 파라미터 스윕 전 선결 수정 필요** — 후속 이슈로 등록 예정.
   - 신규 키 노출(TRADING_TRAILING_ARM_PCT, TRADING_MAX_HOLD_DAYS) — env 전달 체인은 **5곳**(plan-review #6): `application.yml` + `deploy.sh` render + `docker-compose.prod.yml` environment + 루트 `.env.example` + `deploy/aws/.env.example`.
   - **동반 수정**: `TRADING_CHART_EXIT_ENABLED` 가 deploy.sh render·compose·양쪽 .env.example 에 전부 누락(기존 결함 — prod 에서 chartExit 켤 통로 부재). 같은 섹션을 만지므로 이번에 체인 5곳에 함께 추가(#28 의 "env 넣어도 컨테이너에 안 닿음" Critical 과 동일 클래스).
D. **파라미터 스윕 (구현 후 분석 산출물)**
   - KRW-BTC 일봉 200개(Upbit 공개 quotation API, 키 불요 — `UpbitClientImpl.kt:38-44` 확인)로 TP×SL×TR×arm×maxHold(×marketFilter on/off) 조합 백테 스윕 → 결과 표 + 권고안을 plan/이슈 #27 에 기록.
   - **실행 형태** (plan-review #11): JUnit5 `@EnabledIfEnvironmentVariable(named="RUN_SWEEP", matches="true")` 테스트로 커밋 — CI/기본 빌드에선 skip, 수동 실행 전용. 결과에 데이터 기간(첫/마지막 캔들 일자) 명시.
   - **과적합 가드** (plan-review 조건 2): 실질 ~150봉(워밍업 50 제외)·단일 국면(Upbit 200개 상한) 표본으로 5~6차원 그리드 — 권고안은 **"참고용: 순위·민감도(plateau)만, 절대 수익률 신뢰 금지"** 명시 + 전반/후반 분할 일관성 체크 + 상위 후보는 카나리아 검증과 연결.
   - **해석 한계 명시** (arch 요구 + plan-review #7·#8): (a) 백테 peak 은 일봉 **종가** 기준(`highPrice` 미사용)이라 trailing/arm 차원은 라이브(tick 단위 peak) 대비 체계적 편향, (b) 라이브 DAILY_RESET 은 09:00 직후(≈시가) vs 백테 TIME_EXIT 는 종가 체결 — maxHold 차원도 편향, (c) 00:00~09:00 매수분은 캘린더일/거래일 비대칭으로 N>1 에서 백테와 1일 어긋남. 권고는 보수적으로 해석, highPrice 기반 peak 도입은 후속 이슈 후보.
   - **카나리아 정의** (plan-review #12): env 적용 시 `TRADING_MAX_INVEST_AMOUNT` 를 소액(예: 10,000 KRW)으로 낮춰 일정 기간 관찰 후 원복 — 권고안에 절차 포함.
   - 적용(env 변경·배포·카나리아)은 **사용자 결정** — 이 PR 은 코드 디폴트 행동 불변.
E. **범위 제외** (이유 명시)
   - 진입(매수) 로직 변경(combined RSI 상한·MA50 라이브 도입): 스윕 데이터 수집까지만, 변경은 후속 (백테 근거 없이 신호 변경 금지).
   - 미완성 당일봉 제외·MACD 윈도우: C3(`c3-marketdata-accuracy` worktree, WIP)와 같은 PR 조율 권고(#27) — 별도.
   - 포지션 영속화(buyDate/peakPrice 복원): #20 연관 별항.

## 테스트 전략 (TDD)

- ExitGatesTest(common, 신규): arm 조건식 순수 함수 단위 테스트 — arm=0 동치(pnl>0 이면 기존과 동일)·미arm false·arm 후 trail 발화·pnl≤0 false·경계(arm 정확히 도달)·avgBuyPrice=0 유래 비정상 입력(NaN/0%) false (plan-review #4).
- DailyResetManagerTest: (a) maxHoldDays=1 → 현행 동작 고정(당일 false / 익일 true / buyDate null false), (b) maxHoldDays=3 → 2일째 false·3일째 true, (c) Clock 주입으로 09:00 경계 결정적 테스트, (d) maxHoldDays=0 → coerce 로 1 과 동일.
- PositionManagerTest(checkTrailingStop): (a) arm=0 → 기존 회귀(현행 케이스 전부 동일) + 소수익 트레일 발화 케이스 명시 핀(arm>trail 이었다면 막혔을 입력), (b) arm=5·trail=2: peak +3%(<arm)·drop 2.2%·pnl>0 → false(미arm — 현행이면 매도였을 입력), (c) peak +6%(≥arm) 후 drop 2.3% → true, (d) current pnl≤0 → false(기존 게이트 유지). ※ (b)/(c)는 arm>trail 구성 — arm≤trail 은 수학적으로 무의미(Decisions A).
- BacktestEngine: TIME_EXIT 발화 시점(maxHoldDays=1 ⇔ 익일), arm 전 trailing 미발화.
- **parity 테스트**: `BacktestConfig()` 디폴트 == `TradingProperties()` 대응 필드(takeProfitPct/maxLossPct/trailingStopPct/trailingArmPct/maxHoldDays/chartExitEnabled — kValue·investRatio 는 dead field 라 제외) + `feeRate*2 == roundTripFeeRate` 단언(표현 상이, plan-review #10) + `useMarketFilter == false`(라이브 무필터 대응) 단언.
- maxHoldDays coerce 는 라이브·백테 **양쪽 동일 적용** (plan-review #5 — 라이브만 coerce 하면 parity 철학과 충돌): DailyResetManager·BacktestEngine 둘 다 사용 지점 `coerceAtLeast(1)`.
- StrategyController 폴백: 직접 단위 테스트 부재(#28 확인) — 컴파일+기존 회귀로 커버, 사유 기록.

## Rollback

- 단일 브랜치 PR — revert 로 원복(DB migration 없음, SellReason 불변). 코드 디폴트가 현행 행동 보존이라 revert 리스크 낮음.
- env 적용/원복(후속)은 **컨테이너 재기동 수반** (plan-review 조건 3): TradingProperties 는 부팅 바인딩. 재기동 시 in-memory 상태 유실 — `syncPosition` 이 buyDate/peakPrice 미복원이라 보유 포지션은 (a) maxHoldDays **영구 면제**(buyDate=null), (b) peak 리셋 = arm 해제(armPct>0 운영 시 trailing 무력화). → **무포지션 시점 적용 권장**(#28 과 동일). arm 실효성은 포지션 영속화(#20 별항) 전까지 재시작에 취약함을 권고안에 명시.
- 버전 스큐 안전: 새 코드+구 env = 디폴트(현행 동작) / 구 코드+신 env = 미지 키 무시 — 양방향 안전.

## 스윕 결과 (2026-06-11 실행, KRW-BTC D1 2025-11-24~2026-06-11 200봉, combined — **참고용**)

- **baseline(=현행 라이브 디폴트 TP2/SL5/trail2/arm0/1일/무필터): −10.98%** (16건, 승률 50%) vs buy&hold −32.18% — 하락장 방어는 했으나 절대 손실. 1일 강제청산이 주 원인(아래 상위 조합과의 차이가 보유지평).
- 상위 클러스터 2개 (전/후반 분할 일관성 양호):
  1. **TP8/SL5/trail3/hold무제한/무필터: +8.58%** (5건, 승률 60%, PF 1.80, maxDD 11.3%, 최근절반 +6.20%)
  2. **TP2/SL3/trail1.5~3/hold3/MA50필터: +5.31%** (9건, 승률 66.7%, PF 1.75, maxDD 4.16%, 최근절반 +6.59%) — DD 가 1/3 수준이라 리스크 조정 기준 우수
- **arm 차원은 이 데이터로 변별 불가** — 일봉 종가 peak 한계(plan 해석 한계 (a))로 arm 0~5 결과 동일. arm 권고는 보류, 라이브 tick 환경에서만 의미.
- 회피 영역: TP5/SL7/hold무제한 −21.5~−21.9%.
- 가드 재확인: 절대 수익률 신뢰 금지(~150봉 단일 하락국면), 순위·방향성만 참고. **방향성 결론: maxHoldDays 1→3+ 확대가 가장 영향 큰 개선, TP 는 trail 보다 충분히 커야 함(현행 TP2=trail2 는 trailing dead)**.
- 적용 후보(사용자 결정용, 카나리아 전제): 보수안 = `TRADING_MAX_HOLD_DAYS=3` + `TRADING_TAKE_PROFIT_PCT=4` + `TRADING_MAX_LOSS_PCT=3` / 공격안 = TP8/SL5/trail3/hold 무제한(=999).

# Review Disposition

arch planning (격리, codex off): 결정 1·2·3 승인, 4 조건부(parity 테스트+dead field 기록), 5 부분수정(ExitGates 순수 함수만 공용화, 전면 ExitPolicy 반려) — 전부 **fix**(plan 반영 완료). Clock 주입·스윕 해석 한계·백테 신호 live-props 함정 기록 — **fix**.

plan-reviewer (격리 Claude + codex 병행, CONDITIONAL GO):
- **fix**(plan 반영 완료): 조건 1 UI 백테 변경 고지 / 조건 2 스윕 과적합 가드 / 조건 3 rollback 재기동·무포지션·arm 취약성 / #4 ExitGates NaN 가드 테스트 / #5 coerce 양쪽 대칭 / #6 env 체인 5곳 + TRADING_CHART_EXIT_ENABLED 동반 수정 / #7·#8 스윕 해석 한계 추가 / #9 StrategyController 주석 갱신 / #10 parity feeRate·useMarketFilter 단언 / #11 스윕 실행 형태(JUnit env-gated) / #12 카나리아 정의.
- **기각**: codex 의 "arm=0 fast path 이중 경로" — 동치가 수학적으로 명확(pnl>0 ⇒ peakPnl>0≥0)하고 ExitGatesTest 가 고정하므로 이중 경로는 불필요 복잡도(plan-reviewer 도 기각 권고).
- 참고: plan-reviewer 의 codex 는 Windows sandbox 에러로 파일 read 실패 — 일반론 출력만 가중치 절하 통합됨. 메인이 별도 codex exec 백그라운드 실행 중 — 완료 시 신규 finding 만 추가 반영.

구현 후 리뷰 (11단계, 커밋 06224fc 대상 — 양쪽 모두 **APPROVE**, Critical/Major 0):
- architecture-reviewer(정밀, codex off): planning 요구 5건 구현 일치 검증(ExitGates 양쪽 호출·bean 주입·Clock·parity·무상태 arm), 중복 조건식 잔존 없음, 의존 방향 유지. Minor 3건.
- code-reviewer(codex owner — 1차 sandbox 실패 후 diff 임베드 2차 성공): 동치성 3경로(checkTrailingStop arm=0 / DailyReset N=1 / BacktestEngine) 코드 검증, env 체인 5곳 철자·디폴트 일치, 전체 테스트 PASS·sweep CI skip 확인. codex 의 "운영 알람 변화" Major 주장은 DiscordErrorLogAppender(ERROR 만 전송)로 반증되어 다운그레이드.
- fix loop 1회차 (커밋 94eea8b) — **fix**: WARN 문구 '항상' 완화 / drop==trail 경계 핀 / DailyResetManager props 디폴트 제거 / coerce 공용화(ExitGates.effectiveMaxHoldDays + 단위 테스트 — BacktestEngine 쪽 회귀 커버 겸함) / 스윕 .block 진단성·중첩 runBlocking / screens.jsx stale 주석.
- **defer**(후속 가능, 비차단): warn 판정식 순수 함수화(arch Minor C) / DailyResetManagerTest 구 테스트의 시스템 시간 의존(기존 결함 — 09:00 정각 부근 flaky 가능) / maxHoldDays 상한 비대칭(라이브 무상한 vs 백테 API 365).
- **수용(wontfix)**: env 음수 trailingArmPct 침묵(기존 props 검증 부재 패턴의 연장) / ExitGatesTest 의 bot 테스트 소스셋 배치(common test 소스셋 부재 — 기존 컨벤션, PR 본문에 사유 기록).

# Key Files

- `common/src/main/kotlin/com/trading/common/config/TradingProperties.kt` — trailingArmPct·maxHoldDays 추가
- `common/src/main/kotlin/com/trading/common/strategy/ExitGates.kt` — 신규: trailing arm 조건식 순수 함수(라이브·백테 공용)
- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — checkTrailingStop arm 게이트
- `bot/src/main/kotlin/com/trading/bot/engine/DailyResetManager.kt` — shouldSellForDailyReset 일반화
- `bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt:183` — DailyResetManager 주입
- `bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt` — arm 로직 + BacktestConfig 디폴트 정합
- `bot/src/main/kotlin/com/trading/bot/api/StrategyController.kt` — 폴백 정합 + trailingArmPct 노출
- `bot/src/main/resources/application.yml`, `deploy/aws/deploy.sh`, `deploy/aws/docker-compose.prod.yml`, `.env.example` — 신규 env 키 체인
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — (변경 없음 예상, decideSell 우선순위 유지)

# Blockers

(없음)
