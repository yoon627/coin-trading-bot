---
title: backtest-universe-bias — #112 백테 fixture 유니버스의 look-ahead/생존편향 제거
status: in_progress
started: 2026-08-26
updated: 2026-09-02
---

# Goal

백테 fixture 의 마켓 선정에서 look-ahead 를 없앤다. 현재는 **수집 시점(2026-08)의 24h 거래대금 상위**로
골라 구간 *끝* 정보를 쓴 것이라 ① 생존편향(그 사이 폐지·급락한 종목 배제) ② 신규상장 배제 편향이 동시에 있다.
각 구간 *시작* 시점 기준 유니버스로 바꾸는 것이 목표다.

# Progress

- 2026-08-26 worktree 생성(base `main@d21a3eb`), baseline `:bot:test`+`:common:test` BUILD SUCCESSFUL(사전 실패 없음).
- 2026-08-26 Explore — fixture README·`BacktestFixtures`·`BacktestFixturesTest` 확인, 영향 범위 6곳 식별.
- 2026-08-26 researcher 가 세션 한도로 2회 죽어 **메인이 Upbit 공개 API 를 직접 호출해 확정**(아래 Decisions 3).
- 2026-08-26 시점 유니버스 실측 완료 — 30일 평균 거래대금 기준 상위 8 확정, 사용자 결정 2건 수신.
- 2026-08-26 **수집 스크립트 + fixture 교체**(`1d80c0e`) — `scripts/collect_backtest_fixtures.py`.
  부수 발견: 기존 bear fixture 의 마지막 봉(2026-08-18)이 **미완성 봉**이었다(2026-08-19 수집).
  재수집으로 완결됐고 BTC/ETH/XRP 에서 200봉 중 그 1봉만 바뀐다.
- 2026-08-26 **로더·핀·골든 갱신**(`6d178ee`). 골든 재생성에서 함정 둘을 겪어 docstring 에 기록:
  `GOLDEN_OUT` 상대경로가 `bot/bot/` 에 조용히 쓰이는 것, env 변경이 Gradle up-to-date 판정에 안 잡혀
  태스크가 skip 되는 것. 둘 다 "재생성했다"고 착각하게 만든다.
- 2026-08-26 **#128 재측정 + 문서 정정**(`b58a45c`). **결론이 바뀌었다** — 아래 Progress 요약 참조.
- 2026-08-26 최종 검증: `:bot:test :common:test compileKotlin --rerun-tasks` **BUILD SUCCESSFUL** (760 tests / 0 failures).
  wiki: check_links clean / smoke 10 pass / verify.sh 는 **base 부터 실패**(Deferred).
- 2026-08-26 수집 스크립트 하드닝(`d8bb793`→rebase 후 `e5df6fe`) — 순위 조회 실패를 조용히 넘기던 것과
  `--write` 가 지우고 받다 실패하면 fixture 가 반만 남던 것. 둘 다 "조용히 틀린 결과" 부류라 중단으로 바꿨다.
- 2026-09-02 **작업 중 worktree 가 외부에서 삭제**됐다(push 직전). 커밋은 브랜치에 남아 유실 없음.
- 2026-09-02 worktree 재생성 후 **현재 main(`9376452`) 위로 rebase — 충돌 0**, `:bot:test :common:test`
  BUILD SUCCESSFUL. 현재 tip `e5df6fe` (8커밋).
- 2026-09-02 **#112 가 두 브랜치로 갈린 것을 발견** — 아래 Blockers.
- 2026-09-02 **해금** — `fixture-universe-bias` 가 PR #160 으로 먼저 머지됐다(main `8f36928`). 그 위로 rebase(9커밋, 충돌은 `backtest/README.md` 한계 절 1곳 — 그쪽 "look-ahead 크기를 쟀다" 항목을 "실측한 뒤 제거했다" 로 고쳐 이쪽 생존편향 항목과 나란히 둠). 검증: `:bot:test :common:test` 787 tests / 0 failures / skip 9(전부 env 게이트: `RUN_UNIVERSE_AUDIT`·`RUN_COUNTERFACTUAL`·sweep·M1·`TEST_DB_HOST`·KIS smoke), wiki 3종 clean(34페이지). `# Next` 2항(정정문 효과 크기 재검토) 결론: **수정 불필요** — 정정문의 근거는 overlap 이 아니라 재측정 수치라 placebo 바닥(5/8·3/4)과 무관하다. push → PR 진행.

**#128 결론 변화 (편향 제거의 실익)**

| 정책 | 편향 fixture (vb 하락/상승) | 시점 중립 (vb 하락/상승) |
|---|---|---|
| `conditional-reset` | **+0.470 / +0.284** | **−0.013 / +0.187** |
| `hold-through` | +0.263 / −0.004 | −0.044 / −0.423 |
| `cooldown-1` | −0.093 / +0.020 | −0.109 / −0.207 |

"조건부 리셋만 두 국면에서 부호가 일관된다" 는 최초 결론은 **편향의 산물이었다** — 철회했다.
`combined` 의 판정도 "방향성 있음(비용)" → "국면 의존" 으로 바뀌었다.
유지된 결론: 효과 크기가 작다(관측 1.9%p/건에 못 미침), 쿨다운은 개선 근거가 없다.

국면 성격도 바뀌었다 — 옛 bull 은 1/4 마이너스로 균일한 상승장처럼 보였으나
시점 중립에서는 **8개 중 4개가 마이너스**다. 오늘의 승자만 담았던 결과다.

# Next

1. push → PR 생성 → CI → 머지(`Closes #112`). 머지되면 worktree·브랜치 정리 + `status: done`.
2. 머지 후 이슈 판단: fixture 쪽 D7 트리거(`bull/` 한정 재수집 제안)는 이 브랜치가 **두 국면 전부 재수집**으로 흡수했으므로 별도 이슈 불필요. `# Deferred` 의 무릎 plan 3개 수치 재해석은 필요 시 이슈로.

# Decisions

1. ~~실현 가능성(폐지 종목 조회 가부)을 먼저 확정한다~~ → **3 으로 종결** (불가 확정, Goal 축소).
2. ~~fixture 교체 vs 병행 미결~~ → **5 로 종결** (교체 + 다운스트림 재생성).

3. **✅ Upbit API 실측 (2026-08-26, 메인 직접 호출)** — 문서 추정이 아니라 실제 응답 근거다.
   - `GET /v1/market/all?isDetails=true` 스키마 = `market`·`korean_name`·`english_name`·`market_event`.
     **상장일 필드 없음** → 과거 시점 목록을 직접 주는 엔드포인트는 없다.
   - **폐지 종목은 404**: `KRW-LUNA`·`BTC-LUNA`·`USDT-LUNA`·`KRW-LUNC` 전부
     `{"error":{"name":404,"message":"Code not found"}}` — 존재하지 않는 코드와 **동일 응답**.
     LUNA 는 2022-05 업비트 상장폐지가 확인된 실제 사례다.
     → **생존편향 제거는 원리적으로 불가능**하고, 크기 측정조차 불가능하다(그때 뭐가 있었는지 알 방법이 없다).
   - **"현재 상장 + 그때 미상장" 은 200 + 빈 배열** — 404 와 구별된다.
     → **신규상장 배제 편향은 정확히 판정·교정 가능**하다. 이게 이번 작업의 실질 범위다.
   - `candle_acc_trade_price`(누적 거래대금)가 일봉 응답에 있어 **시점 기준 순위를 계산할 수 있다**.
   - rate limit `group=candles; min=600; sec=9` → KRW 284개 전수 조회가 ~40초. 현실적이다.

4. **선정 규칙 = 구간 시작 시점의 30일 평균 거래대금 상위 8** (사용자 확정 2026-08-26).
   같은 "거래대금 상위" 규칙을 시점만 옮기되, 창을 30일로 늘려 단기 펌핑 영향을 줄인다.
   순위 계산 창은 구간 시작 **이전** 30봉이라 look-ahead 가 없다.
   (1일·7일 기준도 계산해 봤고 BLUR·MLK·LSK·ZRX 같은 당시 펌핑주가 상위를 채웠다 — 30일이 그걸 완화한다.)

5. **fixture 교체 + 다운스트림 전면 재생성** (사용자 확정 2026-08-26).
   편향 있는 fixture 를 남겨두면 계속 인용되므로 교체한다. `legacy-golden.txt`·#128 측정치
   (`reset-churn-measurement`)·무릎 전략 비교 수치를 모두 재생성한다. **#128 결론이 바뀔 수 있다.**

6. **paired 교집합이 4 → 3 으로 줄어드는 것을 수용한다.**
   실측 유니버스: bear = XRP·BTC·ETH·AXS·DATA·ENSO·SOL·BERA / bull = GAS·XRP·BTC·SOL·ARK·MINA·BLUR·POLYX.
   교집합은 BTC·SOL·XRP 3개다(기존 4개: XRP·BTC·ETH·DOGE).
   유니버스가 실제로 회전하기 때문이고, **겹침을 억지로 늘리면 선택 편향을 다시 넣는 것**이라 그대로 둔다.
   대신 bull 국면 내 N 이 4 → 8 로 두 배가 된다(#128 이 BULL N=4·한 팔 N=2 로 쪼그라들었던 제약이 풀린다).
   ⚠️ #128 의 A5d(국면 간 부호 비교)는 paired 3개라 더 약해진다 — 재실행 시 판정이 유보로 바뀔 수 있다.

# Key Files

- `bot/src/test/resources/backtest/README.md` — 수집 조건·정규화·한계. **look-ahead 를 이미 스스로 문서화**하고 있다(이 작업의 출발점).
- `bot/src/test/resources/backtest/{bear,bull}/*.json` — 실데이터 fixture 12개(BEAR 8 / BULL 4).
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt` — 로더. `MARKETS_BY_REGIME`·`PAIRED_MARKETS`·`Regime`.
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixturesTest.kt:49-70` — 마켓 로스터를 **구현 상수를 쓰지 않고 독립 하드코딩**해 고정. 유니버스가 바뀌면 의도적으로 실패한다(좋은 설계 — 무음 통과 방지).

영향 받는 소비자(유니버스 변경 시 전부 재검토):
- `BacktestLegacyGoldenTest` + `resources/backtest/legacy-golden.txt` — 현 fixture 12개 기준 골든.
- `DailyResetCounterfactualTest` — #128 측정 하네스. `PAIRED_MARKETS` 의존.
- `BacktestReentryEquivalenceTest` — 전 fixture 순회.
- `KneeStrategyComparisonTest` · `KneeRsiWindowTest` — 무릎 전략 비교 수치.
- `wiki/pages/query/reset-churn-measurement.md` — 오늘 발행한 #128 결과.
- plan 4개: `2026-08-12-knee-backtest-calibration` · `2026-08-20-knee-bull-market-sample` · `2026-08-22-knee-review-followup` · `2026-08-24-daily-reset-counterfactual`.

# Blockers

없음. (2026-09-02 해소 — 아래는 이력)

~~**#112 가 두 브랜치로 갈렸고, 어느 쪽을 먼저 머지할지가 미결이다.**~~ → 사용자 합의대로 진단(`fixture-universe-bias`, PR #160)을 먼저 머지하고 이 브랜치를 그 위로 rebase 했다.

**Goal 축소 (Decisions 3 의 귀결)**: "생존편향 제거"는 이 데이터 소스로 달성 불가다.
이번 작업이 실제로 없애는 것은 **신규상장 배제 편향 + '오늘의 승자를 과거에 소급 적용하는' look-ahead** 이고,
생존편향(폐지 종목 부재)은 **제거도 측정도 불가능한 잔여 한계**로 명시한다.

# Acceptance

**수집 재현성**

| # | 무엇이 충족되나 | 어떻게 검증 | 통과 기준 |
|---|---|---|---|
| A1 | 유니버스 선정이 재현 가능하다 | 수집 스크립트를 커밋하고 재실행 | 같은 입력(구간 시작일·TOP_N·WINDOW)에 같은 마켓 목록. 규칙이 코드에 있고 문서에만 있지 않다 |
| A2 | 선정에 look-ahead 가 없다 | 스크립트 리뷰 + 주석 | 순위 창이 구간 시작 **이전** 30봉이고, 구간 내부·이후 데이터를 순위에 쓰지 않는다 |
| A3 | 미상장/폐지 구별이 정확하다 | 빈 배열 vs 404 분기 | 미상장(빈 배열)은 후보 제외, 404 는 폐지로 기록·집계(제거 불가 한계의 증거) |

**fixture 교체**

| # | 무엇이 충족되나 | 어떻게 검증 | 통과 기준 |
|---|---|---|---|
| A4 | 새 fixture 가 기존 계약을 지킨다 | `BacktestFixturesTest` | 200봉·최신순·키 7개·가격 무변형. 로스터 하드코딩은 새 목록으로 갱신 |
| A5 | 로더가 새 유니버스를 반영한다 | `BacktestFixtures.kt` | `MARKETS_BY_REGIME` 8+8, `PAIRED_MARKETS` = 실제 교집합(BTC·SOL·XRP) |

**다운스트림 재생성 (Decisions 5)**

| # | 무엇이 충족되나 | 어떻게 검증 | 통과 기준 |
|---|---|---|---|
| A6 | legacy 골든 재생성 | `GOLDEN_OUT=... :bot:test --tests "*LegacyGolden*"` 후 커밋 | `BacktestLegacyGoldenTest` green. **재생성 사유를 커밋 메시지에 명시**(기본값 변경이 아니라 fixture 교체) |
| A7 | #128 측정 재실행 | `RUN_COUNTERFACTUAL=true` | 새 표 생성. **결론이 바뀌면 바뀐 대로 보고**(기존 결론에 맞추지 않는다) |
| A8 | 무릎 비교 수치 갱신 | `KneeStrategyComparisonTest`·`KneeRsiWindowTest` | green. 하드코딩 기대값이 있으면 새 수치로 교체하되 **약화가 아닌지 확인** |
| A9 | 전체 스위트 | `:bot:test :common:test` | 0 failures |

**문서**

| # | 무엇이 충족되나 | 어떻게 검증 | 통과 기준 |
|---|---|---|---|
| A10 | fixture README 갱신 | 본문 | 새 선정 규칙·수집일·마켓 목록. **생존편향은 제거 불가 한계로 명시**(폐지 404 근거 포함) |
| A11 | wiki 동기화 | `check_links.py`/`verify.sh`/`smoke.sh` | 3종 clean. `reset-churn-measurement` 수치 갱신 + 유니버스 변경 사실 명시 |
| A12 | #128 결과 재해석 | wiki 페이지 | paired 3개로 줄어든 것과 그 영향(A5d 약화)을 본문에 적는다 |

# Review Disposition

# Deferred

- **`wiki/verify.sh` 의 페이지 수 band(26~30)가 실제 31페이지에 못 미친다** — base(`origin/main`)에서
  이미 실패한다(입증: 내 wiki 변경은 `M` 하나뿐, `git ls-tree origin/main` 도 31). 위키가 정상적으로
  자란 결과이고 band 를 올리는 1줄 유지보수다. #112 와 무관해 이 브랜치에 섞지 않는다(§8). (경미·유지보수)
- 무릎 전략(`KneeStrategyComparisonTest`·`KneeRsiWindowTest`)은 fixture 교체 후에도 통과했다 —
  하드코딩된 기대 수치가 없어서다. 다만 **그 전략들의 과거 결론도 편향 fixture 위에서 나온 것**이라
  재해석이 필요할 수 있다. 관련 plan 3개(knee-backtest-calibration·knee-bull-market-sample·
  knee-review-followup)의 수치는 이번에 갱신하지 않았다. (중간·분석)

# Workflow Findings

- **같은 이슈에 두 세션이 반대 범위를 확정받아 중복 작업이 발생**(2026-08-26). 세션 격리 때문에 구조적으로
  재발 가능하다. 이번엔 결과가 상보적이라 살렸지만, 정반대였으면 한쪽이 통째로 버려졌다.
  착수 전 `git worktree list` + 같은 이슈 브랜치 확인 절차가 있으면 막힌다. → `/improve` 후보.
- **브랜치 ref 만 보고 "주인 없는 브랜치"로 단정해 남의 활성 브랜치를 push·수정하려 했다**(2026-09-02).
  `git worktree list` 를 먼저 봤으면 worktree 가 살아 있고 tip 이 이미 앞서 있음을 알 수 있었다.
  codex 게이트에 막혀 실제 변경은 없었다. → 남의 브랜치를 건드리기 전 worktree 존재 확인을 선행한다.
- **codex-pre-push 리뷰 락 경합으로 push 가 3회 실패**(각 10분 대기, pid 4774·83061).
  세션 병렬 실행 시 push 가 서로를 막는다. 훅의 stale 판정은 정상 동작했다(락 홀더가 실제로 살아 있었다).
