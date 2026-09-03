---
title: yearly-strategy-compare — 운영 티커 8종 1년 일봉으로 전략 9종·적립·단순보유 비교(8/4개월 분할)
status: in_progress
started: 2026-09-03
updated: 2026-09-03
---

# Goal

운영 티커 8종(BTC·ETH·XRP·SOL·DOGE·ADA·AVAX·LINK)의 최근 1년(365봉) 일봉을 fixture 로 고정하고, 스윙 전략 9종(라이브 기본 리스크 파라미터) + 적립 사다리(기본 5/3/3) + 단순보유를 **같은 예산·같은 수수료**로 백테해 예산 대비 순수익률·MDD 로 비교한다. 앞 8개월(선택)·뒤 4개월(검증) 분할로 "선택 구간의 1등이 검증 구간에서도 유지되는가"를 본다. 결과는 표로 보고 — **프로덕션 코드 변경 없음**(수집 스크립트·테스트 소스·fixture·문서만).

# Progress

- 2026-09-03: 사용자 결정 3건 수령 — 코인 = 현 운영 8종 / 알고리즘 = 전략 9종 + 적립 사다리 + 단순보유 / 지표 = 예산 대비 순수익률 + MDD, 8/4개월 분할. worktree `yearly-strategy-compare` 생성. 가정: 코인당 예산 10만원(적립과 동일), 편도 수수료 0.05%, 스윙은 `TradingProperties` 기본값(TP5/SL5/trail2/arm3/hold1) — 파라미터 스윕은 범위 밖.

- 2026-09-03: fixture 8종 × 365봉 수집·기록(`scripts/collect_yearly_fixtures.py --write`, 2025-09-03 ~ 2026-09-02, 결측 0). `YearlyFixtures` 로더 + 테스트 1건 통과. plan-reviewer(Claude 단독, codex 크레딧 소진) CONDITIONAL 16건 → 전부 수용해 Decisions 2~4 개정(`[pr-N]`).

- 2026-09-03: 하네스 `YearlyStrategyComparison`(+테스트 4건, 게이트 `RUN_YEARLY_COMPARE`) TDD 로 구현·실행 — 게이트 on 4건 실행/skip 0, 산출물 `bot/build/reports/yearly-strategy-comparison.md`. **결과(전체 창 315봉, 8마켓 중앙값)**: 1 combined/live +4.52% · 2 volatility_breakout/live +3.54%(평균 +8.86, 거래 380) · 3 knee_pullback/live +1.72 · 4 macd_cross/live +1.57 · … · 17~18 bollinger_bounce −10.7 · 19 accumulate/5-3-3 −48.1(MDD 76.5, 노출 0.97) · 20 buy-and-hold −50.3(MDD 81.7). 마켓별 B&H BTC −36 / ETH −43 / XRP −49 / SOL −52 / DOGE −62 / ADA −72 / AVAX −66 / LINK −42 — 8종 전부 하락한 단일 국면. 선택(193봉) 1위 vb/legacy +7.28, 검증(122봉) 1위 vb/live +5.01, 전체 1위 combined/live 는 검증 11위(−0.43). 스피어만 ρ 0.32, 선택 상위3(동점 포함 4) 의 검증 상위절반 잔류 3/4(기준선 50%). **해석**: 최고 후보도 고정 노셔널 연 +3.5~5% 이며 실체는 저노출(2~12%) 회피, 적립·보유는 반토막, 순위 불안정 → 이 표로 승자 선언 금지(과적합). 상세는 wiki `query/yearly-strategy-comparison`.
- 2026-09-03: code-reviewer(Claude 단독) Major 1·Minor 6·Nit 9 → fix 14 / wontfix 2(아래 Disposition). 수정 후 게이트 재실행 6건/skip 0, 발표값 변동: MDD ±0.1(미실현에 수수료 선차감), 상위3 이 동점 포함 4 → 잔류 3/4, ρ 0.32 불변(재순위·평균순위 적용 후에도 동일).
- 2026-09-03: 문서 — fixture README `yearly/` 절, wiki query 페이지 + index + log + inbound 링크 2곳(accumulate-ladder·backtest-engine), `wiki/verify.sh` 상한 33±2→34±2(조정 이력 기재). A5 `git diff --stat 304f0b0 -- bot/src/main common/src/main` = 0. code-reviewer 는 Claude 단독(codex 크레딧 소진 2026-09-02 확인 — push 시 `CODEX_SKIP=1`, 근거 = 이 대체 검토).

# Next

code-reviewer 결과 처분(`# Review Disposition`) → 필요 시 수정·게이트 재실행 → 전체 테스트 재확인 → simplify 체크 → 커밋 → Report(마무리는 `/e merge`).

# Decisions

## 1) fixture — 별도 디렉토리 `yearly/`, 기존 `Regime` enum 은 건드리지 않는다

- `bot/src/test/resources/backtest/yearly/<market>.json`, 최신순 365봉(2025-09-03 ~ 2026-09-02, 09:00 KST 경계 완결봉만). 기존 fixture 와 같은 7키 정규화(`collect_backtest_fixtures.py` 의 `normalize`·`_get` 재사용).
- `BacktestFixtures.Regime` 에 항목을 추가하지 않는다 — `KneeRsiWindowTest` 등이 `Regime.entries` 를 순회해 기존 측정 모집단이 바뀐다. 로더는 테스트 소스 `YearlyFixtures` 로 분리.
- 유니버스는 **사용자 지정 8종**(시점 중립 선정 아님) — #112 의 look-ahead 논점은 "오늘의 승자로 과거를 소급"인데 여기서는 사용자가 이미 운용 중인 티커를 평가하는 것이라 문제 정의 자체가 다르다. 단 이 8종이 지난 1년 생존·상위인 것 자체가 생존편향임을 보고에 적는다.

## 2) 창 — 거래 구간을 스윙 워밍업(50봉)에 맞춰 세 계열 모두 통일 `[pr-5]`

시간순 index 0..364. `BacktestEngine` 은 넘긴 캔들의 앞 50봉을 워밍업으로 먹으므로 **거래 구간**을 기준으로 정의한다:

| 창 | 엔진 입력(시간순) | 거래 구간 | 거래봉 |
|---|---|---|---|
| 전체(헤드라인) `[pr-10]` | 0..364 | 50..364 | 315 |
| 선택 | 0..242 | 50..242 | 193 |
| 검증 | 193..364 | 243..364 | 122 |

적립·단순보유도 같은 거래 구간(50 부터)만 쓴다. "8개월/4개월" 은 입력 길이이고 실제 거래봉은 193/122 이다. 검증 창의 워밍업(193..242)은 선택 창 거래 구간과 겹치지만 신호를 내지 않는다(`inSample`/`outOfSample` 선례). 창 끝에서 열린 포지션은 `END` 강제 청산 — 122봉 창에서 그 1건의 비중이 크다(각주).

## 3) 지표 — 고정 노셔널 예산 대비, MDD 는 봉단위 equity 로 재계산 `[pr-1][pr-2][pr-6][pr-8]`

- **헤드라인 = 고정 노셔널 예산 대비 순수익률 = Σ 거래별 net pnl%**(`BacktestResult.trades`). `totalReturnPct`(all-in 복리)는 [[strategy-evolution-expectations]] 가 전략 줄세우기에 금지한 지표이고 라이브도 `maxInvestAmount` 고정 노셔널이라 복리가 아니다 — 참고열로만.
- **MDD 는 하네스가 세 계열 모두 봉단위 mark-to-market equity 곡선으로 재계산**(분모 = 예산). 스윙 `maxDrawdownPct` 는 청산 시점에만 갱신돼 미실현 낙폭을 빼먹고, 적립은 봉단위·예산 분모, B&H 는 종가 — 정의가 셋이라 그대로 한 열에 못 세운다. 스윙 equity 는 `trades`(buyIndex·sellIndex·buyPrice)와 캔들 종가로 복원: 보유 중 `100 + 실현누적 + (close/buyPrice − 1)×100`, 청산 봉에 `pnlPercent` 반영.
- **노출 열 필수**: 스윙 `Σ holdDays / 거래봉수`, 적립 `avgInvestedFraction`, B&H 1.0. 낙폭이 작은 것이 실력인지 노출이 작아서인지 가른다.
- **B&H 는 하네스가 한 번만 계산**(첫 거래봉 종가 → 마지막 종가, 왕복 0.1% 차감). `BacktestEngine.buyAndHoldPct` 는 수수료 미차감, `AccumulateBacktest` 는 차감이라 섞으면 두 값이 된다.
- **재진입 모드 두 열** `[pr-7]`: `LEGACY_NEXT_BAR`(기본, 2봉 강제 공백 — 라이브보다 보수적)와 `LIVE_SAME_BAR`(라이브 09:00 즉시 재매수 근사) 둘 다.
- **거래수 열 필수, 0거래 전략은 순위 제외 표기** `[pr-9]` — 신호가 없던 전략은 0%·MDD 0 이라 하락 구간에서 위로 올라온다. 창 내 총 거래수 < 8(마켓당 1건 평균)이면 순위에서 뺀다.
- **통계 주장 가드** `[pr-11]`: 11 후보 × 8 마켓, 실효 독립 표본 2~3. 스피어만 순위상관은 기술통계로만 적고 p-value·유의성·"승자" 선언 금지. "선택 창 상위 3 이 검증 창 상위 절반(5/11)에 남는가"의 무작위 기준선 ≈ 50% 를 병기.
- 적립은 **프로덕션에서 꺼져 있다**(`TRADING_ACCUMULATE_TICKERS` 미설정) — 결과는 기본값 5/3/3 의 백테이지 라이브 관측이 아니다 `[pr-14]`.

## 4) 수집·산출물·재현 `[pr-3][pr-4][pr-12][pr-15]`

- 수집: Upbit 요청당 200봉 상한 → `to`(앞 페이지 최고(最古) 봉 UTC) 로 2회 페이징, 365개 연속 달력 날짜 검증(중복·공백이면 아무것도 쓰지 않고 실패). 종료일은 스크립트 상수 `END_DATE = 2026-09-02` 로 고정하고 "현재 UTC 날짜 > END_DATE" 를 assert(오늘 09:00 KST 이전에 돌리면 형성 중인 봉이 섞이는 README 의 사고 재현 방지). 수집일·용량은 fixture README yearly 절에.
- 유니버스 근거: `deploy/vultr/.env:60 TRADING_TICKERS` 의 8종과 일치, `TRADING_UNIVERSE_AUTO` 미설정(env 기준 스냅샷 — UI 가 `bot_state.tickers` 를 덮을 수 있다) `[pr-13]`.
- 산출물: 비교는 `@EnabledIfEnvironmentVariable(RUN_YEARLY_COMPARE=true)` 게이트(`ParameterSweepTest` 선례) 뒤에서 돌고, 표를 **파일** `bot/build/reports/yearly-strategy-comparison.md` 로 쓴다(`println` 은 `testLogging` 설정이 없어 콘솔에 안 나온다). 실행 명령을 plan·wiki 에 기록하고 실행/skip 건수를 보고에 남긴다([[lesson-skip-is-not-pass]]). 게이트 없는 스모크 1건(마켓 1·전략 1·창 1)이 하네스 자체를 CI 에서 지킨다.
- 결과 wiki `query/yearly-strategy-comparison` 페이지 `verified:` 에 수집일·엔진 커밋·지표 정의·실행 명령을 박는다(사후 정정 시 `[!conflict]` 규약).

# Key Files

- `scripts/collect_backtest_fixtures.py` — `_get`·`normalize`·`THROTTLE_SEC` 재사용
- `scripts/collect_yearly_fixtures.py`(신규) — 8종 × 365봉 수집
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt` — 최신순 규약·`slice` 참고(건드리지 않음)
- `bot/src/test/kotlin/com/trading/bot/engine/YearlyFixtures.kt`(신규), `YearlyStrategyComparisonTest.kt`(신규)
- `bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt`, `bot/src/test/kotlin/com/trading/bot/engine/AccumulateBacktest.kt` — 실행 엔진(무변경)

# Blockers

없음.

# Acceptance

| # | 충족 조건 | 검증 | 기준 |
|---|---|---|---|
| A1 | 8 마켓 × 365봉 fixture, 마지막 봉 2026-09-02(완결), 결측 없음 | 수집 스크립트 출력 + `YearlyFixturesTest` | 통과 — 증거: 수집 8/8 기록, `YearlyFixturesTest` 1건 통과 |
| A2 | 전략 9종(재진입 2모드)·적립·단순보유 × 8 마켓 × 3 창(전체·선택·검증) 결과가 전부 산출되고 파일로 기록 | `RUN_YEARLY_COMPARE=true ./gradlew :bot:test --tests "*YearlyStrategyComparisonTest*" --rerun-tasks` → `bot/build/reports/yearly-strategy-comparison.md` 존재·행 수 단언 | 통과 — 증거: 6건 실행/skip 0, 파일 8.7K 생성(2026-09-03) |
| A3 | 선택/검증 창 순위상관·상위 3 유지 여부·거래수·노출이 산출물 파일에 있음 | 파일 내용 단언(형식) | 통과 — 증거: 게이트 테스트 단언 4개 통과, 순위 안정성 절 ρ·잔류·검증 순위 존재 |
| A4 | 결과·해석·한계가 plan·wiki `query` 페이지·fixture README 에 기록, wiki 3종 검증 | 실행 | 통과 — 증거: check_links clean / verify.sh clean(36 pages) / smoke pass=10 fail=0 |
| A5 | 프로덕션 코드 무변경 | `git diff --stat 304f0b0 -- bot/src/main common/src/main` | 0 — 증거: 0줄, `git status` 에도 main 소스 없음 |
| A6 | 게이트 없는 스모크 1건이 CI 에서 하네스를 지킨다 | `./gradlew :bot:test` | 통과 — 증거: 전체 스위트 883건/실패 0/skip 10(기존 env 게이트 9 + 신규 게이트 1) |

# Review Disposition

code-reviewer 2026-09-03 (Claude 단독, codex 크레딧 소진):

| # | 지적 | 처분 |
|---|---|---|
| 1 | 창별 적격 모집단이 달라지면 순위값에 구멍 → ρ·상위절반 임계 오류 | fix — 양쪽 적격 집합 안에서 재순위, 스피어만은 순위 벡터 피어슨(동점 평균순위) |
| 2 | equity 곡선이 봉당 1거래만 집어 same-bar 재진입 누락·마지막 거래 유실 가능 | fix — 한 봉에서 거래 소진 + 종점 항등식 `check` + 회귀 테스트 |
| 3 | warmup==MIN_CANDLES 무가드, 창 테스트가 리터럴 재진술 | fix — `compare` require + `Window.entries` 불변식 테스트 |
| 4 | wiki 해석 4 가 선택 창(legacy 우세)과 모순 | fix — 구간 의존으로 재서술 |
| 5 | 노출·거래수 열 정의 혼재, B&H 적격이 마켓 수에 의존 | fix — 헤더 정의 명시, 적격 하한은 스윙만 |
| 6 | 마켓별 0거래 셀이 중앙값을 끌어올림 | fix — `0거래 마켓` 열 추가(검증 창 4후보 1마켓씩 확인, wiki 한계에 기재) |
| 7 | 동점을 임의 순서로 갈라 상위 3 컷 | fix — competition rank(`=` 표기) + 평균순위 |
| 8 | 미실현에 수수료 미차감 | fix — 왕복 0.1% 선차감(MDD ±0.1 변동) |
| 9 | `scripts/__pycache__` 미ignore | fix — `.gitignore` |
| 10 | 재수집 시 옛 fixture 미삭제 | fix — 기록 전 `KRW-*.json` 삭제 |
| 11 | 마켓 목록 3곳 중복 | wontfix — 스크립트(python)·로더(Kotlin)·README 는 언어·용도가 달라 단일 소스 불가, `YearlyFixturesTest` 가 8종 핀으로 불일치를 잡는다 |
| 12 | wiki 5~14 행 명단 누락·비정규 이름 | fix |
| 13 | verify.sh 밴드가 이미 상한 | fix — 36±2 |
| 14 | 스모크 단언 부족 | fix — 유한·MDD≥0·스윙 18행 + 곡선 인덱스 require |
| 15 | 창 시작 flat 각주 누락 | fix |
| 16 | plan 미동기화 | fix |
| — | Upbit `to` 배타/포함 규약 미확인(open) | wontfix — 날짜 중복·span 검사가 실패 시 기록을 막으므로 안전 측 |

# Deferred

(비어 있음)

# Workflow Findings

(비어 있음)
