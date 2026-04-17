# Phase C — 리서치·백테스트·리스크 프레임워크 설계

**Date**: 2026-04-17
**Status**: Draft (awaiting implementation plan)
**Owner**: yoon627
**Scope**: Phase C of the repo roadmap (C → A → E, ML 동결). 현재 `bot/engine/BacktestEngine.kt`(214줄) 교체 용도는 아니고, 나란히 존재하는 신규 리서치 엔진.

## 1. 배경과 목적

### 1.1 현재 상태
현 `BacktestEngine.kt`는 소액이라도 실거래에 자본을 투입하기 전 검증용으로 신뢰하기 어렵다. 구체 이슈:

- **Lookahead bias**: 같은 캔들의 종가로 시그널 판정 + 매수 체결 (종가는 장 마감 후에만 알 수 있음)
- **Sharpe 계산 오류**: 거래당 수익률 기반. 정석은 주기별(일별) 수익률 × √252
- **`MIN_CANDLES = 50` 하드코딩**: 타임프레임 무관 적용 (5분봉 50개 ≠ 일봉 50개)
- **단일 자산·단일 전략**: 포트폴리오 레벨 백테스트 불가
- **포지션 사이징 부재**: all-in / none 구조
- **Walk-forward / OOS 분리 없음**: 파라미터 튜닝 시 overfit 검증 불가
- **체결 모델 비현실**: 슬리피지 0, 스프레드 0, 부분체결 없음

### 1.2 목적
- 실거래 소액 투입 전 **"이 전략이 진짜로 돈을 벌까"**를 신뢰할 수 있는 수치로 답할 수 있는 리서치 엔진을 구축
- 다음 단계(A: 팩터 리서치, E: 포트폴리오)가 쌓일 수 있는 기반 확보
- 학습 도구로서 **금융 지표(Sharpe, Sortino, Calmar, VaR, MaxDD)**, **워크포워드 분석**, **포지션 사이징 이론**을 직접 구현하며 배움

### 1.3 명시적 비목표 (v1)
- 틱·호가 레벨 스캘핑 시뮬레이션 (별도 트랙)
- Bayesian / CMA-ES 같은 고급 최적화 (grid search만)
- 라이브 엔진 자동 이식 (수동 포팅)
- 멀티통화 환헷지 회계 (KRW/USD 분리 집계)
- 숏 셀링 (long-only)
- 실시간 대시보드 (파일 리포트만)
- 기존 `BacktestEngine.kt` 삭제 — 병존, 점진적 대체

## 2. 범위 확정 (Q&A 결과)

| 질문 | 결정 |
|---|---|
| 엔진 접근 방식 | **신규 리서치 프레임워크를 기존 엔진과 병렬 구축** |
| 자산·타임프레임 | **주식 + 코인 일봉(1d)**. 스캘핑 제외 |
| 전략 인터페이스 | **계층형** — 기존 `TradingStrategy` 유지 + 신규 `ResearchStrategy` 병존 |
| 데이터 소스 | 기존 PostgreSQL `market_candles` 재사용. 히스토리 부족 시 백필 (수동) |
| 리포트 출력 | JSON(원본) + Markdown(요약) + CSV(거래·에쿼티) |
| 포지션 사이징 | Fixed Fractional + Vol Target + Max DD kill switch |
| Walk-forward | Rolling window (train 2y / test 6mo / step 3mo), grid search |
| 벤치마크 | 자산별 Buy&Hold + SPY(주식) + BTC/KRW(코인) |
| 체결 모델 | 다음 bar 시가 체결 + 거래소별 수수료 + 설정 가능 슬리피지(bps) |

## 3. 아키텍처

### 3.1 모듈 구조
**신규 Gradle 모듈 `:research`** — `settings.gradle.kts`의 기존 `common`, `collector`, `bot` 옆에 추가.

```
coin-trading-bot/
├── common/        # 공유 도메인 (유지)
├── collector/     # 데이터 수집 (유지)
├── bot/           # 라이브 트레이딩 + API (유지, BacktestEngine 그대로 둠)
└── research/      # (신규) 리서치·백테스트·리포트
    └── src/main/kotlin/com/trading/research/
        ├── engine/       # 이벤트 드리븐 시뮬레이터
        ├── strategy/     # ResearchStrategy + LegacyAdapter
        ├── data/         # DataLoader (PG R2DBC)
        ├── execution/    # OrderBook, FillSimulator, 비용 모델
        ├── portfolio/    # Position, PortfolioAccountant
        ├── risk/         # StopLoss, TrailingStop, DrawdownKillSwitch
        ├── sizing/       # FixedFraction, VolTarget, Notional
        ├── walkforward/  # Rolling window split
        ├── metrics/      # Sharpe, Sortino, Calmar, VaR, MaxDD
        ├── report/       # JSON + Markdown + CSV emitters
        └── cli/          # Main.kt CLI runner
```

**의존성 규칙**
- `:research` → `:common` (도메인 타입, 지표) ✓
- `:research` → `:bot` **금지** (라이브 코드가 리서치에 섞이지 못하게)
- 기존 전략(`bot/strategy/*`) 재사용은 **도메인 타입 수준 복제 또는 이동** 중 선택:
  - v1: 필요한 전략 클래스들은 `:common`으로 이동(또는 `:research`가 자체 복제본 소유)
  - 의존 방향을 깨지 않기 위한 의도적 코스트

### 3.2 프로세스 / 패키징
- Spring Boot 컨텍스트 불필요 — plain JVM 애플리케이션
- Kotlin 메인 클래스 `com.trading.research.cli.Main`
- 실행: `./gradlew :research:run --args='...'`
- 설정: 환경변수 + CLI 플래그 (DB 접속은 기존 `bot` 설정 재사용을 위해 `application.yml` 별도 파일)

## 4. 핵심 인터페이스

### 4.1 ResearchStrategy
```kotlin
package com.trading.research.strategy

interface ResearchStrategy {
    val name: String
    val warmupBars: Int  // 각 자산별 최소 필요 히스토리 (예: MA50 전략 → 50)

    /**
     * bar close 이벤트마다 호출. 결정된 주문은 다음 bar의 open에서 체결됨.
     * 포지션 청산 조건은 RiskManager가 독립적으로 평가.
     * suspend로 정의하여 기존 suspend 기반 전략(TradingStrategy.shouldBuyNormalized)을
     * runBlocking 없이 호출 가능.
     */
    suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest>
}

data class BarEvent(
    val asset: Asset,              // Asset(Exchange.UPBIT, "BTC/KRW")
    val bar: Bar,                  // OHLCV + timestamp(UTC)
    val indicators: IndicatorSnapshot,  // RSI, MA, MACD, BB 등 사전 계산
)

interface ResearchContext {
    val clock: ResearchClock       // 현재 시뮬레이션 시각 (결정성)
    val portfolio: PortfolioView   // 읽기 전용 보유 포지션 / 현금
    val universe: UniverseView     // 다른 자산의 최근 bar 조회 (멀티자산 랭킹용)
    val params: Map<String, Any>   // 튜닝 파라미터
}

interface UniverseView {
    val assets: List<Asset>                           // 현재 시뮬 유니버스 전체
    fun recentBars(asset: Asset, count: Int): List<Bar>   // 리서치-네이티브 Bar
    fun recentIndicator(asset: Asset, kind: IndicatorKind, period: Int): Double?
}
// 레거시 어댑터는 List<Bar>를 List<NormalizedCandle>로 변환 후 legacy 전략에 전달

data class OrderRequest(
    val asset: Asset,
    val side: OrderSide,           // BUY / SELL
    val sizing: SizingRule,        // 사이징 규칙 (금액 위임)
    val tag: String = "",          // 사유(STOP_LOSS 등 라벨)
)
```

### 4.2 SizingRule
```kotlin
sealed interface SizingRule {
    data class FixedFraction(val fractionOfEquity: Double) : SizingRule   // 0.1 = 10%
    data class VolTarget(val annualVolTarget: Double, val lookbackDays: Int = 20) : SizingRule
    data class Notional(val amount: Double) : SizingRule  // 고정 통화 금액
    object CloseAll : SizingRule  // 현 포지션 전량 청산
}
```

### 4.3 레거시 어댑터
```kotlin
/**
 * 기존 bot.strategy.TradingStrategy를 ResearchStrategy로 감싸는 어댑터.
 * shouldBuy()만 있고 shouldSell이 없으므로 exit 로직은 RiskManager에 일임.
 */
class LegacyStrategyAdapter(
    private val legacy: TradingStrategy,
    private val sizing: SizingRule = SizingRule.FixedFraction(0.1),
    private val props: TradingProperties,
) : ResearchStrategy {
    override val name = "legacy:${legacy.name}"
    override val warmupBars = 50  // legacy 전략들은 모두 MIN_CANDLES=50 기반

    override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> {
        if (ctx.portfolio.hasPosition(event.asset)) return emptyList()
        val bars = ctx.universe.recentBars(event.asset, warmupBars)
        val normalizedWindow = bars.map { it.toNormalizedCandle(event.asset) }
        return if (legacy.shouldBuyNormalized(normalizedWindow, event.bar.close, props)) {
            listOf(OrderRequest(event.asset, OrderSide.BUY, sizing))
        } else emptyList()
    }
}
```

## 5. 데이터 흐름

```
[PostgreSQL market_candles]
   │  (R2DBC batch load, 시작 시점 1회)
   ▼
[DataLoader] → Map<Asset, List<Bar>>   (자산별 시간순 정렬)
   │
   ▼
[BarStream] → 전 자산 chronological merge
   │  (ties: asset 이름 순, 재현 가능한 결정적 순서)
   ▼
[Engine Loop]
   ├── RiskManager.evaluate(bar) → exit OrderRequest?
   ├── Strategy.onBar(ctx, BarEvent) → entry OrderRequest?
   └── emit OrderRequest
   │
   ▼
[OrderBook] — 다음 bar의 open에서 체결 대기
   │  (다음 이벤트 도달 시)
   ▼
[FillSimulator] — 시가 ± slippage, 수수료 차감, 부분체결 없음 (v1)
   │
   ▼
[PortfolioAccountant] — 포지션/현금/실현·미실현 PnL 업데이트
   │
   ▼
[MetricsAccumulator] — 일별 에쿼티 곡선 누적
   │  (시뮬 종료 시)
   ▼
[ReportEmitter] → research-reports/{strategy}/{YYYYMMDD-HHMMSS}/
    ├── result.json       (기계용 — 모든 수치)
    ├── report.md         (사람용 — 주요 지표 + 차트 링크)
    ├── trades.csv        (개별 거래 리스트)
    └── equity-curve.csv  (일별 에쿼티 + 벤치마크)
```

## 6. 시뮬레이터 루프 (의사코드)

```kotlin
suspend fun run(config: BacktestRunConfig): RunResult {
    val bars: BarStream = dataLoader.load(config.assets, config.period)
    val portfolio = Portfolio(initialCash = config.initialCash)
    val metrics = MetricsAccumulator()
    val orderBook = OrderBook()
    val clock = ResearchClock()

    for (event in bars) {
        clock.advanceTo(event.bar.closeTime)

        // 1. 대기 중 주문 체결 (지난 bar에서 제출된 것들)
        val fills = fillSimulator.fill(orderBook.pending, event.bar.open)
        portfolio.apply(fills)

        // 2. 리스크 평가 (청산 여부)
        val exitOrders = riskManager.evaluate(portfolio, event)
        orderBook.submit(exitOrders)

        // 3. 전략 시그널
        val ctx = ResearchContext(clock, portfolio.view(), universe.view(), config.params)
        val entryOrders = strategy.onBar(ctx, event)
        orderBook.submit(entryOrders)

        // 4. bar close 시점 mark-to-market + 일별 지표 기록
        portfolio.markToMarket(event.bar.close)
        if (clock.isDayBoundary()) {
            metrics.recordDailyEquity(clock.date, portfolio.totalEquity)
        }

        // 5. kill switch
        if (killSwitch.shouldHalt(portfolio)) break
    }

    return buildResult(strategy, portfolio, metrics, config)
}
```

**결정론 보장**
- 동일 입력(시계열 + 설정) → 동일 결과 (해시 대조)
- 난수 사용 시 시드 명시(`config.seed`)
- bar 간 tie-break은 자산 이름 알파벳순

## 7. 메트릭 (올바른 공식)

### 7.1 수익률 시리즈
- **일별 수익률**: $r_t = (E_t - E_{t-1}) / E_{t-1}$ — $E_t$는 bar close 기준 에쿼티

### 7.2 연율화 지표
| 지표 | 공식 | 비고 |
|---|---|---|
| Sharpe | $\bar{r} / \sigma_r \cdot \sqrt{252}$ | per-period 기준, risk-free=0 (v1) |
| Sortino | $\bar{r} / \sigma_{\text{downside}} \cdot \sqrt{252}$ | downside vol만 사용 |
| Calmar | $\text{Annualized Return} / \text{Max DD}$ | 연율화 수익 ÷ 최대 낙폭 |
| Max DD | $\max_t (\text{peak}_t - E_t) / \text{peak}_t$ | 양수 비율 |
| VaR 95%/99% | 일별 수익률 분포의 5/1 percentile | 역사적(historical) |
| CVaR | VaR 이하 평균 | expected shortfall |

### 7.3 거래 기반 지표
- Win rate, Profit factor, Avg trade return, Avg hold days
- Expectancy: `winRate * avgWin - (1-winRate) * avgLoss`

### 7.4 벤치마크 비교
- Excess return, Information Ratio (excess / tracking error)
- Beta (cov/var), Alpha (intercept of regression)
- Max relative drawdown

## 8. Walk-Forward

### 8.1 설계
```kotlin
data class WalkForwardConfig(
    val trainDays: Int = 730,   // 2년
    val testDays: Int = 180,    // 6개월
    val stepDays: Int = 90,     // 3개월
)
```

**절차**
1. 전체 기간을 rolling window로 슬라이딩
2. 각 window에서:
   - train 구간: 파라미터 grid search → 최적 파라미터 선택
   - test 구간: 고정 파라미터로 OOS 실행
3. 모든 window의 test 구간 수익률을 이어붙여 **최종 OOS 에쿼티 곡선** 생성
4. 최종 메트릭은 OOS 곡선 기준

**파라미터 탐색 (v1)**
- Grid: `ParameterGrid.of("rsi_threshold" to listOf(25, 30, 35), "ma_period" to listOf(20, 50))`
- 최적화 대상: `optimizeFor = "sharpe" | "calmar" | "totalReturn"`
- 조기 종료 없음 (전체 조합 평가)

## 9. 리스크 관리

### 9.1 개별 포지션 레벨
- **StopLoss(pct)**: 진입가 대비 -N% → 청산
- **TrailingStop(pct)**: 고점 대비 -N% → 청산 (수익 상태일 때만 활성)
- **TimeExit(maxBars)**: 최대 보유 bar 수 초과 → 청산
- **TakeProfit(pct)**: 진입가 대비 +N% → 청산

### 9.2 포트폴리오 레벨 (Kill Switch)
- **DailyDrawdownHalt(pct)**: 당일 에쿼티 낙폭 > N% → 당일 신규 진입 금지
- **TotalDrawdownHalt(pct)**: 총 낙폭 > N% → 시뮬 중단 (로그 + 리포트 플래그)

### 9.3 포지션 사이징
수식은 `SizingRule` 구현체가 `OrderRequest → notionalAmount` 변환을 담당.

- **FixedFraction(f)**: `notional = portfolio.equity * f`
- **VolTarget(targetVol, lookback)**:
  - `targetVol`은 **연율화 목표 변동성** (예: 0.15 = 15%/년)
  - $\sigma_{\text{daily}}$: 자산 과거 N일 실현 변동성(일별 수익률의 stdev)
  - $\sigma_{\text{annual}} = \sigma_{\text{daily}} \cdot \sqrt{252}$
  - `notional = portfolio.equity * (targetVol / σ_annual)` — 예: 자산 연 변동성 30%, 목표 15% → 자본의 50% 투입
- **Notional(amount)**: `notional = amount` (환율 환산 생략, v1은 자산 통화 그대로)

## 10. CLI

### 10.1 사용 예
```bash
./gradlew :research:run --args='\
  --strategy LegacyRsiBounce \
  --assets UPBIT:BTC/KRW,UPBIT:ETH/KRW,KIS:AAPL,KIS:MSFT \
  --from 2022-01-01 --to 2024-12-31 \
  --timeframe 1d \
  --initial-cash 10000000 \
  --sizing fixed-fraction:0.1 \
  --walkforward train=730,test=180,step=90 \
  --optimize sharpe \
  --params rsi_threshold=25,30,35;ma_period=20,50 \
  --output research-reports/
'
```

### 10.2 출력 구조
```
research-reports/LegacyRsiBounce/20260417-193045/
├── config.json          # 실행 설정 (재현용)
├── result.json          # 모든 수치 (기계 판독)
├── report.md            # 사람 판독용 요약
├── trades.csv
├── equity-curve.csv     # date, equity, benchmark_spy, benchmark_btc
└── walkforward/
    ├── window-01/
    │   ├── params.json  # 선택된 파라미터
    │   └── metrics.json
    └── window-02/...
```

### 10.3 report.md 템플릿 (예시)
```markdown
# Backtest Report: LegacyRsiBounce
Run: 2026-04-17 19:30:45 KST
Period: 2022-01-01 ~ 2024-12-31 (OOS segments concatenated)
Assets: 4 (UPBIT:BTC/KRW, UPBIT:ETH/KRW, KIS:AAPL, KIS:MSFT)

## Headline
| Metric | Value | Benchmark (60/40) |
|---|---|---|
| Total Return | +42.3% | +18.1% |
| CAGR | +12.5% | +5.7% |
| Sharpe | 1.24 | 0.68 |
| Sortino | 1.81 | 0.92 |
| Max DD | -14.2% | -9.8% |
| Calmar | 0.88 | 0.58 |

## Trades
- Total: 87 | Win rate: 54% | Profit factor: 1.62 | Avg hold: 6.2 days

## Walk-Forward
- 6 windows evaluated. Best parameters varied — see walkforward/*
- OOS Sharpe stability: std=0.31 (params sensitive → consider regularization)

## Risk
- Max daily drawdown: -4.1% (2023-06-15 — BTC flash crash)
- VaR 95%: -2.3% / CVaR 95%: -3.6%
```

## 11. 테스트 전략

### 11.1 단위 테스트
- **Metrics**: Known-answer 테스트 (예: 3년 0.1% 일일 수익 → Sharpe ≈ 15.87)
- **FillSimulator**: 수수료·슬리피지 적용 산수 검증
- **SizingRule**: 각 규칙 수식 검증
- **RiskManager**: 스톱로스/트레일링/타임엑싯 경계 조건

### 11.2 통합 테스트
- **Golden dataset**: 고정 캔들 JSON → 고정 결과 JSON. 회귀 방지.
- **Legacy adapter**: 기존 8개 전략이 모두 런타임 예외 없이 실행

### 11.3 안티테스트 (lookahead 검증)
- 의도적으로 "다음 bar를 보고 매매"하는 치트 전략 작성
- 엔진이 치트를 허용하지 않는지(= 치트 전략과 정직한 전략이 같은 결과) 검증

### 11.4 결정론 테스트
- 동일 설정 두 번 실행 → 결과 JSON 해시 동일
- 자산 입력 순서만 바꿔도 결과 동일 (tie-break 검증)

## 12. 마이그레이션 계획 (v1 → 이후)

| 단계 | 내용 |
|---|---|
| **v1 (이번 스펙)** | `:research` 모듈, 이벤트 드리븐 엔진, 레거시 어댑터, 메트릭·워크포워드·리포트, CLI, 기존 8개 전략 어댑터로 재실행 |
| v1.1 | 벤치마크 데이터 자동 로드 (SPY via KIS, BTC/KRW 자동) |
| v2 | 기존 전략을 native `ResearchStrategy`로 1~2개 마이그레이션 (팩터 스타일 시범) |
| v2.1 | 다통화 회계 (KRW/USD 분리 → 환율 기반 통합 에쿼티) |
| Phase A 진입 | 팩터 기반 멀티자산 랭킹 전략 (모멘텀, 밸류, 저변동성) |

## 13. 문서 동기화

**README.md** 업데이트 (v1 완성 시점):
- `## 5. 리서치 / 백테스트` 신규 섹션: CLI 사용법, 출력 포맷, 테스트 실행
- `## 3. 프로젝트 구조` 갱신: Gradle 멀티모듈에 `research/` 추가

**PROJECT_ANALYSIS.md** 업데이트 (v1 완성 시점):
- 섹션 3(프로젝트 구조)에 `research/` 모듈 블록 추가
- 섹션 12(핵심 아키텍처 패턴)에 "리서치-라이브 분리" 항목 추가

## 14. 위험과 완화

| 위험 | 완화 |
|---|---|
| 기존 전략이 `bot` 내부 타입(`TradingProperties`, `bot/domain/Candle`)에 의존 → `:research`에서 재사용 불가 | v1 초기에 필요한 타입을 `:common`으로 이동. 어려우면 `:research`에 얇은 복제본 제작 후 v2에서 통합 |
| 데이터 부족 (PG `market_candles`에 과거 데이터 없음) | v1에 히스토리 **수동 백필 스크립트**(CLI 명령으로 거래소 REST를 장기간 페이징) 포함 |
| Walk-forward grid search가 너무 느림 | v1은 직렬. `--parallel N` 플래그로 조합별 병렬 실행 옵션(v1.1) |
| R2DBC 배치 로드 메모리 압박 | 자산 단위 스트리밍 로드 (한 자산씩 fetch → 메모리 상주는 결합된 결과만) |

## 15. 성공 기준 (v1 DoD)

1. ✅ 기존 8개 전략(RsiBounce, GoldenCross, MacdCross, BollingerBounce, MeanReversion, VolatilityBreakout, CombinedStrategy, MlStrategy) 모두 `:research` 엔진에서 재실행 가능
2. ✅ 동일 입력에 대해 두 번 실행 결과 JSON이 바이트 레벨 동일(결정론)
3. ✅ 치트(lookahead) 전략이 정직한 전략보다 좋은 결과를 내지 못함
4. ✅ Golden dataset 회귀 테스트 통과
5. ✅ `report.md`를 보고 "이 전략을 소액 실거래로 가져갈지" 사용자가 판단 가능한 정보가 모두 들어 있음
6. ✅ README·PROJECT_ANALYSIS.md 갱신 완료
7. ✅ `./gradlew :research:test` 모든 테스트 통과
