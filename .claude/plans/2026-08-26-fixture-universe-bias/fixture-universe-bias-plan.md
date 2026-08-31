---
title: fixture-universe-bias — 백테 유니버스 look-ahead 편향의 크기를 먼저 잰다 (#112)
status: in_progress
started: 2026-08-26
updated: 2026-08-26
---

# Goal

백테 fixture 의 마켓 선정이 **구간 끝 정보**(수집 시점 거래대금 상위)로 이뤄져 look-ahead 편향이 있다.
그 편향의 **크기를 정량화**하고, **다음에 무엇을 할지의 조건과 비용을 사전 고정**한다. GitHub #112.

**재수집 자체는 이번 범위가 아니다** (사용자 확정 2026-08-26). 단 진단이 "숫자만 남는 작업"이 되지 않도록
행동 규칙을 결과에 묶는다(D7).

# Progress

- 2026-08-26 Explore 완료. 선행 외부 사실 2건 확정(D1), 파급 범위 확인(D2), 범위를 진단으로 확정.
- 2026-08-26 plan-review(Claude subagent + codex 0.149.1 medium) → **CONDITIONAL, P0 4건 / P1 8건**.
  전부 코드로 재확인해 수용, **D3 전면 재작성** + D6·D7 신설. 상세는 `# Review Disposition`.
- 2026-08-26 **TDD Red**(`cb3cde0`) — selector 순수함수 API + 결정성 6종. stub 상태에서 6/6 실패,
  5건은 의도한 단언(빈 유니버스·`incomplete=false`·제외사유 null), 1건은 명시 단언으로 보강.
- 2026-08-26 **구현·Green**(`91db3e0`) — 완전성 게이트 → 스테이블 제외 → 30봉 완비 → 평균 랭킹 → top-8,
  동점은 마켓코드 오름차순. 6/6 통과 + 전체 스위트 통과.
- 2026-08-26 **감사 하네스**(`71541ef`) 후 **실행 완료** — 후보 287 KRW 마켓, 4패스.
  결과는 wiki `query/universe-look-ahead-audit`.
  - **3단 감쇠**: 하락장 229 → 222 → **8**, 상승장 102 → 102 → **8**
  - **overlap**: 하락장 3/8, 상승장 2/4. **placebo(절차 노이즈 바닥)**: 5/8, 3/4 → 시점 이동 몫이 따로 있다
  - **horizon**: 하락장 200일, 상승장 1001일 (같은 잣대 비교 금지)
- 2026-08-26 **D7 사전 규칙 판정: 트리거 발동** — 상승장 top-8 중 구간 200봉 충족 **8 ≥ 6**.
  → `bull/` 한정 재수집을 후속 이슈로 제안한다. **BULL 4마켓은 데이터 부족이 아니라 선정 방식 탓**이었다.
- 2026-08-26 문서 동기화 + 최종 검증 — wiki 3종(32 pages, pass=10 fail=0),
  `:bot:test :common:test compileKotlin --rerun-tasks` BUILD SUCCESSFUL.

# Next

1. **후속 이슈 제안** — D7 트리거 발동(BULL ③=8 ≥ 6) → `bull/` 한정 재수집을 이슈로 올린다.
2. push → PR.

진단(A1~A9)은 끝났다.

# Decisions

## D1. 시점 중립 유니버스는 구성 가능하다 — 단 일부만 (✅공식 문서·API 실측)

| 편향 | 닫히나 | 근거 |
|---|---|---|
| **신규상장 배제**(look-ahead) | ✅ 닫힌다 | 구간 시작 시점 캔들 존재 여부로 상장 판정 가능 |
| **과거 거래대금 랭킹** | ✅ 닫힌다 | 일봉 응답에 **`candle_acc_trade_price`**(KRW 거래대금)가 있다 (API 실측 2026-08-26). 도메인 모델에도 이미 있다(`Candle.kt:19-20`) |
| **생존편향 중 폐지 배제분** | ❌ 안 닫힌다 | `GET /v1/market/all` 응답은 `market`·`korean_name`·`english_name`(+선택 `market_event`)뿐. **폐지 종목은 목록에서 사라져 열거 자체가 불가** |

**정정(리뷰 지적)**: 구 D1 은 "생존편향 ❌"라고 뭉뚱그렸으나 부정확하다 — 급락했지만 **상장 유지 중인** 종목은
`/v1/market/all` 에 있어 랭킹에 정상 반영된다. 닫히지 않는 건 **폐지 배제분뿐**이다. 보고도 이 문구로 한다.

## D2. 파급 범위 — 유니버스를 바꾸면 커밋된 숫자가 전부 바뀐다

`BacktestFixtures` 를 참조하는 테스트 **6개**(구 plan "7개"는 오기 — 7번째는 `BacktestFixtures.kt` 자신):
`BacktestLegacyGoldenTest`(trade 단위 골든 `legacy-golden.txt` 377줄) · `KneeStrategyComparisonTest` ·
`DailyResetCounterfactualTest`(#128 측정 하네스) · `BacktestReentryEquivalenceTest` ·
`BacktestFixturesTest` · `KneeRsiWindowTest`.

재수집은 **골든 재생성 + #128 측정 재실행 + wiki `reset-churn-measurement` 갱신**을 동반한다.

## D3'. 진단 설계 (전면 재작성 — 구 D3 의 2단계 게이트 폐지)

### 왜 바꿨나
구 D3 은 "`overlap ≤ 5/8` 이면 2단계"라는 사전 게이트를 뒀는데, **`BULL` 현재 유니버스가 4마켓**이라
(`BacktestFixtures.kt:37` `Regime.BULL to PAIRED_MARKETS`) 교집합 최대가 4 → **결과와 무관하게 항상 발동**한다.
사전등록의 의미가 없었다. 게다가 2단계 비용은 16 요청(≈3초)뿐이라 게이트가 막는 비용보다 오작동 비용이 크다.
→ **실행 게이트를 없애고 해석 규칙만 사전등록**한다. p-hacking 을 막는 건 "언제 재느냐"가 아니라
"어떤 결과를 무엇으로 읽느냐"의 사전 고정이다.

### 선정 규칙 (시점 중립)
t0 = 구간 시작일. **t0 이전 정보만** 쓴다.

1. 후보: `GET /v1/market/all` 의 KRW 마켓 전체
2. 각 후보에 `to=t0&count=30` 1회 조회
   - **정상 응답 + 30봉 완비** = t0 시점에 최소 30일 상장 → 선정 자격
   - 30봉 미만 = 신생(또는 결측) → **제외**. 상장 당일 거래대금 스파이크가 top-N 을 오염시키는 것을 같이 막는다.
     ⚠️ 이건 "30일 미만 신규상장"을 배제하므로 **완전한 시점 중립이 아니다** — 리포트에 명시한다.
   - 빈 응답/4xx = 미상장 또는 잘못된 코드 → **상태코드로 분기**(`M1ReplayBiasTest.fetchDayCandles` 는
     빈 응답에 `check(...)` 로 throw 하므로 그대로 재사용하면 미상장마다 죽는다)
3. 스테이블코인 제외 — **결정적 상수 목록**을 코드에 고정(현 README 는 `KRW-USDT` 하나만 적어 재현 불가)
4. 30일 `candle_acc_trade_price` **평균**으로 내림차순 → 상위 8 = 시점 중립 유니버스

**`200봉 확보 가능`을 선정 조건에서 뺀다(P0-3)** — fixture 는 `to=<구간끝>&count=200` 으로 받으므로
그 조건은 "구간 끝까지 살아있었나"를 묻는 것이고 **정확히 없애려는 look-ahead** 다.
200봉 충족 여부는 **필터가 아니라 보고할 결과**로 옮긴다(A3').

### placebo 대조군 (P0-2 — 필수)
현행 규칙은 "**수집 시점 24h 누적**"(README:22), 신안은 "**t0 직전 30일 평균**" — 시점(as-of)과 집계창을
**동시에** 바꾼다. 그러면 overlap 차이를 look-ahead 에 귀속할 수 없다.
→ 동일한 **30일-평균 selector 를 수집일(bear 2026-08-19 / bull 2026-08-20) 기준으로** 한 번 더 돌려
커밋된 로스터를 얼마나 재현하는지 본다. 그게 **절차 노이즈 바닥**이다.
가능하면 2×2 로 {24h 단일일, 30일 평균} × {t0, 수집시점} 를 산출해 시점 효과와 추정량 효과를 분해한다.

### 완전성 규칙 (P0-4 — 판정 금지 조건)
후보 조회에 **누락이 1건이라도 있으면** 그 국면을 `incomplete=true` 로 표시하고 **top-8 판정·해석을 금지**한다.
누락을 흡수해 부분 결과를 내면 그 마켓이 거래대금 0 으로 취급돼 **다른 마켓이 부당하게 top-8 에 오른다**
(조용한 랭킹 오염 — CLAUDE.md §1 에러 무시 금지). 누락 마켓·HTTP 상태·재시도 횟수는 리포트에 남긴다.

## D4'. 하네스 — 수동 전용 + 실측 상수 사용

`@EnabledIfEnvironmentVariable(RUN_UNIVERSE_AUDIT)`. CI 비실행(`M1ReplayBiasTest` 선례).

- **요청 간격 150ms** — repo 실측 상수 `MarketDataIngestionService.CANDLE_REQUEST_SPACING_MS = 150L`,
  근거는 `MarketDataIngestionServiceTest.kt:96` "Upbit candles 그룹 = 초당 10회 / 분당 600회, 실측".
  180마켓 × 150ms ≈ **27초/패스**, 국면 2 + placebo ≈ **81초**. rate limit 은 쟁점이 아니다(구 plan 과장 정정).
- **429/5xx 만 지수 백오프, 그 외 4xx 즉시 throw, 최대 3회** (`M1ReplayBiasTest.kt:228-230` 과 동일)
- **`to` 포맷·타임존**: README 는 `2024-06-10T00:00:00Z`, `M1ReplayBiasTest` 는 `yyyy-MM-dd HH:mm:ss`.
  9시간 어긋나면 "직전 30일"이 하루 밀린다 → **반환된 최신 봉의 `candle_date_time_kst` 가 t0 하루 전인지 assert**.

## D5'. selector 를 순수 함수로 분리 — 재현성과 TDD 를 동시에 얻는다 (구 A5 재정의)

구 A5 의 "과거 구간이라 시세 불변"은 **캔들에만** 참이다. 후보 집합의 출처인 `/v1/market/all` 은
시간 불변이 아니다(신규상장·폐지로 바뀐다) — D1 이 인정한 생존편향과 같은 메커니즘이다.

→ 3분할:
1. **네트워크 단계**: 원시 입력(후보 목록 + 마켓별 30봉)을 **스냅샷 파일로 덤프 + 해시**
2. **selector**: 스냅샷을 먹는 **순수 함수**. 결정적 단위테스트를 붙인다 — 지금 구조로는 Red 를 만들 대상이 없었다
3. **리포트**: 실행시각·commit SHA·API 파라미터·응답 건수·누락 목록·입력 해시 기록

## D6. 국면별 top-8 은 `PAIRED_MARKETS` 를 보장하지 않는다 (신설)

현재는 하나의 수집시점 랭킹을 두 국면이 공유해 `BULL ⊂ BEAR` 가 보장되고, 그래서 교집합 4개로 paired 비교가
성립한다(`BacktestFixturesTest.kt:64-69` 가 그 관계를 고정). 국면별로 각각 top-8 을 뽑으면 교집합 크기가
보장되지 않는다.

`DailyResetCounterfactualTest.kt:262-268`·`:301-303` 은 유효 마켓 < 2 또는 paired 표본이 비면 **판정 유보**로
빠진다 → 2단계를 돌려도 "유보"만 나올 수 있다.
→ **두 국면 시점중립 유니버스의 교집합 크기를 1단계 산출물에 포함**하고, 교집합 < 2 면 2단계는
"paired 비교 불가"로 보고하고 마켓별 원값만 낸다.

## D7. 결과를 행동에 묶는다 (신설 — "숫자만 남는 작업" 방지)

리뷰의 가치 판정: overlap 은 "유니버스가 다르다"만 말하고 "결론이 바뀐다"는 말하지 못하며,
게다가 생존편향 때문에 **"안 바뀐다" 쪽으로 편향**돼 있다(P1-3).
→ 사전 고정하는 **행동 규칙**:

> **BULL 시점중립 top-8 중 구간 200봉을 실제로 채우는 마켓이 6개 이상이면**,
> `BULL 한정 재수집`을 후속 이슈로 **즉시 제안**한다(비용: 8마켓 수집 + 골든 재생성 + #128 재실행 — D2).
> 6 미만이면 "BULL 4마켓 제약은 유니버스 선정이 아니라 데이터 가용성 탓"으로 결론하고 재수집을 권하지 않는다.

BULL 4마켓은 #128 표본력의 binding constraint 였으므로, 이 한 줄이 진단을 행동으로 닫는다.

**금지 규칙(P1-3 반영)**: `overlap` 이 높다는 것을 **"look-ahead 편향이 작다"의 근거로 쓰지 않는다.**
폐지 종목이 후보에서 빠져 살아남은 마켓이 그 자리를 채우므로 overlap 은 낙관 쪽으로 편의된다.

**horizon 병기(P1-8)**: BEAR 는 t0~수집일 ≈ 6.5개월, BULL 은 ≈ 2.75년이다. 두 국면의 overlap 을 같은 잣대로
비교하지 않는다 — 낮은 overlap 이 국면 탓인지 horizon 탓인지 못 가린다.

## D8. 기대치를 미리 낮춘다 — 이건 표본력 문제를 풀지 않는다 (구 D5)

#112 = **편향(타당성)**, #128 을 묶은 것 = **표본력(N_eff ≈ 2, 마켓 상관 0.49)**. 다른 문제다.
상승장이 4 → 최대 8마켓이 되면 그 국면 N 은 2배지만 N_eff 증가폭은 상관에 제약된다.
**"#112 를 고치면 #128 결론이 확정된다"는 기대는 틀렸다** — 리포트에 명시한다.

# Key Files

- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt` — `MARKETS_BY_REGIME`(BULL=4)·`PAIRED_MARKETS`·`Regime` 구간
- `bot/src/test/resources/backtest/README.md` — 선정 규칙·정규화(7키)·한계·재수집 절차(단일 소스)
- `bot/src/test/kotlin/com/trading/bot/engine/M1ReplayBiasTest.kt` — 수동 하네스 선례(150ms·백오프·`to` 포맷·빈응답 throw)
- `bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt:75` — `CANDLE_REQUEST_SPACING_MS`
- `bot/src/test/kotlin/com/trading/bot/engine/DailyResetCounterfactualTest.kt` — 2단계 재사용 대상, 유보 경로(`:262-268`,`:301-303`)
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixturesTest.kt` — 로스터·교집합 고정, **마켓 간 날짜 일치는 미검증**
- `wiki/pages/concept/backtest-engine.md` · `wiki/pages/query/reset-churn-measurement.md`
- **`wiki/pages/concept/swing-strategies.md`** — `bot/src/test/resources/backtest/` 를 **디렉토리 sources** 로 선언(`:12`).
  repo CLAUDE.md 가 경고한 "경로 grep 으로 못 잡는" 케이스 — 구 plan 에서 누락됐다.

# Blockers

없음.

# Acceptance

| # | 무엇이 충족되나 | 어떻게 검증 | 통과 기준 |
|---|---|---|---|
| A1 | selector 가 스냅샷 입력에 대해 결정적이다 | 순수함수 단위테스트(네트워크 없음, CI 실행) | 고정 스냅샷 → 고정 top-8. 30봉 미만 제외·스테이블 제외·동점 처리까지 커버 |
| A2 | 완전성 규칙이 판정을 막는다 | 누락 1건 있는 스냅샷 입력 | `incomplete=true` 이고 top-8 판정이 **거부**된다 |
| A3' | BULL 이 몇 마켓까지 가능한지 3단 감쇠로 나온다 | 수동 하네스 실행·출력 관찰 | ① t0 상장 후보 수 ② 선정조건 통과 수 → top-8 ③ **그중 구간 200봉을 채우는 수** |
| A4 | placebo 가 절차 노이즈 바닥을 준다 | 수집일 기준 패스 | 커밋된 로스터 재현율(예: 6/8)이 수치로 나온다 |
| A5' | 재현 가능하다 | 스냅샷 해시 + provenance | 같은 스냅샷 → 같은 결과. 리포트에 실행시각·commit SHA·API 파라미터·누락 목록·입력 해시 |
| A6 | 한계가 정직하게 남는다 | 리포트·문서 | 폐지 배제분만 미해결(D1)·overlap 을 편향 크기로 쓰지 않음(D7)·표본력과 무관(D8)·30일 미만 제외로 완전 중립 아님 |
| A7' | 기본 경로 무변경 | `./gradlew :bot:test :common:test` | 기존 테스트 green. `BacktestFixtures` 기본 동작 불변을 테스트로 고정(2단계 주입은 opt-in) |
| A8 | 행동 규칙이 결과와 함께 남는다 | wiki query 페이지 + #112 코멘트 | D7 판정 결과와 **다음 액션의 조건·비용 견적**이 명시 |
| A9 | 문서 동기화 | wiki 3종 + README | `check_links.py`·`verify.sh`·`smoke.sh` 통과, `backtest-engine`·**`swing-strategies`**·fixture README 갱신 |

# Review Disposition

plan-review(2026-08-26, Claude subagent + codex 0.149.1 medium) 처분:

| finding | 처분 | 근거 |
|---|---|---|
| **P0-1** BULL 분모가 4라 게이트가 항상 발동 | **fix** | ✅ `BacktestFixtures.kt:37` 확인. 게이트 폐지(D3') |
| **P0-2** 시점·집계창 동시 변경으로 귀속 불가 | **fix** | 타당. placebo 대조군 신설(D3') |
| **P0-3** "200봉 확보"가 미래 정보 | **fix** | ✅ fixture 가 `to=<구간끝>` 수집이라 정확한 지적. 선정조건에서 제거, 결과로 이동 |
| **P0-4** 부분 실패 흡수 = 조용한 랭킹 오염 | **fix** | 타당. `incomplete` 판정 금지 규칙(D3') |
| P1-1 A7↔2단계 양립 불가 | **fix** | `DailyResetCounterfactualTest` 가 `BacktestFixtures` 고정 호출. opt-in 주입 + 기본값 보존 테스트(A7') |
| P1-2 `PAIRED_MARKETS` 붕괴 → 유보 경로 | **fix** | D6 신설 |
| P1-3 생존편향이 overlap 을 낙관 쪽으로 민다 | **fix** | D7 의 금지 규칙 |
| P1-4 게이트 비용이 0이라 순손실 | **fix** | 게이트 폐지(D3') |
| P1-5 A5 재현성 미성립 | **fix** | D5' 3분할. **부수 효과로 TDD 대상이 생긴다** |
| P1-6 rate limit 은 비쟁점 + 실측 상수 | **fix** | ✅ `CANDLE_REQUEST_SPACING_MS=150L`·`MarketDataIngestionServiceTest.kt:96` 확인. D4' |
| P1-7 A3 는 답이 나온 질문 | **fix** | 3단 감쇠표로 재정의(A3') |
| P1-8 horizon 비대칭 | **fix** | D7 병기 규칙 |
| 누락: `to` 타임존·빈응답 throw·30일 미만·스테이블 목록·`count=200`≠200일·7키 스키마 | **fix** | D3'·D4' 에 반영. 날짜 일치 assert 는 2단계 전제로 `# Deferred` |
| 누락: 임시 fixture 경로 격리 | **fix** | 2단계 시 `build/` 하위에만 쓰고 `bot/src/test/resources/backtest/` 는 **절대 건드리지 않는다**(골든 오염 방지) |
| D2 "테스트 7개" 오기 | **fix** | 6개로 정정 |
| `swing-strategies.md` 디렉토리 sources 누락 | **fix** | Key Files·A9 추가 |
| **가치 판정**: 진단만으론 타당성 문제도 못 푼다 | **fix** | D7 신설 — 행동 규칙을 결과에 사전 고정 |

# Deferred

- **생존편향 중 폐지 배제분** — 공식 API 에 열거 경로 없음(D1). 비공식 공지 파싱·외부 데이터셋은 별도 판단.
- **재수집 자체** — D7 판정에 따라 후속 이슈로.
- **`BacktestFixturesTest` 의 마켓 간 날짜 일치 assert** — 현재 정렬만 검증한다(`:38-47`).
  새 마켓을 수집하면 `count=200` 이 "존재하는 200봉"이라 구간이 어긋날 수 있다 → 재수집 착수 시 전제로 추가.

- **`wiki/verify.sh` 페이지 수 가드가 main 에서 이미 stale 했다** — `origin/main` 은 페이지 31개인데
  그쪽 `verify.sh` 가드가 `26..30` 이라 **내 변경 전부터 실패**하던 baseline 이다(증명: `git ls-tree`
  31개 + `git show origin/main:wiki/verify.sh` 의 상한 30). 내 페이지 추가가 걸린 검사라 미루지 않고
  `32±2` 로 갱신했다. 이 가드는 페이지가 늘 때마다 stale 해지므로 ±2 밴드 자체가 재검토 대상일 수 있다.
- **pre-push 리뷰 P2 "평균을 관측 봉 수로 나눈다"** — `MAX_WINDOW_SPAN_DAYS=32` 로 결측 2일을 허용하므로
  결측이 있는 종목의 평균은 엄밀히 *30일 평균*이 아니라 *관측 30봉 평균*이다. **wontfix + 문서화**:
  결측일을 0 으로 채우면 "거래가 없던 날 = 거래대금 0" 으로 추정량이 바뀌어 비교 기준 자체가 달라진다.
  허용폭이 30일 중 2일이고 4회 실행에서 순위 변동이 관측되지 않았다.
  wiki `query/universe-look-ahead-audit` 의 "읽을 때의 제약"에 명시했다.
- **fix loop 상한 초과** — push 게이트 findings 에 대해 5라운드를 돌았다(dlc 상한 2). 3라운드 이후로는
  지적이 "Upbit 이 실제로 내지 않는 malformed 응답" 영역으로 옮겨갔고, 값싸고 원칙에 맞는 것만 고치고
  나머지는 명시적 risk accept 로 닫았다.

# Workflow Findings

- **wiki 페이지 이름을 worktree slug 와 같게 지으면 `smoke.sh` 음성검사에 걸린다.**
  그 검사는 "실재하는 브랜치 이름이 wiki 에 등장하는가"를 보는데, 페이지명 `fixture-universe-bias` 가
  브랜치명과 그대로 겹쳐 오탐이 아니라 **정탐**으로 잡혔다. 검사 취지("확정된 지식은 특정 브랜치를 지목할
  이유가 없다")가 옳으므로 검사를 약화시키지 않고 페이지를 `universe-look-ahead-audit` 로 개명했다.
  → **wiki 페이지는 작업 slug 가 아니라 지식 내용으로 이름 짓는다.**
