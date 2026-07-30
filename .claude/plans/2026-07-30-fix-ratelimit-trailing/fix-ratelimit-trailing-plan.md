---
title: fix-ratelimit-trailing — 업비트 캔들 429 해소 + 트레일링 dead 설정 정정
status: in_progress
started: 2026-07-30
updated: 2026-07-30
---

# Goal

운영 중 반복되던 두 문제를 고친다. 둘 다 Vultr 이전(#73)과 무관한 기존 이슈다.

1. **429 Too Many Requests** — 캔들 수집이 업비트 rate limit 을 넘겨 10분에 30건 실패.
2. **트레일링 스톱이 사실상 dead** — `takeProfit(2.0) <= trailingStop(2.0)` 로 익절이 항상 선행.

# Progress

- 2026-07-30: 원인 확정. **rate limit 은 실측**(`curl -D -` → `remaining-req: group=candles; min=600; sec=9`)
  = 초당 10회 / 분당 600회. `collectCandlesPeriodically`(:114) 와 `seedDailyCandles`(:192) 가
  마켓 13개를 **요청 간 지연 없이** 연속 호출하고 사이클 단위로만 `delay` → 초당 10회 초과.
  부팅 시엔 seed(13) + 즉시 collect(13) 이 겹쳐 더 확실히 초과.
- 2026-07-30: `upbitMarketFeed.getCandles`(실제 REST) 호출처는 **이 2곳뿐** 확인(나머지는
  `marketDataStore.getCandles` = 메모리 조회) → 수정 범위가 이 파일로 한정된다.
- 2026-07-30: 트레일링 dead 기전 확정. `ExitGates.isTrailingStopTriggered` =
  `pnlPct > 0 && peakPnlPct >= arm && dropFromPeak >= trailingStop`. 수익률이 2% 에 닿으면
  `PositionManager.checkTakeProfit`(:593)이 먼저 익절하므로, 고점이 2% 를 넘는 순간 이미 청산돼
  "고점 대비 2% 하락"이 성립할 구간이 없다.
- 2026-07-30: 구현·검증 완료. spacing 150ms + 429 1회 backoff, 루프 본문을 `collectCandlesRound` 로
  추출(테스트 가능화). 트레일링 기본값을 **TP 5.0 / trail 2.0 / arm 3.0** 으로 3곳 정합
  (`application.yml`·`TradingProperties`·`BacktestEngine`) + `.env.example` 3개 + README 표 갱신.
  **신규 테스트 4개**(spacing 2·429 재시도·비429 미재시도) virtual time 으로 작성, Red→Green 확인.
  **기존 테스트 3개가 깨졌고 원인은 "기본값 암묵 의존"** — 주석에 `// takeProfitPct 2.0`, `// arm 0` 이라
  전제가 적혀 있었다. 기대값을 낮추지 않고 **fixture 에 값을 명시**해 검증 의도(게이트 경계·gross/net
  불변식)를 보존했다. 최종 `./gradlew build` BUILD SUCCESSFUL.
- 2026-07-30: **기존 `ParameterSweepTest` 로 백테스트 실측**(RUN_SWEEP=true, JDK21).
  KRW-BTC D1 200개(2026-01-12~07-30, 하락장) 1,800 조합. baseline(현 기본값) **-4.28%**,
  1위 TP5/SL7/trail1.5/arm3/hold999/filter=true **+1.93%**.
  ⚠️ **상위 15개 전부 `hold=999` + `filter=true`** — 성과 지배 변인은 트레일링이 아니라
  보유기간·마켓필터였다. 트레일링 값(1.5/2.0/3.0)은 순위 내 차이가 거의 없다.
  ⚠️ 단일 종목·하락장·200캔들에서 1,800 조합 상위를 고르는 것은 과최적화 위험이 크고,
  상위 조합도 `recentHalf return` 이 전부 -5~-6% 다.

# Next

구현·검증 완료. 남은 것:

1. PR 생성 + 머지.
2. 머지 후 Vultr 재배포(`./deploy/vultr/deploy.sh deploy`)로 실제 429 감소를 로그로 확인.
   ⚠️ 코드 수정은 재배포해야 운영에 반영된다 — 지금 도는 인스턴스는 아직 구버전이다.
3. `# Deferred` 의 maxHoldDays/marketFilter 는 별도 판단.

# Decisions

- **spacing 값은 실측 기반**: candles 그룹 초당 10회 → 안전 마진 두고 **150ms**(초당 ~6.7회).
  13개 마켓 × 150ms = 약 2초/사이클이고 수집 주기가 60초라 지연 영향이 없다.
- **429 는 spacing 만으로 끝내지 않고 backoff 도 둔다** — 다른 경로(수동 조회 등)와 겹치거나
  업비트가 순간 제한을 좁히면 spacing 만으로는 못 막는다. 단 재시도는 **1회·짧게**(다음 사이클이
  60초 뒤 다시 오므로 길게 물고 있을 이유가 없다).
- **트레일링은 TP 5.0 / trail 2.0 / arm 3.0** (사용자 결정, 2026-07-30). 백테스트 상위 조합들이
  공통으로 가진 범위(TP≥3, trail 1.5~2, arm 3~5)의 보수적 지점.
  **SL(5.0)·maxHoldDays(1)·marketFilter 는 건드리지 않는다** — 백테스트상 성과 지배 변인이지만
  전략의 근본 변경이고 과최적화 위험이 크다. 별도 판단으로 분리.
- **백테스트 수치를 "기대 수익"으로 쓰지 않는다** — 표본이 단일 종목·하락장이고 상위 조합의
  최근 절반 성과가 전부 음수다. 이번 변경의 목적은 "트레일링이 죽어 있는 모순 제거"이지
  수익 최적화가 아니다.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt` — 429 수정 대상
  (`collectCandlesPeriodically` :111, `seedDailyCandles` :191, `companion object` :66).
- `bot/src/main/resources/application.yml` :71,73,75 — 트레일링 기본값.
- `bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt` :15,19,20 — 같은 값이 하드코딩돼 있어 정합 필요.
- `bot/src/test/kotlin/com/trading/bot/marketdata/MarketDataIngestionServiceTest.kt` — 테스트 추가 위치.
- `common/src/main/kotlin/com/trading/common/strategy/ExitGates.kt` — 트레일링 판정(변경 없음, 근거).
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` :101 — 모순 경고(변경 없음, 이 경고가 사라지는 게 목표).

# Blockers

없음.

# Acceptance

| # | 충족 조건 | 검증 방법 | 통과 기준 |
|---|---|---|---|
| 1 | 캔들 수집이 요청 간 spacing 을 둔다 | 신규 테스트(virtual time) | 마켓 N개 수집 시 경과 시간 ≥ (N-1)×spacing |
| 2 | seed 도 spacing 을 둔다 | 〃 | 〃 |
| 3 | 429 발생 시 backoff 후 재시도 | 신규 테스트(mockk 첫 호출 429 → 둘째 성공) | 재시도로 캔들이 store 에 들어감 |
| 4 | 기존 동작 보존 | 기존 6개 테스트 | 전부 통과 |
| 5 | 트레일링 모순 해소 | `TradingEngine` 경고 조건 대입 | `5.0 > 2.0 && 5.0 > 3.0` → 경고 미발동 |
| 6 | 백테스트 기본값 정합 | `BacktestEngine` 기본값 확인 | application.yml 과 동일 |
| 7 | 빌드·테스트 | `./gradlew :bot:compileKotlin :bot:test` (JDK21) | 통과 |
| 8 | 문서 동기화 | 판정 후 필요 시 README 갱신 | 외부 visible 변경 여부 판단 근거 기록 |

# Deferred

- **`maxHoldDays=1` → 999, `useMarketFilter` → true** 검토: 백테스트상 성과를 지배하는 변인이지만
  일일 정산 전략을 버리는 근본 변경이라 이번 범위에서 제외. 다종목·상승장 표본으로 재검증 후 판단할 것.
- DB 백업 미설정(운영 리스크). `deploy/vultr/README.md` 6절 참조.
