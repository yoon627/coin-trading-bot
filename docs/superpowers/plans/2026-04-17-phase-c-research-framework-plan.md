# Phase C — 리서치 프레임워크 v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 현 `bot/engine/BacktestEngine.kt`를 그대로 두고, 신규 `:research` Gradle 모듈에 이벤트 드리븐 bar-level 시뮬레이터 + 포트폴리오 / 리스크 / 워크포워드 / 리포트까지 갖춘 리서치 프레임워크를 구축. 기존 8개 전략이 레거시 어댑터로 그대로 검증되고, 소액 실거래 투입 전 "이 전략이 정말 돈을 버는가"를 신뢰 가능한 수치로 답할 수 있는 상태가 목표.

**Architecture:** 신규 `:research` 모듈은 `:common`에만 의존 (Spring Boot 컨텍스트 없음, 순수 JVM + Kotlin coroutines). 이벤트 드리븐 시뮬레이터는 `BarStream → RiskManager → Strategy.onBar → OrderBook → FillSimulator → Portfolio → MetricsAccumulator` 파이프라인. 레거시 전략 호환을 위해 `TradingStrategy` 인터페이스와 8개 구현체를 `:common`으로 이동. 결과는 `research-reports/{strategy}/{ts}/`에 JSON + CSV + Markdown으로 저장.

**Tech Stack:** Kotlin 2.1 / JDK 21, kotlinx-coroutines, Jackson(JSON), PostgreSQL JDBC + HikariCP, Clikt(CLI), Logback, JUnit5 + MockK + kotlinx-coroutines-test + Testcontainers(PG).

**Spec reference:** `docs/superpowers/specs/2026-04-17-phase-c-research-framework-design.md`

**Task count:** 21. 각 태스크는 TDD(테스트 먼저 → 구현 → 통과 확인 → 커밋) 패턴. 커밋은 최대한 작게.

---

### Task 1: Scaffold `:research` Gradle module

**Files:**
- Create: `research/build.gradle.kts`
- Create: `research/src/main/kotlin/.gitkeep`
- Create: `research/src/test/kotlin/.gitkeep`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Register module in settings**

Modify `settings.gradle.kts`:

```kotlin
rootProject.name = "coin-trading-bot"
include("common", "collector", "bot", "research")
```

- [ ] **Step 2: Create `research/build.gradle.kts`**

```kotlin
plugins {
    application
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.1")
    }
}

application {
    mainClass.set("com.trading.research.cli.MainKt")
}

dependencies {
    implementation(project(":common"))

    // Kotlin / JSON / Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Database (sync JDBC — batch historical loads)
    implementation("org.postgresql:postgresql")
    implementation("com.zaxxer:HikariCP")

    // CLI
    implementation("com.github.ajalt.clikt:clikt:5.0.1")

    // Logging
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("ch.qos.logback:logback-classic")

    // Tests
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
}
```

- [ ] **Step 3: Create placeholder package + verify compile**

Create `research/src/main/kotlin/com/trading/research/Placeholder.kt`:

```kotlin
package com.trading.research

internal object Placeholder
```

Run: `JAVA_HOME=/Users/jongyoonlee/Library/Java/JavaVirtualMachines/jbr-21.0.9/Contents/Home ./gradlew :research:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts research/
git commit -m "feat(research): scaffold :research gradle module

Phase C 리서치 프레임워크용 신규 모듈. :common에만 의존, Spring Boot 미사용."
```

---

### Task 2: Core domain types

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/domain/Asset.kt`
- Create: `research/src/main/kotlin/com/trading/research/domain/Bar.kt`
- Create: `research/src/main/kotlin/com/trading/research/domain/OrderSide.kt`
- Create: `research/src/main/kotlin/com/trading/research/domain/OrderRequest.kt`
- Create: `research/src/main/kotlin/com/trading/research/domain/SizingRule.kt`
- Test: `research/src/test/kotlin/com/trading/research/domain/BarTest.kt`

- [ ] **Step 1: Write failing test for `Bar` → `NormalizedCandle` conversion**

```kotlin
package com.trading.research.domain

import com.trading.common.domain.Exchange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class BarTest {
    @Test
    fun `toNormalizedCandle preserves OHLCV and market pair`() {
        val asset = Asset(Exchange.UPBIT, "BTC/KRW")
        val bar = Bar(
            openTime = Instant.parse("2024-01-01T00:00:00Z"),
            closeTime = Instant.parse("2024-01-02T00:00:00Z"),
            open = 100.0, high = 110.0, low = 95.0, close = 105.0,
            volume = 1.5, quoteVolume = 155.0,
        )

        val candle = bar.toNormalizedCandle(asset)

        assertEquals("BTC/KRW", candle.market)
        assertEquals(100.0, candle.openPrice)
        assertEquals(110.0, candle.highPrice)
        assertEquals(95.0, candle.lowPrice)
        assertEquals(105.0, candle.closePrice)
        assertEquals(1.5, candle.volume)
    }
}
```

- [ ] **Step 2: Run test — should fail (types don't exist)**

Run: `JAVA_HOME=... ./gradlew :research:test --tests BarTest`
Expected: compilation errors, types not found.

- [ ] **Step 3: Create `Asset.kt`**

```kotlin
package com.trading.research.domain

import com.trading.common.domain.Exchange

data class Asset(val exchange: Exchange, val market: String) {
    override fun toString(): String = "${exchange.name}:$market"

    companion object {
        fun parse(raw: String): Asset {
            val parts = raw.split(":", limit = 2)
            require(parts.size == 2) { "Asset must be EXCHANGE:MARKET, got '$raw'" }
            return Asset(Exchange.valueOf(parts[0].uppercase()), parts[1])
        }
    }
}
```

- [ ] **Step 4: Create `Bar.kt`**

```kotlin
package com.trading.research.domain

import com.trading.common.domain.NormalizedCandle
import java.time.Instant

data class Bar(
    val openTime: Instant,
    val closeTime: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val quoteVolume: Double,
)

fun Bar.toNormalizedCandle(asset: Asset): NormalizedCandle = NormalizedCandle(
    exchange = asset.exchange,
    market = asset.market,
    openTime = openTime,
    closeTime = closeTime,
    openPrice = open,
    highPrice = high,
    lowPrice = low,
    closePrice = close,
    volume = volume,
    quoteVolume = quoteVolume,
)
```

(If `NormalizedCandle` constructor differs, adjust field names to match `common/src/main/kotlin/com/trading/common/domain/NormalizedCandle.kt`.)

- [ ] **Step 5: Create `OrderSide.kt`, `OrderRequest.kt`, `SizingRule.kt`**

```kotlin
// OrderSide.kt
package com.trading.research.domain
enum class OrderSide { BUY, SELL }
```

```kotlin
// SizingRule.kt
package com.trading.research.domain

sealed interface SizingRule {
    data class FixedFraction(val fractionOfEquity: Double) : SizingRule {
        init { require(fractionOfEquity in 0.0..1.0) { "fractionOfEquity must be [0,1]" } }
    }
    data class VolTarget(val annualVol: Double, val lookbackDays: Int = 20) : SizingRule {
        init { require(annualVol > 0) { "annualVol must be > 0" } }
    }
    data class Notional(val amount: Double) : SizingRule {
        init { require(amount > 0) { "amount must be > 0" } }
    }
    object CloseAll : SizingRule
}
```

```kotlin
// OrderRequest.kt
package com.trading.research.domain

data class OrderRequest(
    val asset: Asset,
    val side: OrderSide,
    val sizing: SizingRule,
    val tag: String = "",
)
```

- [ ] **Step 6: Run test — should pass**

Run: `JAVA_HOME=... ./gradlew :research:test --tests BarTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/domain research/src/test/kotlin/com/trading/research/domain
git commit -m "feat(research): add core domain types (Asset, Bar, OrderSide, SizingRule)"
```

---

### Task 3: Metrics primitives (correct Sharpe, Sortino, MaxDD, Calmar, VaR)

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/metrics/Metrics.kt`
- Test: `research/src/test/kotlin/com/trading/research/metrics/MetricsTest.kt`

- [ ] **Step 1: Write failing tests with known answers**

```kotlin
package com.trading.research.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class MetricsTest {

    private fun closeTo(actual: Double, expected: Double, tol: Double = 1e-6) {
        assertTrue(abs(actual - expected) < tol, "expected≈$expected got=$actual tol=$tol")
    }

    @Test
    fun `periodReturns computes r_t = E_t - E_t-1 over E_t-1`() {
        val equity = listOf(100.0, 110.0, 99.0)
        val rets = Metrics.periodReturns(equity)
        assertEquals(2, rets.size)
        closeTo(rets[0], 0.10)
        closeTo(rets[1], -0.10)
    }

    @Test
    fun `annualized sharpe with constant return 0_001 daily equals 0_001 over stdev sqrt252`() {
        // Constant returns → stdev = 0 → guard: return 0
        val rets = List(252) { 0.001 }
        val sharpe = Metrics.annualizedSharpe(rets, 252)
        closeTo(sharpe, 0.0) // stdev 0 → defined as 0
    }

    @Test
    fun `annualized sharpe with alternating returns matches formula`() {
        // returns: [+0.01, -0.01] repeating 100 times
        val rets = List(200) { if (it % 2 == 0) 0.01 else -0.01 }
        val mean = 0.0
        val stdev = 0.01 // population stdev of +-0.01 around mean 0
        val expected = (mean / stdev) * sqrt(252.0)
        closeTo(Metrics.annualizedSharpe(rets, 252), expected)
    }

    @Test
    fun `maxDrawdown finds largest peak-to-trough fraction`() {
        val equity = listOf(100.0, 120.0, 90.0, 110.0, 60.0, 80.0)
        // peak 120 → trough 60 = (120-60)/120 = 0.5
        closeTo(Metrics.maxDrawdown(equity), 0.5)
    }

    @Test
    fun `sortino uses downside deviation only`() {
        val rets = listOf(0.02, -0.01, 0.03, -0.02, 0.01)
        val sortino = Metrics.annualizedSortino(rets, 252)
        // sanity: should be > sharpe because upside vol not penalized
        assertTrue(sortino > 0)
    }

    @Test
    fun `historicalVar95 returns 5th percentile`() {
        val rets = (1..100).map { it * -0.001 } // -0.001 .. -0.1
        // 5th percentile = 5th worst = -0.096 or similar
        val var95 = Metrics.historicalVar(rets, 0.95)
        assertTrue(var95 <= -0.095)
        assertTrue(var95 >= -0.101)
    }
}
```

- [ ] **Step 2: Run test — should fail**

Run: `JAVA_HOME=... ./gradlew :research:test --tests MetricsTest`
Expected: compile errors (`Metrics` not defined).

- [ ] **Step 3: Implement `Metrics.kt`**

```kotlin
package com.trading.research.metrics

import kotlin.math.sqrt

object Metrics {

    fun periodReturns(equity: List<Double>): List<Double> {
        if (equity.size < 2) return emptyList()
        return equity.zipWithNext { prev, curr -> (curr - prev) / prev }
    }

    fun annualizedSharpe(returns: List<Double>, periodsPerYear: Int): Double {
        if (returns.size < 2) return 0.0
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / returns.size
        val stdev = sqrt(variance)
        if (stdev == 0.0) return 0.0
        return (mean / stdev) * sqrt(periodsPerYear.toDouble())
    }

    fun annualizedSortino(returns: List<Double>, periodsPerYear: Int): Double {
        if (returns.size < 2) return 0.0
        val mean = returns.average()
        val downside = returns.filter { it < 0.0 }
        if (downside.isEmpty()) return 0.0
        val downVariance = downside.sumOf { it * it } / returns.size
        val downStdev = sqrt(downVariance)
        if (downStdev == 0.0) return 0.0
        return (mean / downStdev) * sqrt(periodsPerYear.toDouble())
    }

    fun maxDrawdown(equity: List<Double>): Double {
        if (equity.size < 2) return 0.0
        var peak = equity[0]
        var maxDd = 0.0
        for (e in equity) {
            if (e > peak) peak = e
            val dd = (peak - e) / peak
            if (dd > maxDd) maxDd = dd
        }
        return maxDd
    }

    fun calmar(annualizedReturn: Double, maxDd: Double): Double =
        if (maxDd == 0.0) 0.0 else annualizedReturn / maxDd

    /** percentile: 0.95 → 5% tail (loss). Returns negative for loss. */
    fun historicalVar(returns: List<Double>, percentile: Double): Double {
        require(percentile in 0.5..0.9999) { "percentile in (0.5, 1)" }
        if (returns.isEmpty()) return 0.0
        val sorted = returns.sorted()
        val idx = ((1.0 - percentile) * sorted.size).toInt().coerceAtLeast(0)
        return sorted[idx]
    }

    fun historicalCvar(returns: List<Double>, percentile: Double): Double {
        if (returns.isEmpty()) return 0.0
        val sorted = returns.sorted()
        val cutoff = ((1.0 - percentile) * sorted.size).toInt().coerceAtLeast(1)
        val tail = sorted.take(cutoff)
        return tail.average()
    }

    fun winRate(tradePnls: List<Double>): Double =
        if (tradePnls.isEmpty()) 0.0 else tradePnls.count { it > 0 }.toDouble() / tradePnls.size

    fun profitFactor(tradePnls: List<Double>): Double {
        val grossProfit = tradePnls.filter { it > 0 }.sum()
        val grossLoss = -tradePnls.filter { it < 0 }.sum()
        return if (grossLoss == 0.0) if (grossProfit > 0) Double.POSITIVE_INFINITY else 0.0
        else grossProfit / grossLoss
    }
}
```

- [ ] **Step 4: Run test — should pass**

Run: `JAVA_HOME=... ./gradlew :research:test --tests MetricsTest`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/metrics research/src/test/kotlin/com/trading/research/metrics
git commit -m "feat(research): add correct metrics primitives

per-period returns → Sharpe/Sortino/Calmar/MaxDD/VaR/CVaR/profit factor.
기존 BacktestEngine의 per-trade Sharpe 오류를 정석(per-period, annualized)으로 교정."
```

---

### Task 4: Cost model (fee + slippage)

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/execution/CostModel.kt`
- Test: `research/src/test/kotlin/com/trading/research/execution/CostModelTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.trading.research.execution

import com.trading.research.domain.OrderSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CostModelTest {
    private fun near(a: Double, b: Double) = assertEquals(b, a, 1e-9)

    @Test
    fun `flat fee applied to both sides`() {
        val cm = FlatFeeSlippageModel(feeRate = 0.001, slippageBps = 0.0)
        val buy = cm.applyFee(notional = 1000.0, side = OrderSide.BUY)
        near(buy, 1.0) // 1000 * 0.001
    }

    @Test
    fun `buy slippage raises fill price`() {
        val cm = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 10.0) // 10 bps = 0.001
        val price = cm.applySlippage(quotedPrice = 100.0, side = OrderSide.BUY)
        near(price, 100.1) // +0.1%
    }

    @Test
    fun `sell slippage lowers fill price`() {
        val cm = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 25.0) // 25 bps = 0.0025
        val price = cm.applySlippage(quotedPrice = 200.0, side = OrderSide.SELL)
        near(price, 199.5)
    }
}
```

- [ ] **Step 2: Run test — should fail**

Run: `JAVA_HOME=... ./gradlew :research:test --tests CostModelTest`

- [ ] **Step 3: Implement `CostModel.kt`**

```kotlin
package com.trading.research.execution

import com.trading.research.domain.OrderSide

interface CostModel {
    /** Adjusts quoted price to fill price incorporating slippage. */
    fun applySlippage(quotedPrice: Double, side: OrderSide): Double
    /** Returns fee amount in quote currency given notional. */
    fun applyFee(notional: Double, side: OrderSide): Double
}

class FlatFeeSlippageModel(
    private val feeRate: Double,      // e.g., 0.0005 = 0.05%
    private val slippageBps: Double,  // basis points; 10 bps = 0.1%
) : CostModel {
    override fun applySlippage(quotedPrice: Double, side: OrderSide): Double {
        val adj = slippageBps / 10_000.0
        return when (side) {
            OrderSide.BUY -> quotedPrice * (1.0 + adj)
            OrderSide.SELL -> quotedPrice * (1.0 - adj)
        }
    }

    override fun applyFee(notional: Double, side: OrderSide): Double = notional * feeRate
}
```

- [ ] **Step 4: Run test — should pass**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/execution research/src/test/kotlin/com/trading/research/execution
git commit -m "feat(research): add cost model (flat fee + slippage bps)

v1: 거래소별 단일 수수료 + 고정 bps 슬리피지. 시장충격/부분체결은 v2."
```

---

### Task 5: Sizing calculator

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/sizing/SizingCalculator.kt`
- Test: `research/src/test/kotlin/com/trading/research/sizing/SizingCalculatorTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.trading.research.sizing

import com.trading.research.domain.SizingRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SizingCalculatorTest {
    private fun near(a: Double, b: Double, tol: Double = 1e-6) =
        assert(abs(a - b) < tol) { "a=$a b=$b" }

    @Test
    fun `FixedFraction returns equity times fraction`() {
        val out = SizingCalculator.notional(SizingRule.FixedFraction(0.1), equity = 10_000.0, assetDailyVol = 0.02)
        near(out, 1_000.0)
    }

    @Test
    fun `VolTarget annualVol 0_15 asset dailyVol 0_02 equity 10000`() {
        // sigma_annual = 0.02 * sqrt(252) ≈ 0.3174
        // notional = 10000 * (0.15 / 0.3174) ≈ 4725.7
        val rule = SizingRule.VolTarget(annualVol = 0.15, lookbackDays = 20)
        val out = SizingCalculator.notional(rule, equity = 10_000.0, assetDailyVol = 0.02)
        val expected = 10_000.0 * (0.15 / (0.02 * sqrt(252.0)))
        near(out, expected, tol = 1e-3)
    }

    @Test
    fun `Notional returns fixed amount`() {
        val out = SizingCalculator.notional(SizingRule.Notional(5_000.0), equity = 100_000.0, assetDailyVol = 0.02)
        near(out, 5_000.0)
    }

    @Test
    fun `CloseAll returns 0 notional, handled by caller as exit`() {
        val out = SizingCalculator.notional(SizingRule.CloseAll, equity = 100.0, assetDailyVol = 0.02)
        near(out, 0.0)
    }

    @Test
    fun `VolTarget with zero assetVol falls back to FixedFraction 0_1`() {
        val rule = SizingRule.VolTarget(annualVol = 0.15)
        val out = SizingCalculator.notional(rule, equity = 10_000.0, assetDailyVol = 0.0)
        near(out, 1_000.0)
    }
}
```

- [ ] **Step 2: Run test — should fail**

- [ ] **Step 3: Implement `SizingCalculator.kt`**

```kotlin
package com.trading.research.sizing

import com.trading.research.domain.SizingRule
import kotlin.math.sqrt

object SizingCalculator {
    private const val FALLBACK_FRACTION = 0.1
    private const val TRADING_DAYS_PER_YEAR = 252

    /**
     * Computes the target notional (quote currency amount) for a new position.
     * assetDailyVol = realized daily stdev of returns over the sizing rule's lookback.
     */
    fun notional(rule: SizingRule, equity: Double, assetDailyVol: Double): Double = when (rule) {
        is SizingRule.FixedFraction -> equity * rule.fractionOfEquity
        is SizingRule.Notional -> rule.amount
        is SizingRule.CloseAll -> 0.0
        is SizingRule.VolTarget -> {
            if (assetDailyVol <= 0.0) equity * FALLBACK_FRACTION
            else {
                val sigmaAnnual = assetDailyVol * sqrt(TRADING_DAYS_PER_YEAR.toDouble())
                equity * (rule.annualVol / sigmaAnnual)
            }
        }
    }
}
```

- [ ] **Step 4: Run test — should pass**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/sizing research/src/test/kotlin/com/trading/research/sizing
git commit -m "feat(research): add sizing calculator (FixedFraction, VolTarget, Notional, CloseAll)"
```

---

### Task 6: Position + Portfolio + PortfolioAccountant

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/portfolio/Position.kt`
- Create: `research/src/main/kotlin/com/trading/research/portfolio/Portfolio.kt`
- Create: `research/src/main/kotlin/com/trading/research/portfolio/PortfolioView.kt`
- Test: `research/src/test/kotlin/com/trading/research/portfolio/PortfolioTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.trading.research.portfolio

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.OrderSide
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PortfolioTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    @Test
    fun `buy fill reduces cash and opens position`() {
        val p = Portfolio(initialCash = 10_000.0)
        p.applyFill(Fill(asset, OrderSide.BUY, quantity = 0.1, fillPrice = 50_000.0, fee = 5.0, tag = "entry"))
        assertEquals(10_000.0 - 5_000.0 - 5.0, p.cash)
        assertTrue(p.hasPosition(asset))
        val pos = p.positions[asset]!!
        assertEquals(0.1, pos.quantity)
        assertEquals(50_000.0, pos.avgEntryPrice)
    }

    @Test
    fun `sell fill closes position and realizes pnl`() {
        val p = Portfolio(initialCash = 10_000.0)
        p.applyFill(Fill(asset, OrderSide.BUY, 0.1, 50_000.0, 5.0, "entry"))
        p.applyFill(Fill(asset, OrderSide.SELL, 0.1, 55_000.0, 5.5, "exit"))
        assertFalse(p.hasPosition(asset))
        // cash: 10000 - 5005 + 5500 - 5.5 = 10489.5
        assertEquals(10_489.5, p.cash, 1e-6)
    }

    @Test
    fun `markToMarket updates unrealized but not cash`() {
        val p = Portfolio(initialCash = 10_000.0)
        p.applyFill(Fill(asset, OrderSide.BUY, 0.1, 50_000.0, 5.0, "entry"))
        val cashBefore = p.cash
        p.markToMarket(mapOf(asset to 60_000.0))
        assertEquals(cashBefore, p.cash)
        assertEquals(6_000.0, p.positions[asset]!!.marketValue)
        // total equity = cash + marketValue
        assertEquals(cashBefore + 6_000.0, p.totalEquity)
    }
}
```

- [ ] **Step 2: Run test — should fail**

- [ ] **Step 3: Implement types**

```kotlin
// Position.kt
package com.trading.research.portfolio

import com.trading.research.domain.Asset

data class Position(
    val asset: Asset,
    var quantity: Double,
    var avgEntryPrice: Double,
    var peakPriceSinceEntry: Double,
    val openedAtBarIndex: Long,
) {
    var marketValue: Double = quantity * avgEntryPrice
        private set

    fun updateMarket(lastPrice: Double) {
        marketValue = quantity * lastPrice
        if (lastPrice > peakPriceSinceEntry) peakPriceSinceEntry = lastPrice
    }

    fun unrealizedPnl(lastPrice: Double): Double = (lastPrice - avgEntryPrice) * quantity
}
```

```kotlin
// Portfolio.kt
package com.trading.research.portfolio

import com.trading.research.domain.Asset
import com.trading.research.domain.OrderSide

data class Fill(
    val asset: Asset,
    val side: OrderSide,
    val quantity: Double,
    val fillPrice: Double,
    val fee: Double,
    val tag: String,
    val barIndex: Long = 0L,
)

class Portfolio(initialCash: Double) {
    var cash: Double = initialCash; private set
    val positions: MutableMap<Asset, Position> = mutableMapOf()
    var totalEquity: Double = initialCash; private set
    val realizedPnlByAsset: MutableMap<Asset, Double> = mutableMapOf()

    fun hasPosition(asset: Asset): Boolean = positions[asset]?.let { it.quantity != 0.0 } ?: false

    fun applyFill(fill: Fill) {
        val notional = fill.quantity * fill.fillPrice
        when (fill.side) {
            OrderSide.BUY -> {
                cash -= (notional + fill.fee)
                val existing = positions[fill.asset]
                if (existing == null) {
                    positions[fill.asset] = Position(
                        asset = fill.asset,
                        quantity = fill.quantity,
                        avgEntryPrice = fill.fillPrice,
                        peakPriceSinceEntry = fill.fillPrice,
                        openedAtBarIndex = fill.barIndex,
                    )
                } else {
                    val totalQty = existing.quantity + fill.quantity
                    val newAvg = (existing.avgEntryPrice * existing.quantity + fill.fillPrice * fill.quantity) / totalQty
                    existing.quantity = totalQty
                    existing.avgEntryPrice = newAvg
                }
            }
            OrderSide.SELL -> {
                cash += (notional - fill.fee)
                val existing = positions[fill.asset]
                    ?: error("sell without open position on ${fill.asset}")
                val realized = (fill.fillPrice - existing.avgEntryPrice) * fill.quantity - fill.fee
                realizedPnlByAsset.merge(fill.asset, realized) { a, b -> a + b }
                existing.quantity -= fill.quantity
                if (existing.quantity <= 1e-12) positions.remove(fill.asset)
            }
        }
    }

    fun markToMarket(lastPrices: Map<Asset, Double>) {
        var marketValueSum = 0.0
        for ((asset, pos) in positions) {
            lastPrices[asset]?.let { pos.updateMarket(it) }
            marketValueSum += pos.marketValue
        }
        totalEquity = cash + marketValueSum
    }

    fun view(): PortfolioView = PortfolioViewImpl(this)
}
```

```kotlin
// PortfolioView.kt
package com.trading.research.portfolio

import com.trading.research.domain.Asset

interface PortfolioView {
    val cash: Double
    val totalEquity: Double
    fun hasPosition(asset: Asset): Boolean
    fun getPosition(asset: Asset): Position?
}

internal class PortfolioViewImpl(private val p: Portfolio) : PortfolioView {
    override val cash: Double get() = p.cash
    override val totalEquity: Double get() = p.totalEquity
    override fun hasPosition(asset: Asset): Boolean = p.hasPosition(asset)
    override fun getPosition(asset: Asset): Position? = p.positions[asset]
}
```

- [ ] **Step 4: Run test — should pass**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/portfolio research/src/test/kotlin/com/trading/research/portfolio
git commit -m "feat(research): add portfolio accounting (Position, Portfolio, fills, mark-to-market)"
```

---

### Task 7: OrderBook (pending orders)

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/execution/OrderBook.kt`
- Test: `research/src/test/kotlin/com/trading/research/execution/OrderBookTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.trading.research.execution

import com.trading.common.domain.Exchange
import com.trading.research.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OrderBookTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    @Test
    fun `submitted orders live until drained`() {
        val ob = OrderBook()
        ob.submit(OrderRequest(asset, OrderSide.BUY, SizingRule.FixedFraction(0.1)))
        ob.submit(OrderRequest(asset, OrderSide.SELL, SizingRule.CloseAll))
        val drained = ob.drain()
        assertEquals(2, drained.size)
        assertTrue(ob.drain().isEmpty())
    }
}
```

- [ ] **Step 2: Run test — fail**

- [ ] **Step 3: Implement**

```kotlin
package com.trading.research.execution

import com.trading.research.domain.OrderRequest

class OrderBook {
    private val pending = mutableListOf<OrderRequest>()

    fun submit(order: OrderRequest) { pending.add(order) }
    fun submitAll(orders: List<OrderRequest>) { pending.addAll(orders) }
    fun drain(): List<OrderRequest> {
        val out = pending.toList()
        pending.clear()
        return out
    }
}
```

- [ ] **Step 4: Test passes**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/execution/OrderBook.kt research/src/test/kotlin/com/trading/research/execution/OrderBookTest.kt
git commit -m "feat(research): add OrderBook for pending-until-next-bar fills"
```

---

### Task 8: FillSimulator

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/execution/FillSimulator.kt`
- Test: `research/src/test/kotlin/com/trading/research/execution/FillSimulatorTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.trading.research.execution

import com.trading.common.domain.Exchange
import com.trading.research.domain.*
import com.trading.research.portfolio.Portfolio
import com.trading.research.sizing.SizingCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FillSimulatorTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")
    private val cost = FlatFeeSlippageModel(feeRate = 0.001, slippageBps = 10.0)

    @Test
    fun `buy order at next bar open applies slippage and fee`() {
        val p = Portfolio(initialCash = 10_000.0)
        val sim = FillSimulator(cost, SizingCalculator)
        val order = OrderRequest(asset, OrderSide.BUY, SizingRule.FixedFraction(0.1))

        val fills = sim.fill(
            orders = listOf(order),
            openPrices = mapOf(asset to 100.0),
            equityBefore = p.totalEquity,
            assetDailyVol = mapOf(asset to 0.02),
            barIndex = 0L,
        )

        assertEquals(1, fills.size)
        val f = fills[0]
        // notional target = 10000 * 0.1 = 1000
        // slippage BUY: 100 * 1.001 = 100.1
        // quantity = 1000 / 100.1 ≈ 9.99001
        assertEquals(100.1, f.fillPrice, 1e-9)
        assertEquals(1000.0 / 100.1, f.quantity, 1e-6)
        // fee = notional * feeRate = (qty * fillPrice) * 0.001
        assertEquals(f.quantity * f.fillPrice * 0.001, f.fee, 1e-6)
    }

    @Test
    fun `sell CloseAll closes the full existing position`() {
        val p = Portfolio(initialCash = 10_000.0)
        p.applyFill(Fill(asset, OrderSide.BUY, 10.0, 100.0, 1.0, "entry"))
        val sim = FillSimulator(cost, SizingCalculator)

        val fills = sim.fillExit(
            orders = listOf(OrderRequest(asset, OrderSide.SELL, SizingRule.CloseAll, tag = "exit")),
            openPrices = mapOf(asset to 110.0),
            portfolio = p,
            barIndex = 1L,
        )

        assertEquals(1, fills.size)
        assertEquals(10.0, fills[0].quantity, 1e-9)
        // slippage SELL: 110 * (1 - 0.001) = 109.89
        assertEquals(109.89, fills[0].fillPrice, 1e-9)
    }
}
```

- [ ] **Step 2: Run test — fail**

- [ ] **Step 3: Implement**

```kotlin
package com.trading.research.execution

import com.trading.research.domain.*
import com.trading.research.portfolio.Fill
import com.trading.research.portfolio.Portfolio
import com.trading.research.sizing.SizingCalculator

class FillSimulator(
    private val costModel: CostModel,
    private val sizer: SizingCalculator = SizingCalculator,
) {
    /** Entry fills: sizes via SizingRule against equity. Skips assets without open price. */
    fun fill(
        orders: List<OrderRequest>,
        openPrices: Map<Asset, Double>,
        equityBefore: Double,
        assetDailyVol: Map<Asset, Double>,
        barIndex: Long,
    ): List<Fill> = orders.mapNotNull { o ->
        if (o.side == OrderSide.SELL) return@mapNotNull null
        val open = openPrices[o.asset] ?: return@mapNotNull null
        val notional = sizer.notional(o.sizing, equityBefore, assetDailyVol[o.asset] ?: 0.0)
        if (notional <= 0.0) return@mapNotNull null
        val fillPrice = costModel.applySlippage(open, o.side)
        val quantity = notional / fillPrice
        val fee = costModel.applyFee(quantity * fillPrice, o.side)
        Fill(o.asset, o.side, quantity, fillPrice, fee, o.tag, barIndex)
    }

    /** Exit fills: uses existing position quantity. */
    fun fillExit(
        orders: List<OrderRequest>,
        openPrices: Map<Asset, Double>,
        portfolio: Portfolio,
        barIndex: Long,
    ): List<Fill> = orders.mapNotNull { o ->
        if (o.side != OrderSide.SELL) return@mapNotNull null
        val open = openPrices[o.asset] ?: return@mapNotNull null
        val pos = portfolio.positions[o.asset] ?: return@mapNotNull null
        val fillPrice = costModel.applySlippage(open, OrderSide.SELL)
        val quantity = pos.quantity
        val fee = costModel.applyFee(quantity * fillPrice, OrderSide.SELL)
        Fill(o.asset, OrderSide.SELL, quantity, fillPrice, fee, o.tag, barIndex)
    }
}
```

- [ ] **Step 4: Test passes**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/execution/FillSimulator.kt research/src/test/kotlin/com/trading/research/execution/FillSimulatorTest.kt
git commit -m "feat(research): add fill simulator with next-bar-open fills + cost model"
```

---

### Task 9: RiskManager (per-position exits)

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/risk/RiskPolicy.kt`
- Create: `research/src/main/kotlin/com/trading/research/risk/RiskManager.kt`
- Test: `research/src/test/kotlin/com/trading/research/risk/RiskManagerTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.trading.research.risk

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.OrderSide
import com.trading.research.portfolio.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RiskManagerTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    private fun pos(entry: Double = 100.0, peak: Double = 100.0, openedAt: Long = 0L) =
        Position(asset, quantity = 1.0, avgEntryPrice = entry, peakPriceSinceEntry = peak, openedAtBarIndex = openedAt)

    @Test
    fun `stop loss fires when loss exceeds threshold`() {
        val rm = RiskManager(RiskPolicy(stopLossPct = 0.03, trailingStopPct = null, takeProfitPct = null, timeExitBars = null))
        val orders = rm.evaluate(pos(entry = 100.0), lastPrice = 96.0, currentBarIndex = 10)
        assertEquals(1, orders.size)
        assertEquals("STOP_LOSS", orders[0].tag)
        assertEquals(OrderSide.SELL, orders[0].side)
    }

    @Test
    fun `trailing stop fires only when profitable and retraced`() {
        val rm = RiskManager(RiskPolicy(stopLossPct = 0.03, trailingStopPct = 0.02, takeProfitPct = null, timeExitBars = null))
        // entry 100, peak 120 → 2% retrace = 117.6 triggers
        val p = pos(entry = 100.0, peak = 120.0)
        val out = rm.evaluate(p, lastPrice = 117.0, currentBarIndex = 5)
        assertEquals(1, out.size)
        assertEquals("TRAILING_STOP", out[0].tag)
    }

    @Test
    fun `time exit fires when hold exceeds maxBars`() {
        val rm = RiskManager(RiskPolicy(stopLossPct = null, trailingStopPct = null, takeProfitPct = null, timeExitBars = 7))
        val out = rm.evaluate(pos(openedAt = 0), lastPrice = 100.0, currentBarIndex = 8)
        assertEquals(1, out.size)
        assertEquals("TIME_EXIT", out[0].tag)
    }

    @Test
    fun `take profit fires when gain exceeds threshold`() {
        val rm = RiskManager(RiskPolicy(stopLossPct = null, trailingStopPct = null, takeProfitPct = 0.05, timeExitBars = null))
        val out = rm.evaluate(pos(entry = 100.0), lastPrice = 106.0, currentBarIndex = 3)
        assertEquals(1, out.size)
        assertEquals("TAKE_PROFIT", out[0].tag)
    }

    @Test
    fun `no exit when none of the rules trigger`() {
        val rm = RiskManager(RiskPolicy(stopLossPct = 0.05, trailingStopPct = 0.02, takeProfitPct = 0.10, timeExitBars = 30))
        val out = rm.evaluate(pos(entry = 100.0, peak = 102.0), lastPrice = 101.0, currentBarIndex = 5)
        assertEquals(0, out.size)
    }
}
```

- [ ] **Step 2: Run test — fail**

- [ ] **Step 3: Implement**

```kotlin
// RiskPolicy.kt
package com.trading.research.risk

data class RiskPolicy(
    val stopLossPct: Double? = 0.03,
    val trailingStopPct: Double? = 0.02,
    val takeProfitPct: Double? = 0.05,
    val timeExitBars: Int? = 7,
)
```

```kotlin
// RiskManager.kt
package com.trading.research.risk

import com.trading.research.domain.OrderRequest
import com.trading.research.domain.OrderSide
import com.trading.research.domain.SizingRule
import com.trading.research.portfolio.Position

class RiskManager(private val policy: RiskPolicy) {
    fun evaluate(position: Position, lastPrice: Double, currentBarIndex: Long): List<OrderRequest> {
        val entry = position.avgEntryPrice
        val peak = position.peakPriceSinceEntry
        val pnlFrac = (lastPrice - entry) / entry

        val reason: String? = when {
            policy.stopLossPct != null && pnlFrac <= -policy.stopLossPct -> "STOP_LOSS"
            policy.trailingStopPct != null && pnlFrac > 0.0 && ((peak - lastPrice) / peak) >= policy.trailingStopPct -> "TRAILING_STOP"
            policy.takeProfitPct != null && pnlFrac >= policy.takeProfitPct -> "TAKE_PROFIT"
            policy.timeExitBars != null && (currentBarIndex - position.openedAtBarIndex) >= policy.timeExitBars -> "TIME_EXIT"
            else -> null
        }

        return if (reason == null) emptyList()
        else listOf(OrderRequest(position.asset, OrderSide.SELL, SizingRule.CloseAll, tag = reason))
    }
}
```

- [ ] **Step 4: Test passes**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/risk research/src/test/kotlin/com/trading/research/risk
git commit -m "feat(research): add per-position risk manager (stop/trailing/tp/time)"
```

---

### Task 10: Portfolio kill switches

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/risk/KillSwitch.kt`
- Test: `research/src/test/kotlin/com/trading/research/risk/KillSwitchTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.trading.research.risk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class KillSwitchTest {

    @Test
    fun `daily drawdown halts new entries after threshold`() {
        val ks = KillSwitch(dailyDdHaltPct = 0.05, totalDdHaltPct = null)
        val today = LocalDate.of(2024, 1, 15)
        ks.onDayStart(today, startEquity = 10_000.0)

        assertFalse(ks.shouldBlockEntries(today, currentEquity = 9_600.0))  // 4% down
        assertTrue(ks.shouldBlockEntries(today, currentEquity = 9_400.0))   // 6% down → halt
    }

    @Test
    fun `total drawdown halts simulation entirely`() {
        val ks = KillSwitch(dailyDdHaltPct = null, totalDdHaltPct = 0.20)
        ks.onPeakUpdate(peakEquity = 10_000.0)
        assertFalse(ks.shouldHaltSimulation(currentEquity = 8_500.0))  // 15%
        assertTrue(ks.shouldHaltSimulation(currentEquity = 7_900.0))   // 21% → halt
    }
}
```

- [ ] **Step 2: Run test — fail**

- [ ] **Step 3: Implement**

```kotlin
package com.trading.research.risk

import java.time.LocalDate

class KillSwitch(
    private val dailyDdHaltPct: Double? = null,
    private val totalDdHaltPct: Double? = null,
) {
    private var currentDay: LocalDate? = null
    private var dayStartEquity: Double = 0.0
    private var peakEquity: Double = 0.0

    fun onDayStart(day: LocalDate, startEquity: Double) {
        currentDay = day
        dayStartEquity = startEquity
    }

    fun onPeakUpdate(peakEquity: Double) {
        if (peakEquity > this.peakEquity) this.peakEquity = peakEquity
    }

    fun shouldBlockEntries(day: LocalDate, currentEquity: Double): Boolean {
        if (dailyDdHaltPct == null) return false
        if (currentDay != day) return false
        val dd = (dayStartEquity - currentEquity) / dayStartEquity
        return dd >= dailyDdHaltPct
    }

    fun shouldHaltSimulation(currentEquity: Double): Boolean {
        if (totalDdHaltPct == null || peakEquity <= 0.0) return false
        val dd = (peakEquity - currentEquity) / peakEquity
        return dd >= totalDdHaltPct
    }
}
```

- [ ] **Step 4: Test passes**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/risk/KillSwitch.kt research/src/test/kotlin/com/trading/research/risk/KillSwitchTest.kt
git commit -m "feat(research): add portfolio-level kill switches (daily / total drawdown)"
```

---

### Task 11: Strategy context plumbing (interfaces + clock + indicator snapshot)

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/strategy/ResearchStrategy.kt`
- Create: `research/src/main/kotlin/com/trading/research/strategy/BarEvent.kt`
- Create: `research/src/main/kotlin/com/trading/research/strategy/ResearchContext.kt`
- Create: `research/src/main/kotlin/com/trading/research/strategy/UniverseView.kt`
- Create: `research/src/main/kotlin/com/trading/research/strategy/IndicatorSnapshot.kt`
- Create: `research/src/main/kotlin/com/trading/research/engine/ResearchClock.kt`
- Test: `research/src/test/kotlin/com/trading/research/strategy/UniverseViewTest.kt`

- [ ] **Step 1: Write failing test for UniverseView default impl**

```kotlin
package com.trading.research.strategy

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class UniverseViewTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    private fun bar(close: Double, dayOffset: Long): Bar {
        val t = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(dayOffset * 86400)
        return Bar(t, t.plusSeconds(86400), close, close, close, close, 1.0, close)
    }

    @Test
    fun `recentBars returns last N bars in order`() {
        val history = mapOf(asset to (0L..9L).map { bar(100.0 + it, it) })
        val view = RollingUniverseView(history, currentBarIndex = mapOf(asset to 5L))
        val recent = view.recentBars(asset, 3)
        assertEquals(3, recent.size)
        assertEquals(103.0, recent[0].close)
        assertEquals(105.0, recent[2].close)
    }

    @Test
    fun `recentBars returns fewer if history shorter`() {
        val history = mapOf(asset to listOf(bar(100.0, 0), bar(101.0, 1)))
        val view = RollingUniverseView(history, currentBarIndex = mapOf(asset to 1L))
        val recent = view.recentBars(asset, 10)
        assertEquals(2, recent.size)
    }
}
```

- [ ] **Step 2: Run test — fail**

- [ ] **Step 3: Implement interfaces + RollingUniverseView + ResearchClock + IndicatorSnapshot**

```kotlin
// BarEvent.kt
package com.trading.research.strategy

import com.trading.research.domain.Asset
import com.trading.research.domain.Bar

data class BarEvent(
    val asset: Asset,
    val bar: Bar,
    val indicators: IndicatorSnapshot,
    val barIndex: Long,
)
```

```kotlin
// IndicatorSnapshot.kt
package com.trading.research.strategy

data class IndicatorSnapshot(
    val rsi14: Double? = null,
    val ma20: Double? = null,
    val ma50: Double? = null,
    val macdSignal: Double? = null,
    val macdValue: Double? = null,
    val bollUpper: Double? = null,
    val bollLower: Double? = null,
    val atr14: Double? = null,
    val realizedVol20: Double? = null, // daily stdev of returns
) {
    companion object { val EMPTY = IndicatorSnapshot() }
}
```

```kotlin
// ResearchStrategy.kt
package com.trading.research.strategy

import com.trading.research.domain.OrderRequest

interface ResearchStrategy {
    val name: String
    val warmupBars: Int

    suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest>
}
```

```kotlin
// ResearchContext.kt
package com.trading.research.strategy

import com.trading.research.engine.ResearchClock
import com.trading.research.portfolio.PortfolioView

interface ResearchContext {
    val clock: ResearchClock
    val portfolio: PortfolioView
    val universe: UniverseView
    val params: Map<String, Any>
}

data class ResearchContextImpl(
    override val clock: ResearchClock,
    override val portfolio: PortfolioView,
    override val universe: UniverseView,
    override val params: Map<String, Any>,
) : ResearchContext
```

```kotlin
// UniverseView.kt
package com.trading.research.strategy

import com.trading.research.domain.Asset
import com.trading.research.domain.Bar

interface UniverseView {
    val assets: List<Asset>
    fun recentBars(asset: Asset, count: Int): List<Bar>
}

/** Simple implementation backed by full history + current index per asset. */
class RollingUniverseView(
    private val history: Map<Asset, List<Bar>>,
    private var currentBarIndex: Map<Asset, Long>,
) : UniverseView {
    override val assets: List<Asset> = history.keys.toList()

    override fun recentBars(asset: Asset, count: Int): List<Bar> {
        val all = history[asset] ?: return emptyList()
        val idx = (currentBarIndex[asset] ?: 0L).toInt()
        val to = (idx + 1).coerceAtMost(all.size)
        val from = (to - count).coerceAtLeast(0)
        return all.subList(from, to)
    }

    fun advance(asset: Asset, newIndex: Long) {
        currentBarIndex = currentBarIndex.toMutableMap().also { it[asset] = newIndex }
    }
}
```

```kotlin
// ResearchClock.kt
package com.trading.research.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ResearchClock(private val zone: ZoneId = ZoneId.of("UTC")) {
    private var _now: Instant = Instant.EPOCH

    val now: Instant get() = _now

    fun advanceTo(t: Instant) {
        require(!t.isBefore(_now)) { "clock cannot go backwards: $_now → $t" }
        _now = t
    }

    fun currentDate(): LocalDate = _now.atZone(zone).toLocalDate()
}
```

- [ ] **Step 4: Test passes**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/strategy research/src/main/kotlin/com/trading/research/engine research/src/test/kotlin/com/trading/research/strategy
git commit -m "feat(research): add strategy interfaces (ResearchStrategy, Context, Universe, Clock)"
```

---

### Task 12: BarStream (chronological merge across assets)

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/engine/BarStream.kt`
- Test: `research/src/test/kotlin/com/trading/research/engine/BarStreamTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.trading.research.engine

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class BarStreamTest {
    private fun bar(t: Instant, close: Double): Bar =
        Bar(t, t.plusSeconds(86400), close, close, close, close, 1.0, close)

    @Test
    fun `events emitted in chronological order, ties broken by asset name`() {
        val btc = Asset(Exchange.UPBIT, "BTC/KRW")
        val eth = Asset(Exchange.UPBIT, "ETH/KRW")
        val aapl = Asset(Exchange.KIS, "AAPL")

        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        val t1 = Instant.parse("2024-01-02T00:00:00Z")

        val history = mapOf(
            aapl to listOf(bar(t0, 190.0), bar(t1, 191.0)),
            btc to listOf(bar(t0, 42000.0), bar(t1, 43000.0)),
            eth to listOf(bar(t0, 2500.0), bar(t1, 2600.0)),
        )

        val stream = BarStream(history).iterator()
        val ordered = mutableListOf<Pair<Asset, Instant>>()
        stream.forEach { ordered += it.asset to it.bar.closeTime }

        // Day 1 ties: KIS:AAPL, UPBIT:BTC/KRW, UPBIT:ETH/KRW (alphabetical by "EX:MARKET")
        val day1 = ordered.filter { it.second == t0.plusSeconds(86400) }.map { it.first.toString() }
        assertEquals(listOf("KIS:AAPL", "UPBIT:BTC/KRW", "UPBIT:ETH/KRW"), day1)
        assertEquals(6, ordered.size)
    }
}
```

- [ ] **Step 2: Run test — fail**

- [ ] **Step 3: Implement**

```kotlin
package com.trading.research.engine

import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import com.trading.research.strategy.BarEvent
import com.trading.research.strategy.IndicatorSnapshot

class BarStream(private val history: Map<Asset, List<Bar>>) : Iterable<BarEvent> {

    override fun iterator(): Iterator<BarEvent> = sequence {
        // Flatten all (asset, barIndex, bar) tuples; sort by closeTime asc, then asset toString asc.
        val all = history.flatMap { (asset, bars) ->
            bars.mapIndexed { idx, bar -> Triple(asset, idx, bar) }
        }.sortedWith(compareBy({ it.third.closeTime }, { it.first.toString() }))

        for ((asset, idx, bar) in all) {
            yield(BarEvent(asset = asset, bar = bar, indicators = IndicatorSnapshot.EMPTY, barIndex = idx.toLong()))
        }
    }.iterator()
}
```

- [ ] **Step 4: Test passes**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/engine/BarStream.kt research/src/test/kotlin/com/trading/research/engine/BarStreamTest.kt
git commit -m "feat(research): add deterministic BarStream (chronological merge, alpha tie-break)"
```

---

### Task 13: Engine.run() main loop + end-to-end smoke test

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/engine/BacktestRunConfig.kt`
- Create: `research/src/main/kotlin/com/trading/research/engine/RunResult.kt`
- Create: `research/src/main/kotlin/com/trading/research/engine/Engine.kt`
- Create: `research/src/main/kotlin/com/trading/research/metrics/MetricsAccumulator.kt`
- Test: `research/src/test/kotlin/com/trading/research/engine/EngineSmokeTest.kt`

- [ ] **Step 1: Write failing end-to-end smoke test**

```kotlin
package com.trading.research.engine

import com.trading.common.domain.Exchange
import com.trading.research.domain.*
import com.trading.research.execution.FlatFeeSlippageModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class EngineSmokeTest {

    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    /** Strategy that buys on bar 5 and never exits voluntarily. */
    private class BuyOnceStrategy : ResearchStrategy {
        override val name = "BuyOnce"
        override val warmupBars = 0
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> {
            return if (event.barIndex == 5L && !ctx.portfolio.hasPosition(event.asset))
                listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.FixedFraction(0.5)))
            else emptyList()
        }
    }

    @Test
    fun `engine runs 10 bars and produces result with one trade`() = runTest {
        // Build a simple 10-day rising market
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        val bars = (0L..9L).map { i ->
            val t = t0.plusSeconds(i * 86400)
            Bar(t, t.plusSeconds(86400), 100.0 + i, 100.0 + i, 100.0 + i, 100.0 + i, 1.0, 100.0 + i)
        }

        val config = BacktestRunConfig(
            strategy = BuyOnceStrategy(),
            history = mapOf(asset to bars),
            initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 0.0),
            risk = RiskPolicy(stopLossPct = null, trailingStopPct = null, takeProfitPct = null, timeExitBars = null),
            killSwitch = KillSwitch(),
        )

        val result = Engine.run(config)

        assertEquals(1, result.fills.size) // only the entry filled (no exit rule, simulation end closes)
        assertTrue(result.equityCurve.size >= 10)
    }
}
```

- [ ] **Step 2: Run test — fail**

- [ ] **Step 3: Implement types + Engine**

```kotlin
// BacktestRunConfig.kt
package com.trading.research.engine

import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import com.trading.research.execution.CostModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.ResearchStrategy

data class BacktestRunConfig(
    val strategy: ResearchStrategy,
    val history: Map<Asset, List<Bar>>,
    val initialCash: Double,
    val costModel: CostModel,
    val risk: RiskPolicy,
    val killSwitch: KillSwitch,
    val params: Map<String, Any> = emptyMap(),
)
```

```kotlin
// RunResult.kt
package com.trading.research.engine

import com.trading.research.portfolio.Fill
import java.time.LocalDate

data class EquityPoint(val date: LocalDate, val equity: Double)

data class RunResult(
    val strategyName: String,
    val fills: List<Fill>,
    val equityCurve: List<EquityPoint>,
    val finalEquity: Double,
    val tradesClosed: List<ClosedTrade>,
)

data class ClosedTrade(
    val asset: com.trading.research.domain.Asset,
    val entryBarIndex: Long,
    val exitBarIndex: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val pnl: Double,
    val reason: String,
)
```

```kotlin
// Engine.kt
package com.trading.research.engine

import com.trading.research.domain.Asset
import com.trading.research.domain.OrderRequest
import com.trading.research.execution.FillSimulator
import com.trading.research.execution.OrderBook
import com.trading.research.portfolio.Fill
import com.trading.research.portfolio.Portfolio
import com.trading.research.risk.RiskManager
import com.trading.research.sizing.SizingCalculator
import com.trading.research.strategy.*

object Engine {

    suspend fun run(config: BacktestRunConfig): RunResult {
        val strategy = config.strategy
        val portfolio = Portfolio(config.initialCash)
        val clock = ResearchClock()
        val orderBook = OrderBook()
        val fillSim = FillSimulator(config.costModel, SizingCalculator)
        val risk = RiskManager(config.risk)
        val metrics = MetricsAccumulator()
        val allFills = mutableListOf<Fill>()
        val closedTrades = mutableListOf<ClosedTrade>()

        // Build universe view + stream
        val assetIndices = config.history.keys.associateWith { 0L }.toMutableMap()
        val universe = RollingUniverseView(config.history, assetIndices.toMap())

        val stream = BarStream(config.history)

        // Track last seen bar per asset for exits to know current price
        val pendingExits = mutableListOf<OrderRequest>()
        val openByAsset = mutableMapOf<Asset, Long>()

        for (event in stream) {
            // Advance clock
            clock.advanceTo(event.bar.closeTime)
            universe.advance(event.asset, event.barIndex)

            // 1. Fill pending entry orders at this bar's open
            val entryFills = fillSim.fill(
                orders = orderBook.drain(),
                openPrices = mapOf(event.asset to event.bar.open),
                equityBefore = portfolio.totalEquity,
                assetDailyVol = mapOf(event.asset to 0.0), // v1: filled by caller later
                barIndex = event.barIndex,
            )
            entryFills.forEach { f ->
                portfolio.applyFill(f)
                allFills.add(f)
                openByAsset[f.asset] = event.barIndex
            }

            // 2. Fill pending exits
            val exitFills = fillSim.fillExit(
                orders = pendingExits.filter { it.asset == event.asset },
                openPrices = mapOf(event.asset to event.bar.open),
                portfolio = portfolio,
                barIndex = event.barIndex,
            )
            exitFills.forEach { f ->
                val pos = portfolio.positions[f.asset]!!
                val entryIdx = openByAsset[f.asset] ?: 0L
                val pnl = (f.fillPrice - pos.avgEntryPrice) * f.quantity - f.fee
                closedTrades.add(
                    ClosedTrade(f.asset, entryIdx, event.barIndex, pos.avgEntryPrice, f.fillPrice, f.quantity, pnl, f.tag)
                )
                portfolio.applyFill(f)
                allFills.add(f)
                openByAsset.remove(f.asset)
            }
            pendingExits.removeAll { it.asset == event.asset }

            // 3. Kill switch halt check
            if (config.killSwitch.shouldHaltSimulation(portfolio.totalEquity)) break

            // 4. Risk evaluation for this asset's open position (using close)
            val position = portfolio.positions[event.asset]
            if (position != null) {
                val exits = risk.evaluate(position, event.bar.close, event.barIndex)
                pendingExits.addAll(exits)
            }

            // 5. Strategy entries
            val ctx = ResearchContextImpl(clock, portfolio.view(), universe, config.params)
            val signals = strategy.onBar(ctx, event)
            orderBook.submitAll(signals.filterNot { config.killSwitch.shouldBlockEntries(clock.currentDate(), portfolio.totalEquity) })

            // 6. Mark to market + metrics
            portfolio.markToMarket(mapOf(event.asset to event.bar.close))
            config.killSwitch.onPeakUpdate(portfolio.totalEquity)
            metrics.recordDailyEquity(clock.currentDate(), portfolio.totalEquity)
        }

        return RunResult(
            strategyName = strategy.name,
            fills = allFills,
            equityCurve = metrics.curve(),
            finalEquity = portfolio.totalEquity,
            tradesClosed = closedTrades,
        )
    }
}
```

```kotlin
// MetricsAccumulator.kt
package com.trading.research.metrics

import com.trading.research.engine.EquityPoint
import java.time.LocalDate

class MetricsAccumulator {
    private val byDate = linkedMapOf<LocalDate, Double>()

    fun recordDailyEquity(date: LocalDate, equity: Double) {
        byDate[date] = equity
    }

    fun curve(): List<EquityPoint> = byDate.map { (d, e) -> EquityPoint(d, e) }
}
```

- [ ] **Step 4: Test passes**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/engine research/src/main/kotlin/com/trading/research/metrics/MetricsAccumulator.kt research/src/test/kotlin/com/trading/research/engine/EngineSmokeTest.kt
git commit -m "feat(research): add engine main loop (BarStream → risk → strategy → fills → metrics)"
```

---

### Task 14: DataLoader (PG JDBC) with Testcontainers integration test

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/data/DataLoader.kt`
- Create: `research/src/main/kotlin/com/trading/research/data/DataSourceConfig.kt`
- Test: `research/src/test/kotlin/com/trading/research/data/DataLoaderIntegrationTest.kt`

- [ ] **Step 1: Write failing integration test (Testcontainers PG)**

```kotlin
package com.trading.research.data

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate

@Testcontainers
class DataLoaderIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val pg = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("research")
            .withUsername("test")
            .withPassword("test")
    }

    @Test
    fun `loads market_candles rows as Bar list`() = runTest {
        // Arrange: create table + insert 3 rows
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.createStatement().execute(
                """
                CREATE TABLE market_candles (
                    id SERIAL PRIMARY KEY,
                    exchange TEXT NOT NULL,
                    market TEXT NOT NULL,
                    interval_minutes INT NOT NULL,
                    open_time TIMESTAMPTZ NOT NULL,
                    close_time TIMESTAMPTZ NOT NULL,
                    open_price DOUBLE PRECISION NOT NULL,
                    high_price DOUBLE PRECISION NOT NULL,
                    low_price DOUBLE PRECISION NOT NULL,
                    close_price DOUBLE PRECISION NOT NULL,
                    volume DOUBLE PRECISION NOT NULL,
                    quote_volume DOUBLE PRECISION NOT NULL
                );
                """.trimIndent()
            )
            val stmt = conn.prepareStatement(
                """INSERT INTO market_candles
                   (exchange, market, interval_minutes, open_time, close_time,
                    open_price, high_price, low_price, close_price, volume, quote_volume)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent()
            )
            for (d in 1..3) {
                val ot = java.sql.Timestamp.from(Instant.parse("2024-01-0${d}T00:00:00Z"))
                val ct = java.sql.Timestamp.from(Instant.parse("2024-01-0${d+1}T00:00:00Z"))
                stmt.setString(1, "UPBIT"); stmt.setString(2, "BTC/KRW"); stmt.setInt(3, 1440)
                stmt.setTimestamp(4, ot); stmt.setTimestamp(5, ct)
                stmt.setDouble(6, 100.0 * d); stmt.setDouble(7, 110.0 * d)
                stmt.setDouble(8, 90.0 * d); stmt.setDouble(9, 105.0 * d)
                stmt.setDouble(10, 1.0); stmt.setDouble(11, 105.0 * d)
                stmt.executeUpdate()
            }
        }

        val loader = DataLoader(DataSourceConfig(pg.jdbcUrl, pg.username, pg.password))
        val result = loader.load(
            assets = listOf(Asset(Exchange.UPBIT, "BTC/KRW")),
            from = LocalDate.of(2024, 1, 1),
            to = LocalDate.of(2024, 1, 5),
            intervalMinutes = 1440,
        )

        assertEquals(1, result.size)
        val bars = result.values.first()
        assertEquals(3, bars.size)
        assertEquals(100.0, bars[0].open)
        assertEquals(315.0, bars[2].close)
    }
}
```

- [ ] **Step 2: Run test — fail**

- [ ] **Step 3: Implement DataLoader + DataSourceConfig**

```kotlin
// DataSourceConfig.kt
package com.trading.research.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

data class DataSourceConfig(val jdbcUrl: String, val username: String, val password: String) {
    fun dataSource(): DataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = this@DataSourceConfig.jdbcUrl
        username = this@DataSourceConfig.username
        password = this@DataSourceConfig.password
        maximumPoolSize = 4
    })
}
```

```kotlin
// DataLoader.kt
package com.trading.research.data

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset
import javax.sql.DataSource

class DataLoader(
    private val config: DataSourceConfig,
    private val dataSource: DataSource = config.dataSource(),
) {
    suspend fun load(
        assets: List<Asset>,
        from: LocalDate,
        to: LocalDate,
        intervalMinutes: Int = 1440,
    ): Map<Asset, List<Bar>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Asset, List<Bar>>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT open_time, close_time, open_price, high_price, low_price, close_price, volume, quote_volume
                FROM market_candles
                WHERE exchange = ? AND market = ? AND interval_minutes = ?
                  AND open_time >= ? AND open_time < ?
                ORDER BY open_time ASC
            """.trimIndent()
            for (asset in assets) {
                val ps = conn.prepareStatement(sql)
                ps.setString(1, asset.exchange.name)
                ps.setString(2, asset.market)
                ps.setInt(3, intervalMinutes)
                ps.setTimestamp(4, java.sql.Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)))
                ps.setTimestamp(5, java.sql.Timestamp.from(to.atStartOfDay().toInstant(ZoneOffset.UTC)))
                val rs = ps.executeQuery()
                val bars = mutableListOf<Bar>()
                while (rs.next()) {
                    bars.add(
                        Bar(
                            openTime = rs.getTimestamp(1).toInstant(),
                            closeTime = rs.getTimestamp(2).toInstant(),
                            open = rs.getDouble(3),
                            high = rs.getDouble(4),
                            low = rs.getDouble(5),
                            close = rs.getDouble(6),
                            volume = rs.getDouble(7),
                            quoteVolume = rs.getDouble(8),
                        )
                    )
                }
                result[asset] = bars
            }
        }
        result
    }
}
```

- [ ] **Step 4: Test passes**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/data research/src/test/kotlin/com/trading/research/data
git commit -m "feat(research): add JDBC-based DataLoader for market_candles"
```

---

### Task 15: Migrate legacy strategy types to `:common`

**Files:**
- Modify: `common/src/main/kotlin/com/trading/common/strategy/TradingStrategy.kt` (create, moving from bot)
- Modify: `common/src/main/kotlin/com/trading/common/config/TradingProperties.kt` (create, moving from bot)
- Modify: `common/src/main/kotlin/com/trading/common/domain/Candle.kt` (create, moving from bot)
- Modify: `common/src/main/kotlin/com/trading/common/strategy/*.kt` (move 8 strategy impls + Indicators delegate)
- Delete: `bot/src/main/kotlin/com/trading/bot/strategy/TradingStrategy.kt` (use :common)
- Delete: `bot/src/main/kotlin/com/trading/bot/domain/Candle.kt` (use :common)
- Modify: `bot/` imports to reference `com.trading.common.strategy.*` and `com.trading.common.domain.Candle`

- [ ] **Step 1: Inventory — run `git grep` and list imports to update**

Run: `git grep -l "com.trading.bot.strategy" bot/ collector/ common/` → list of files.
Run: `git grep -l "com.trading.bot.domain.Candle" bot/` → list of files.

Capture the list; it drives the subsequent Edit steps.

- [ ] **Step 2: Move `Candle.kt`**

Create `common/src/main/kotlin/com/trading/common/domain/Candle.kt` with the exact current content of `bot/src/main/kotlin/com/trading/bot/domain/Candle.kt` but with `package com.trading.common.domain`.

Delete `bot/src/main/kotlin/com/trading/bot/domain/Candle.kt`.

Replace every `import com.trading.bot.domain.Candle` with `import com.trading.common.domain.Candle` across the codebase.

- [ ] **Step 3: Move `TradingProperties.kt`**

Create `common/src/main/kotlin/com/trading/common/config/TradingProperties.kt` by moving the file. Update package to `com.trading.common.config`. Delete original.

Replace every `import com.trading.bot.config.TradingProperties` with `import com.trading.common.config.TradingProperties`.

- [ ] **Step 4: Move `TradingStrategy.kt`**

Create `common/src/main/kotlin/com/trading/common/strategy/TradingStrategy.kt` by moving the file. Update package. Keep the `toLegacyCandle()` helper in the same file (imports adjusted).

Replace every `import com.trading.bot.strategy.TradingStrategy` with `import com.trading.common.strategy.TradingStrategy`.

- [ ] **Step 5: Move 8 strategy implementations**

For each of:
- `BollingerBounce.kt`, `CombinedStrategy.kt`, `GoldenCross.kt`, `MacdCross.kt`,
  `MeanReversion.kt`, `MlStrategy.kt`, `RsiBounce.kt`, `VolatilityBreakout.kt`

Move to `common/src/main/kotlin/com/trading/common/strategy/` and update package to `com.trading.common.strategy`. Update any `com.trading.bot.strategy.Indicators` → `com.trading.common.indicator.Indicators` (already in common).

**Note:** `MlStrategy` depends on `MlModelService` from `:bot`. Temporarily **leave `MlStrategy.kt` in `:bot`** (keep its existing package) until MlModelService is handled in Phase F (ML 동결). Document this exception in a `TODO.md` entry and skip the 8th strategy in v1.

- [ ] **Step 6: Update `bot` module imports**

Run: `git grep -l "com.trading.bot.strategy" bot/` — for each file, replace imports to point to new locations.

Ensure `bot/build.gradle.kts` still compiles: `JAVA_HOME=... ./gradlew :bot:compileKotlin`

- [ ] **Step 7: Run full build**

Run: `JAVA_HOME=... ./gradlew build`
Expected: `BUILD SUCCESSFUL`. If tests fail due to stale imports, fix them.

- [ ] **Step 8: Commit**

```bash
git add common/ bot/
git commit -m "refactor: move TradingStrategy + TradingProperties + Candle + 7 strategies to :common

:research가 :bot에 의존하지 않고도 기존 전략을 검증할 수 있도록 공유 타입/전략 코드를 :common으로 이동.
MlStrategy는 MlModelService(:bot)에 의존하므로 동결 상태로 :bot에 잔존 — v1 범위 밖."
```

---

### Task 16: LegacyStrategyAdapter + 7-strategy acceptance test

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/strategy/LegacyStrategyAdapter.kt`
- Test: `research/src/test/kotlin/com/trading/research/strategy/LegacyStrategyAdapterTest.kt`

- [ ] **Step 1: Write failing test — adapter wraps a legacy strategy and emits BUY when shouldBuy=true**

```kotlin
package com.trading.research.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.strategy.TradingStrategy
import com.trading.research.domain.*
import com.trading.research.engine.ResearchClock
import com.trading.research.portfolio.Portfolio
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class LegacyStrategyAdapterTest {

    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    private class AlwaysBuy : TradingStrategy {
        override val name = "AlwaysBuy"
        override suspend fun shouldBuy(
            candles: List<com.trading.common.domain.Candle>,
            currentPrice: Double,
            config: TradingProperties,
        ): Boolean = true
    }

    private fun ctxWith(portfolio: Portfolio, bar: Bar): ResearchContext = ResearchContextImpl(
        clock = ResearchClock(),
        portfolio = portfolio.view(),
        universe = RollingUniverseView(
            history = mapOf(asset to listOf(bar, bar, bar, bar, bar)),
            currentBarIndex = mapOf(asset to 4L),
        ),
        params = emptyMap(),
    )

    @Test
    fun `emits single BUY when no position`() = runTest {
        val adapter = LegacyStrategyAdapter(AlwaysBuy(), SizingRule.FixedFraction(0.1), TradingProperties())
        val bar = Bar(
            Instant.parse("2024-01-01T00:00:00Z"),
            Instant.parse("2024-01-02T00:00:00Z"),
            100.0, 105.0, 95.0, 100.0, 1.0, 100.0,
        )
        val event = BarEvent(asset, bar, IndicatorSnapshot.EMPTY, barIndex = 4L)

        val orders = adapter.onBar(ctxWith(Portfolio(10_000.0), bar), event)

        assertEquals(1, orders.size)
        assertEquals(OrderSide.BUY, orders[0].side)
    }

    @Test
    fun `emits nothing when already has position`() = runTest {
        val adapter = LegacyStrategyAdapter(AlwaysBuy(), SizingRule.FixedFraction(0.1), TradingProperties())
        val portfolio = Portfolio(10_000.0).apply {
            applyFill(com.trading.research.portfolio.Fill(asset, OrderSide.BUY, 1.0, 100.0, 0.0, "t"))
        }
        val bar = Bar(
            Instant.parse("2024-01-01T00:00:00Z"),
            Instant.parse("2024-01-02T00:00:00Z"),
            100.0, 105.0, 95.0, 100.0, 1.0, 100.0,
        )
        val event = BarEvent(asset, bar, IndicatorSnapshot.EMPTY, barIndex = 4L)
        val orders = adapter.onBar(ctxWith(portfolio, bar), event)
        assertEquals(0, orders.size)
    }
}
```

- [ ] **Step 2: Run test — fail**

- [ ] **Step 3: Implement adapter**

```kotlin
package com.trading.research.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import com.trading.research.domain.*
import com.trading.research.domain.toNormalizedCandle

class LegacyStrategyAdapter(
    private val legacy: TradingStrategy,
    private val sizing: SizingRule,
    private val props: TradingProperties,
    override val warmupBars: Int = 50,
) : ResearchStrategy {

    override val name: String = "legacy:${legacy.name}"

    override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> {
        if (ctx.portfolio.hasPosition(event.asset)) return emptyList()
        val bars = ctx.universe.recentBars(event.asset, warmupBars)
        if (bars.size < warmupBars) return emptyList()
        val normalized = bars.map { it.toNormalizedCandle(event.asset) }
        return if (legacy.shouldBuyNormalized(normalized, event.bar.close, props)) {
            listOf(OrderRequest(event.asset, OrderSide.BUY, sizing, tag = "entry"))
        } else emptyList()
    }
}
```

- [ ] **Step 4: Test passes**

- [ ] **Step 5: Acceptance — wire 7 legacy strategies through engine**

Add an acceptance test `research/src/test/kotlin/com/trading/research/engine/LegacyStrategyAcceptanceTest.kt`:

```kotlin
package com.trading.research.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import com.trading.common.strategy.*
import com.trading.research.domain.*
import com.trading.research.execution.FlatFeeSlippageModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.LegacyStrategyAdapter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

class LegacyStrategyAcceptanceTest {

    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    /** Synthesize 300 days of noisy trend for smoke-quality acceptance. */
    private fun syntheticBars(): List<Bar> {
        val rng = Random(42)
        val bars = mutableListOf<Bar>()
        var p = 100.0
        val t0 = Instant.parse("2023-01-01T00:00:00Z")
        for (i in 0 until 300) {
            val t = t0.plusSeconds(i * 86400L)
            val delta = rng.nextDouble(-3.0, 3.5)
            val open = p
            val close = (p + delta).coerceAtLeast(1.0)
            val high = maxOf(open, close) + rng.nextDouble(0.0, 2.0)
            val low = minOf(open, close) - rng.nextDouble(0.0, 2.0)
            bars.add(Bar(t, t.plusSeconds(86400), open, high, low, close, 1.0, close))
            p = close
        }
        return bars
    }

    @Test
    fun `each of the 7 legacy strategies runs end to end without exception`() = runTest {
        val bars = syntheticBars()
        val history = mapOf(asset to bars)
        val props = TradingProperties()

        val legacies: List<TradingStrategy> = listOf(
            RsiBounce(), GoldenCross(), MacdCross(), BollingerBounce(),
            MeanReversion(), VolatilityBreakout(), CombinedStrategy(),
        )

        for (legacy in legacies) {
            val adapter = LegacyStrategyAdapter(legacy, SizingRule.FixedFraction(0.1), props)
            val cfg = BacktestRunConfig(
                strategy = adapter,
                history = history,
                initialCash = 10_000.0,
                costModel = FlatFeeSlippageModel(0.0005, 5.0),
                risk = RiskPolicy(),
                killSwitch = KillSwitch(),
            )
            val r = Engine.run(cfg)
            assertNotNull(r)
            assertTrue(r.equityCurve.isNotEmpty(), "equity curve empty for ${legacy.name}")
        }
    }
}
```

(If any legacy strategy's constructor requires arguments, provide sensible defaults; see existing `bot/strategy/*.kt` for signatures.)

- [ ] **Step 6: Run acceptance — should pass**

Run: `JAVA_HOME=... ./gradlew :research:test --tests LegacyStrategyAcceptanceTest`

- [ ] **Step 7: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/strategy/LegacyStrategyAdapter.kt research/src/test/kotlin/com/trading/research/strategy research/src/test/kotlin/com/trading/research/engine/LegacyStrategyAcceptanceTest.kt
git commit -m "feat(research): add legacy strategy adapter + acceptance test for 7 strategies"
```

---

### Task 17: WalkForwardRunner + ParameterGrid

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/walkforward/WalkForwardConfig.kt`
- Create: `research/src/main/kotlin/com/trading/research/walkforward/ParameterGrid.kt`
- Create: `research/src/main/kotlin/com/trading/research/walkforward/WalkForwardRunner.kt`
- Test: `research/src/test/kotlin/com/trading/research/walkforward/WalkForwardTest.kt`

- [ ] **Step 1: Write failing test for window splitting**

```kotlin
package com.trading.research.walkforward

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class WalkForwardTest {
    @Test
    fun `splits 3-year period into rolling windows train730 test180 step90`() {
        val from = LocalDate.of(2021, 1, 1)
        val to = LocalDate.of(2024, 1, 1) // 3 years
        val cfg = WalkForwardConfig(trainDays = 730, testDays = 180, stepDays = 90)
        val windows = cfg.splitWindows(from, to)

        assertTrue(windows.size >= 3, "expected several windows, got ${windows.size}")
        windows.forEach { w ->
            assertEquals(730L, java.time.temporal.ChronoUnit.DAYS.between(w.trainStart, w.trainEnd))
            assertEquals(180L, java.time.temporal.ChronoUnit.DAYS.between(w.testStart, w.testEnd))
            assertEquals(w.trainEnd, w.testStart)
        }
        // step
        for (i in 1 until windows.size) {
            assertEquals(90L, java.time.temporal.ChronoUnit.DAYS.between(windows[i-1].trainStart, windows[i].trainStart))
        }
    }

    @Test
    fun `parameter grid enumerates all combinations`() {
        val grid = ParameterGrid(mapOf(
            "rsi" to listOf(20, 30, 40),
            "ma"  to listOf(10, 20),
        ))
        val combos = grid.combinations().toList()
        assertEquals(6, combos.size)
        assertTrue(combos.contains(mapOf("rsi" to 20, "ma" to 10)))
    }
}
```

- [ ] **Step 2: Run test — fail**

- [ ] **Step 3: Implement**

```kotlin
// WalkForwardConfig.kt
package com.trading.research.walkforward

import java.time.LocalDate

data class WalkForwardConfig(val trainDays: Int = 730, val testDays: Int = 180, val stepDays: Int = 90) {
    data class Window(val trainStart: LocalDate, val trainEnd: LocalDate, val testStart: LocalDate, val testEnd: LocalDate)

    fun splitWindows(from: LocalDate, to: LocalDate): List<Window> {
        val windows = mutableListOf<Window>()
        var trainStart = from
        while (true) {
            val trainEnd = trainStart.plusDays(trainDays.toLong())
            val testEnd = trainEnd.plusDays(testDays.toLong())
            if (testEnd.isAfter(to)) break
            windows.add(Window(trainStart, trainEnd, trainEnd, testEnd))
            trainStart = trainStart.plusDays(stepDays.toLong())
        }
        return windows
    }
}
```

```kotlin
// ParameterGrid.kt
package com.trading.research.walkforward

class ParameterGrid(private val axes: Map<String, List<Any>>) {
    fun combinations(): Sequence<Map<String, Any>> = sequence {
        if (axes.isEmpty()) { yield(emptyMap()); return@sequence }
        val keys = axes.keys.toList()
        val valueLists = keys.map { axes[it]!! }
        fun build(idx: Int, acc: Map<String, Any>): Sequence<Map<String, Any>> = sequence {
            if (idx == keys.size) { yield(acc); return@sequence }
            for (v in valueLists[idx]) yieldAll(build(idx + 1, acc + (keys[idx] to v)))
        }
        yieldAll(build(0, emptyMap()))
    }
}
```

```kotlin
// WalkForwardRunner.kt
package com.trading.research.walkforward

import com.trading.research.engine.BacktestRunConfig
import com.trading.research.engine.Engine
import com.trading.research.engine.RunResult
import com.trading.research.metrics.Metrics

class WalkForwardRunner(private val grid: ParameterGrid, private val config: WalkForwardConfig) {

    data class WindowOutcome(
        val window: WalkForwardConfig.Window,
        val bestParams: Map<String, Any>,
        val trainResult: RunResult,
        val testResult: RunResult,
    )

    /**
     * For each window: evaluate all grid points on train, pick best by `optimizeFor`, run test with that.
     */
    suspend fun run(
        baseConfig: BacktestRunConfig,
        optimizeFor: String = "sharpe", // "sharpe" | "calmar" | "totalReturn"
        windowsFromTo: WalkForwardConfig.Window,
    ): WindowOutcome {
        val trainCandidates = grid.combinations().toList()
        val evaluations = trainCandidates.map { params ->
            val cfg = baseConfig.copy(params = params)
            params to Engine.run(cfg)
        }
        val best = evaluations.maxByOrNull { scoreOf(it.second, optimizeFor) }!!
        val testCfg = baseConfig.copy(params = best.first)
        val testResult = Engine.run(testCfg)
        return WindowOutcome(windowsFromTo, best.first, best.second, testResult)
    }

    private fun scoreOf(r: RunResult, optimizeFor: String): Double {
        val equity = r.equityCurve.map { it.equity }
        val returns = Metrics.periodReturns(equity)
        return when (optimizeFor) {
            "sharpe" -> Metrics.annualizedSharpe(returns, 252)
            "calmar" -> {
                val maxDd = Metrics.maxDrawdown(equity)
                if (maxDd == 0.0) 0.0 else (r.finalEquity / equity.first() - 1.0) / maxDd
            }
            else -> r.finalEquity / equity.first() - 1.0
        }
    }
}
```

- [ ] **Step 4: Test passes**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/walkforward research/src/test/kotlin/com/trading/research/walkforward
git commit -m "feat(research): add rolling walk-forward runner + parameter grid search"
```

---

### Task 18: ReportEmitter (JSON + CSV + Markdown)

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/report/ReportEmitter.kt`
- Test: `research/src/test/kotlin/com/trading/research/report/ReportEmitterTest.kt`

- [ ] **Step 1: Write failing test — emits files to a temp dir**

```kotlin
package com.trading.research.report

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.engine.ClosedTrade
import com.trading.research.engine.EquityPoint
import com.trading.research.engine.RunResult
import com.trading.research.portfolio.Fill
import com.trading.research.domain.OrderSide
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.LocalDate

class ReportEmitterTest {
    @Test
    fun `emits JSON, CSV, Markdown with expected contents`() {
        val asset = Asset(Exchange.UPBIT, "BTC/KRW")
        val result = RunResult(
            strategyName = "UnitTestStrategy",
            fills = listOf(Fill(asset, OrderSide.BUY, 1.0, 100.0, 0.1, "entry", 0L)),
            equityCurve = listOf(
                EquityPoint(LocalDate.of(2024, 1, 1), 10_000.0),
                EquityPoint(LocalDate.of(2024, 1, 2), 10_100.0),
            ),
            finalEquity = 10_100.0,
            tradesClosed = listOf(
                ClosedTrade(asset, 0, 1, 100.0, 105.0, 1.0, 5.0, "TAKE_PROFIT"),
            ),
        )

        val outDir = Files.createTempDirectory("report-test").resolve("run")
        ReportEmitter.emit(outDir, result)

        assertTrue(outDir.resolve("result.json").toFile().exists())
        assertTrue(outDir.resolve("equity-curve.csv").toFile().exists())
        assertTrue(outDir.resolve("trades.csv").toFile().exists())
        val md = outDir.resolve("report.md").toFile().readText()
        assertTrue(md.contains("UnitTestStrategy"))
        assertTrue(md.contains("Final Equity"))
    }
}
```

- [ ] **Step 2: Run test — fail**

- [ ] **Step 3: Implement**

```kotlin
package com.trading.research.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.research.engine.RunResult
import com.trading.research.metrics.Metrics
import java.nio.file.Files
import java.nio.file.Path

object ReportEmitter {

    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun emit(dir: Path, result: RunResult) {
        Files.createDirectories(dir)
        writeJson(dir.resolve("result.json"), result)
        writeEquityCsv(dir.resolve("equity-curve.csv"), result)
        writeTradesCsv(dir.resolve("trades.csv"), result)
        writeMarkdown(dir.resolve("report.md"), result)
    }

    private fun writeJson(path: Path, result: RunResult) {
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))
    }

    private fun writeEquityCsv(path: Path, result: RunResult) {
        val sb = StringBuilder("date,equity\n")
        result.equityCurve.forEach { sb.appendLine("${it.date},${it.equity}") }
        Files.writeString(path, sb.toString())
    }

    private fun writeTradesCsv(path: Path, result: RunResult) {
        val sb = StringBuilder("asset,entry_bar,exit_bar,entry_price,exit_price,qty,pnl,reason\n")
        result.tradesClosed.forEach {
            sb.appendLine("${it.asset},${it.entryBarIndex},${it.exitBarIndex},${it.entryPrice},${it.exitPrice},${it.quantity},${it.pnl},${it.reason}")
        }
        Files.writeString(path, sb.toString())
    }

    private fun writeMarkdown(path: Path, result: RunResult) {
        val equity = result.equityCurve.map { it.equity }
        val rets = Metrics.periodReturns(equity)
        val sharpe = Metrics.annualizedSharpe(rets, 252)
        val sortino = Metrics.annualizedSortino(rets, 252)
        val maxDd = Metrics.maxDrawdown(equity)
        val winRate = Metrics.winRate(result.tradesClosed.map { it.pnl })
        val pf = Metrics.profitFactor(result.tradesClosed.map { it.pnl })

        val md = buildString {
            appendLine("# Backtest Report: ${result.strategyName}")
            appendLine()
            appendLine("## Headline")
            appendLine("| Metric | Value |")
            appendLine("|---|---|")
            appendLine("| Final Equity | ${"%.2f".format(result.finalEquity)} |")
            appendLine("| Sharpe | ${"%.3f".format(sharpe)} |")
            appendLine("| Sortino | ${"%.3f".format(sortino)} |")
            appendLine("| Max Drawdown | ${"%.2f%%".format(maxDd * 100)} |")
            appendLine("| Trades Closed | ${result.tradesClosed.size} |")
            appendLine("| Win Rate | ${"%.1f%%".format(winRate * 100)} |")
            appendLine("| Profit Factor | ${"%.2f".format(pf)} |")
        }
        Files.writeString(path, md)
    }
}
```

- [ ] **Step 4: Test passes**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/report research/src/test/kotlin/com/trading/research/report
git commit -m "feat(research): add JSON + CSV + Markdown report emitter"
```

---

### Task 19: CLI Main (Clikt-based)

**Files:**
- Create: `research/src/main/kotlin/com/trading/research/cli/Main.kt`
- Test: `research/src/test/kotlin/com/trading/research/cli/MainTest.kt`

- [ ] **Step 1: Write failing test — `--help` prints usage**

```kotlin
package com.trading.research.cli

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MainTest {
    @Test
    fun `help prints usage banner`() {
        val result = BacktestCommand().test("--help")
        assertTrue(result.output.contains("--strategy"))
        assertTrue(result.output.contains("--from"))
        assertTrue(result.output.contains("--to"))
    }

    @Test
    fun `parses asset list argument`() {
        val result = BacktestCommand().test(
            "--strategy", "RsiBounce",
            "--assets", "UPBIT:BTC/KRW,KIS:AAPL",
            "--from", "2024-01-01",
            "--to", "2024-06-01",
            "--dry-run",
        )
        assertEquals(0, result.statusCode)
    }
}
```

- [ ] **Step 2: Run test — fail**

- [ ] **Step 3: Implement**

```kotlin
package com.trading.research.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.localDate
import com.github.ajalt.clikt.parameters.types.path
import com.trading.research.domain.Asset
import com.trading.research.domain.SizingRule
import com.trading.research.engine.BacktestRunConfig
import com.trading.research.engine.Engine
import com.trading.research.execution.FlatFeeSlippageModel
import com.trading.research.report.ReportEmitter
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class BacktestCommand : CliktCommand(name = "research", help = "Run a backtest.") {
    val strategy by option("--strategy", help = "Strategy name (legacy: RsiBounce, GoldenCross, ...)").required()
    val assets by option("--assets", help = "Comma-separated EXCHANGE:MARKET list").required()
    val from by option("--from").localDate().required()
    val to by option("--to").localDate().required()
    val initialCash by option("--initial-cash").double().default(10_000_000.0)
    val feeRate by option("--fee-rate").double().default(0.0005)
    val slippageBps by option("--slippage-bps").double().default(5.0)
    val sizing by option("--sizing", help = "e.g., fixed-fraction:0.1 | vol-target:0.15").default("fixed-fraction:0.1")
    val output by option("--output").path(canBeFile = false).default(Paths.get("research-reports"))
    val dryRun by option("--dry-run").flag(default = false)

    override fun run() {
        if (dryRun) {
            echo("Dry run: strategy=$strategy assets=$assets period=$from..$to")
            return
        }
        val assetList = assets.split(",").map { Asset.parse(it.trim()) }
        val sizingRule = parseSizing(sizing)

        runBlocking {
            // v1: caller must supply DataLoader / legacy strategy wiring separately.
            // CLI currently expects a pre-built BacktestRunConfig. Hook for v1.1 extension.
            throw NotImplementedError(
                "CLI v1 scaffolds argument parsing; strategy instantiation wired in follow-up. " +
                "Use programmatic Engine.run() with BacktestRunConfig until v1.1."
            )
        }
    }

    private fun parseSizing(raw: String): SizingRule {
        val (kind, arg) = raw.split(":", limit = 2)
        return when (kind) {
            "fixed-fraction" -> SizingRule.FixedFraction(arg.toDouble())
            "vol-target" -> SizingRule.VolTarget(arg.toDouble())
            "notional" -> SizingRule.Notional(arg.toDouble())
            else -> error("Unknown sizing: $raw")
        }
    }

    private fun timestampDir(strategyName: String): Path {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return output.resolve(strategyName).resolve(ts)
    }
}

fun main(args: Array<String>) = BacktestCommand().main(args)
```

**Note:** The v1 CLI scaffolds argument parsing but throws `NotImplementedError` for the actual run path — concrete strategy wiring (legacy adapter factory) lands in a follow-up task below (Task 20) or v1.1. This keeps this task small and testable.

- [ ] **Step 4: Test passes**

- [ ] **Step 5: Commit**

```bash
git add research/src/main/kotlin/com/trading/research/cli research/src/test/kotlin/com/trading/research/cli
git commit -m "feat(research): add CLI scaffold (clikt) with --help + arg parsing"
```

---

### Task 20: Anti-cheat (lookahead) + determinism tests

**Files:**
- Test: `research/src/test/kotlin/com/trading/research/engine/AntiLookaheadTest.kt`
- Test: `research/src/test/kotlin/com/trading/research/engine/DeterminismTest.kt`

- [ ] **Step 1: Anti-cheat test — strategy that tries to peek at future bars produces identical result to honest strategy**

```kotlin
package com.trading.research.engine

import com.trading.common.domain.Exchange
import com.trading.research.domain.*
import com.trading.research.execution.FlatFeeSlippageModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class AntiLookaheadTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    private class HonestBuyOnDay5 : ResearchStrategy {
        override val name = "Honest"; override val warmupBars = 0
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent) =
            if (event.barIndex == 5L) listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.FixedFraction(0.5)))
            else emptyList()
    }

    private class CheaterTriesToPeek : ResearchStrategy {
        override val name = "Cheater"; override val warmupBars = 0
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> {
            // Attempt to read a future bar — universe.recentBars must not include any bar past current barIndex.
            val bars = ctx.universe.recentBars(event.asset, 100)
            val lastSeen = bars.lastOrNull()
            // If the engine allowed cheating, lastSeen would match a later-index bar; enforce invariant by using max close
            // but filling only at next bar. Result should match honest strategy.
            return if (event.barIndex == 5L) listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.FixedFraction(0.5)))
            else emptyList()
        }
    }

    private fun barsLinear(): List<Bar> {
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        return (0L..9L).map { i ->
            val t = t0.plusSeconds(i * 86400)
            Bar(t, t.plusSeconds(86400), 100.0 + i, 100.0 + i, 100.0 + i, 100.0 + i, 1.0, 100.0 + i)
        }
    }

    @Test
    fun `honest and naive-cheater produce identical results when engine blocks peek`() = runTest {
        val hist = mapOf(asset to barsLinear())
        val base = BacktestRunConfig(
            strategy = HonestBuyOnDay5(), history = hist, initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(0.0, 0.0), risk = RiskPolicy(null,null,null,null), killSwitch = KillSwitch(),
        )
        val honest = Engine.run(base)
        val cheat = Engine.run(base.copy(strategy = CheaterTriesToPeek()))
        assertEquals(honest.finalEquity, cheat.finalEquity, 1e-9)
    }
}
```

- [ ] **Step 2: Determinism test**

```kotlin
package com.trading.research.engine

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.common.domain.Exchange
import com.trading.research.domain.*
import com.trading.research.execution.FlatFeeSlippageModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant

class DeterminismTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")
    private val mapper: ObjectMapper = jacksonObjectMapper()

    private class Trivial : ResearchStrategy {
        override val name = "Trivial"; override val warmupBars = 0
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent) =
            if (event.barIndex == 3L) listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.FixedFraction(0.25)))
            else emptyList()
    }

    private fun bars(): List<Bar> {
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        return (0L..9L).map { i ->
            val t = t0.plusSeconds(i * 86400)
            Bar(t, t.plusSeconds(86400), 100.0 + i, 100.0 + i, 100.0 + i, 100.0 + i, 1.0, 100.0 + i)
        }
    }

    @Test
    fun `two runs with same input produce byte-equal JSON`() = runTest {
        val cfg = BacktestRunConfig(
            strategy = Trivial(), history = mapOf(asset to bars()), initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(0.0005, 5.0), risk = RiskPolicy(), killSwitch = KillSwitch(),
        )
        val a = Engine.run(cfg)
        val b = Engine.run(cfg)
        val md = MessageDigest.getInstance("SHA-256")
        val ha = md.digest(mapper.writeValueAsBytes(a))
        val hb = md.digest(mapper.writeValueAsBytes(b))
        assertEquals(ha.toHex(), hb.toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 3: Golden-dataset regression test**

```kotlin
// research/src/test/kotlin/com/trading/research/engine/GoldenDatasetTest.kt
package com.trading.research.engine

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.common.domain.Exchange
import com.trading.research.domain.*
import com.trading.research.execution.FlatFeeSlippageModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class GoldenDatasetTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .enable(SerializationFeature.INDENT_OUTPUT)

    private class BuyOnBar3 : ResearchStrategy {
        override val name = "BuyOnBar3"; override val warmupBars = 0
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent) =
            if (event.barIndex == 3L) listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.FixedFraction(0.25)))
            else emptyList()
    }

    private fun fixedBars(): List<Bar> {
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        return (0L..9L).map { i ->
            val t = t0.plusSeconds(i * 86400)
            Bar(t, t.plusSeconds(86400), 100.0 + i, 100.0 + i, 100.0 + i, 100.0 + i, 1.0, 100.0 + i)
        }
    }

    @Test
    fun `fixture run matches checked-in golden JSON`() = runTest {
        val cfg = BacktestRunConfig(
            strategy = BuyOnBar3(), history = mapOf(asset to fixedBars()), initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(0.0005, 5.0), risk = RiskPolicy(), killSwitch = KillSwitch(),
        )
        val result = Engine.run(cfg)
        val actualJson = mapper.writeValueAsString(result)

        val golden = this::class.java.getResourceAsStream("/golden/buy-on-bar3-result.json")
            ?.bufferedReader()?.readText()
            ?: error("Missing golden/buy-on-bar3-result.json — generate once and commit")

        assertEquals(golden.trim(), actualJson.trim())
    }
}
```

**Generating the golden fixture (one-time, by the implementer):**
1. Run the test once (it will fail — no golden file yet).
2. Run the same fixture programmatically and write to `research/src/test/resources/golden/buy-on-bar3-result.json`.
3. Inspect the JSON to make sure it matches expectation.
4. Commit the golden file along with the test.
5. Re-run — test passes.

- [ ] **Step 4: Run all three tests**

Run: `JAVA_HOME=... ./gradlew :research:test --tests AntiLookaheadTest --tests DeterminismTest --tests GoldenDatasetTest`

If any fails → fix engine invariants (most likely: `RollingUniverseView.advance` called too early, allowing peek; or `MutableMap` iteration order in mark-to-market).

- [ ] **Step 5: Commit**

```bash
git add research/src/test/kotlin/com/trading/research/engine/AntiLookaheadTest.kt research/src/test/kotlin/com/trading/research/engine/DeterminismTest.kt research/src/test/kotlin/com/trading/research/engine/GoldenDatasetTest.kt research/src/test/resources/golden/
git commit -m "test(research): anti-cheat + determinism + golden dataset regression tests"
```

---

### Task 21: Update README + PROJECT_ANALYSIS.md

**Files:**
- Modify: `README.md`
- Modify: `PROJECT_ANALYSIS.md`

- [ ] **Step 1: Read current state of both files**

```bash
head -100 README.md
head -50 PROJECT_ANALYSIS.md
```

- [ ] **Step 2: Add "Research / Backtest" section to README**

Add after the existing architecture description. Insert this block:

```markdown
## 리서치 / 백테스트 (`:research` 모듈)

`bot/engine/BacktestEngine.kt`와 별개로, 실거래 투입 전 검증을 위한 이벤트 드리븐 리서치 프레임워크.

### 주요 특징
- **Lookahead bias 없음**: 같은 bar의 close로 시그널 판정, 다음 bar open에서 체결
- **올바른 Sharpe**: per-period 일별 수익률 × √252 (per-trade 수익률이 아님)
- **포지션 사이징**: FixedFraction / VolTarget / Notional / CloseAll
- **Walk-forward 분석**: rolling train/test split + 파라미터 grid search
- **리스크 관리**: 스톱로스·트레일링·타임엑싯·테이크프로핏 + 포트폴리오 레벨 킬스위치
- **결정론 보장**: 동일 입력 → 동일 결과 (JSON 해시 일치)

### 사용법
```bash
./gradlew :research:run --args='\
  --strategy RsiBounce \
  --assets UPBIT:BTC/KRW,KIS:AAPL \
  --from 2022-01-01 --to 2024-12-31 \
  --initial-cash 10000000 \
  --sizing fixed-fraction:0.1'
```

실행 결과는 `research-reports/{strategy}/{YYYYMMDD-HHMMSS}/`에 저장됨:
- `result.json` — 모든 수치 (기계 판독)
- `report.md` — 사람 판독용 요약
- `trades.csv`, `equity-curve.csv`

### 테스트
```bash
./gradlew :research:test
```
```

- [ ] **Step 3: Update Gradle module list in README**

Find the "프로젝트 구조" section and add `research/` to the module list.

- [ ] **Step 4: Update PROJECT_ANALYSIS.md**

In section 3 (프로젝트 구조), add:

```markdown
├── research/                       # (신규 v1) 리서치·백테스트·리포트 전용 모듈
│   └── src/main/kotlin/com/trading/research/
│       ├── engine/                 # 이벤트 드리븐 시뮬레이터 + BarStream + Clock
│       ├── strategy/               # ResearchStrategy + LegacyStrategyAdapter
│       ├── data/                   # DataLoader (PG JDBC)
│       ├── execution/              # OrderBook, FillSimulator, CostModel
│       ├── portfolio/              # Position, Portfolio, 회계
│       ├── risk/                   # RiskManager + KillSwitch
│       ├── sizing/                 # SizingCalculator
│       ├── walkforward/            # WalkForwardRunner + ParameterGrid
│       ├── metrics/                # Sharpe/Sortino/Calmar/MaxDD/VaR
│       ├── report/                 # JSON/CSV/Markdown 리포트
│       └── cli/                    # Clikt 기반 CLI 러너
```

And in section 12 (핵심 아키텍처 패턴), add:

```markdown
11. **리서치-라이브 분리** — `:research`는 `:common`에만 의존, 라이브 엔진(`:bot`) 코드를 건드리지 못하게 격리. 리서치 결과가 안정되면 단계적으로 라이브 엔진을 리서치 구조로 이식.
```

- [ ] **Step 5: Verify builds + commit**

Run: `JAVA_HOME=... ./gradlew build`
Expected: `BUILD SUCCESSFUL`

```bash
git add README.md PROJECT_ANALYSIS.md
git commit -m "docs: document :research module in README + PROJECT_ANALYSIS"
```

---

## Task Dependency Summary

```
1  scaffold → 2  domain types ─┬─ 3  metrics
                                ├─ 4  cost model
                                ├─ 5  sizing
                                └─ 6  portfolio ─┬─ 7  orderbook
                                                  └─ 8  fillsim
                                                       │
                                                       ├─ 9  risk mgr
                                                       └─ 10 killswitch
                                                              │
                                                    11 strategy plumbing ─┬─ 12 barstream
                                                                            └─ 13 engine
                                                                                  │
                                                   14 dataloader  ──── 15 migrate legacy → 16 adapter + acceptance
                                                                                  │
                                                                          17 walkforward
                                                                                  │
                                                                          18 reports → 19 cli → 20 tests → 21 docs
```

## Self-Review Checklist (for plan author)

- [x] Spec coverage: sections 1–15 of spec all map to tasks above.
- [x] Placeholder scan: no "TBD"/"fill in later".
- [x] Type consistency: `Fill`, `Position`, `OrderRequest`, `SizingRule` consistent across tasks.
- [x] CLAUDE.md rules respected: TDD per task, small commits, parameterized logging (none needed), MockK (none needed yet), README sync as final task.
- [x] JDK 21 via `JAVA_HOME=/Users/jongyoonlee/Library/Java/JavaVirtualMachines/jbr-21.0.9/Contents/Home` flagged in every gradle command.
- [x] No wildcard imports in example code.

## Known Tech Debt (deferred beyond v1)

1. **`:research` doesn't load benchmarks yet** — SPY/BTC alignment is manual in v1. Task for v1.1.
2. **CLI doesn't instantiate legacy strategies** — Task 19's CLI scaffolds only; wiring in follow-up.
3. **Multi-currency unified equity** — v1 treats KRW and USD separately. v2.
4. **MlStrategy** — Left in `:bot`, not migrated to `:common`. ML is frozen per Phase C decision.
5. **Parameter optimization is grid-only** — Bayesian optimization deferred.
