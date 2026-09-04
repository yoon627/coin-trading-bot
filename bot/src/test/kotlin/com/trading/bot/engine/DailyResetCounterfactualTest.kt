package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.VolatilityBreakout
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * #128 일일리셋 반사실 측정 — CI 비실행(수동 전용):
 *   `RUN_COUNTERFACTUAL=true ./gradlew :bot:test --tests "*DailyResetCounterfactualTest*"`
 *
 * 라이브는 KST 09:00 거래일 경계에서 보유상한 청산(`DAILY_RESET`)을 내는 **동시에** `boughtToday` 가 풀려
 * 곧바로 재매수가 가능하다. 그 churn 이 수익 기여 없이 비용만 내는지를 네 정책으로 갈라 측정한다.
 *
 * **판정 기준은 plan(A5a~A5h)에서 사전 고정됐고 이 파일에 인코딩돼 있다** — 실효 독립표본이 ~2개
 * (마켓 상관 평균 0.49, BTC/ETH 0.90)라 사후에 기준을 움직이면 어떤 결론이든 만들 수 있다.
 * 선례: [M1ReplayBiasTest] 도 표본 미달 시 유보를 코드로 강제한다.
 *
 * **이 설계가 재지 못하는 것**(리포트에 병기):
 * - 재진입 슬리피지. D1 에서 재진입가 = 청산가(`bar.open`)라 가격 갭이 구조적으로 0이다.
 * - `volatility_breakout` 의 라이브 트리거. `target = 당일시가 + k×전일레인지` 라 라이브는 09:00 에
 *   재진입하지 못하고 장중 돌파 시점에 더 비싸게 산다(#128 관측: 07:32·08:43·14:27, +2.88~3.09%).
 * - 라이브 신호 window 의 당일 부분 봉(`MarketDataStore` 가 분봉마다 upsert).
 */
@EnabledIfEnvironmentVariable(named = "RUN_COUNTERFACTUAL", matches = "true")
class DailyResetCounterfactualTest {

    companion object {
        /** 운영 배포값(`TRADING_STRATEGY`)이 primary. `combined` 는 코드 기본값이라 함께 사전등록. */
        private val STRATEGIES = listOf("volatility_breakout", "combined")

        /** 마켓당 리셋 이벤트가 이 수 미만이면 그 마켓은 추정에서 제외(A5f). */
        private const val MIN_RESET_EVENTS = 3

        /** 국면당 유효 마켓이 이 수 미만이면 판정 유보(A5f). */
        private const val MIN_MARKETS_PER_REGIME = 2

        /** Student-t 95% 양측 (index=df). df>30 은 보수적으로 마지막 값 유지. */
        private val T95 = doubleArrayOf(
            0.0, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
        )
    }

    /** 팔 = 정책 하나. `maxHoldDays=1` 고정이 원칙이고 `hold-through` 만 리셋을 끈다(Decision 3). */
    private data class Arm(val label: String, val config: BacktestConfig)

    private val arms = listOf(
        Arm("hold-through", BacktestConfig(maxHoldDays = 999)),
        Arm("live-reproduction", BacktestConfig(maxHoldDays = 1, reentryMode = ReentryMode.LIVE_SAME_BAR)),
        Arm("cooldown-1", BacktestConfig(maxHoldDays = 1, reentryMode = ReentryMode.LIVE_SAME_BAR, reentryCooldownBars = 1)),
        Arm("cooldown-2", BacktestConfig(maxHoldDays = 1, reentryMode = ReentryMode.LIVE_SAME_BAR, reentryCooldownBars = 2)),
        Arm("cooldown-3", BacktestConfig(maxHoldDays = 1, reentryMode = ReentryMode.LIVE_SAME_BAR, reentryCooldownBars = 3)),
        Arm(
            "conditional-reset",
            BacktestConfig(maxHoldDays = 1, reentryMode = ReentryMode.LIVE_SAME_BAR, holdLimitOnlyWhenProfitable = true),
        ),
    )

    /** 현행(`live-reproduction`) 대비 개선폭을 재는 대안들 — #128 의 1안(쿨다운)·2안(조건부)·3안(제거). */
    private val alternatives = listOf("cooldown-1", "cooldown-2", "cooldown-3", "conditional-reset", "hold-through")

    private val engine = BacktestEngine(listOf(VolatilityBreakout(), CombinedStrategy()), TradingProperties())

    /**
     * 팔 하나의 마켓 하나 결과. 비교 허용 지표만 담는다(Decision 11) — MDD/Sharpe 는 보유기간이 다른
     * 팔끼리 단위가 달라(MDD 는 청산 시점에만 갱신, Sharpe 는 거래당·비연율화) 담지 않는다.
     */
    private data class Cell(
        /** END 제외 trade 들의 `pnlPercent` 합. 가법이라 이벤트 단위 나눗셈·표본 제외가 정의된다. */
        val sumPnl: Double,
        val trades: Int,
        val resetEvents: Int,
        val feePct: Double,
        val endTrades: Int,
        val endPnl: Double,
    )

    @Test
    fun `measure daily reset counterfactual across arms`() = runBlocking {
        val report = StringBuilder()
        report.appendLine("# #128 일일리셋 반사실 측정")
        report.appendLine()
        report.appendLine("- 판정 기준은 plan A5a~A5h 에서 **사전 고정**됐고 이 테스트에 인코딩돼 있다.")
        report.appendLine("- 구간: fixture 200봉 전체(in/out 분할 없음). `maxHoldDays` 는 `hold-through` 만 999, 나머지 1.")
        report.appendLine("- 비교 허용 지표: 이벤트당 %p / 가법 수익합 / 거래수 / 수수료. MDD·Sharpe 는 팔 간 비교 불가라 제외.")
        report.appendLine("- END(시리즈 말미 마크투마켓) trade 는 전 팔에서 제외 후 재계산하고 개수를 병기한다.")
        report.appendLine()

        for (strategy in STRATEGIES) {
            report.appendLine("## 전략 `$strategy`${if (strategy == STRATEGIES[0]) " (운영 배포값 — primary)" else " (코드 기본값 — secondary)"}")
            report.appendLine()

            val perRegimePerEvent = mutableMapOf<BacktestFixtures.Regime, MutableMap<String, Double>>()

            for (regime in BacktestFixtures.ORIGINAL_REGIMES) {
                report.appendLine("### ${regime.label}")
                report.appendLine()
                report.appendLine("| 마켓 | 팔 | 가법수익합%p | 거래수 | 리셋이벤트 | 수수료%p | END(건/기여) |")
                report.appendLine("|---|---|---|---|---|---|---|")

                val perEvent = mutableMapOf<String, Double>()
                // 대안 label -> (마켓 -> 현행 대비 %p/건). 분모를 현행의 리셋 이벤트 수로 고정해
                // 모든 대안을 같은 척도(지금 실제로 일어나는 리셋 1건당)에 올린다.
                val altPerEvent = alternatives.associateWith { mutableMapOf<String, Double>() }

                for (market in BacktestFixtures.markets(regime)) {
                    val candles = BacktestFixtures.load(regime, market)
                    val cells = mutableMapOf<String, Cell>()

                    for (arm in arms) {
                        val result = engine.run(strategy, candles, market, arm.config)
                        if (result == null) {
                            report.appendLine("| $market | ${arm.label} | (봉 부족) | - | - | - | - |")
                            continue
                        }
                        val cell = toCell(result, arm.config)
                        cells[arm.label] = cell
                        report.appendLine(
                            "| $market | ${arm.label} | %.2f | %d | %d | %.2f | %d / %.2f".format(
                                cell.sumPnl, cell.trades, cell.resetEvents, cell.feePct, cell.endTrades, cell.endPnl,
                            ) + " |",
                        )
                    }

                    val live = cells["live-reproduction"]
                    val hold = cells["hold-through"]
                    if (live != null && live.resetEvents >= MIN_RESET_EVENTS) {
                        if (hold != null) perEvent[market] = (live.sumPnl - hold.sumPnl) / live.resetEvents
                        alternatives.forEach { label ->
                            cells[label]?.let { alt ->
                                altPerEvent.getValue(label)[market] = (alt.sumPnl - live.sumPnl) / live.resetEvents
                            }
                        }
                    }
                }

                report.appendLine()
                report.appendLine(
                    "리셋 이벤트 ${MIN_RESET_EVENTS}건 미만 마켓은 추정 제외(A5f) — " +
                        "유효 ${perEvent.size} / ${BacktestFixtures.markets(regime).size} 마켓",
                )
                report.appendLine()
                report.appendLine("**primary estimand — 리셋 1건당 %p (`live-reproduction` − `hold-through`)**")
                report.appendLine()
                report.appendLine("| 마켓 | %p/건 |")
                report.appendLine("|---|---|")
                perEvent.toSortedMap().forEach { (m, v) -> report.appendLine("| $m | %.3f |".format(v)) }
                report.appendLine()
                report.appendLine(summarize(perEvent.values.toList()))
                report.appendLine()
                report.appendLine("**정책 대안 — 현행(`live-reproduction`) 대비 리셋 1건당 %p (양수 = 개선)**")
                report.appendLine()
                report.appendLine("| 정책 | #128 안 | 마켓 균등가중 | 개선된 마켓 |")
                report.appendLine("|---|---|---|---|")
                alternatives.forEach { label ->
                    val v = altPerEvent.getValue(label).values.toList()
                    val tag = when (label) {
                        "conditional-reset" -> "2안 대상한정"
                        "hold-through" -> "3안 리셋제거"
                        else -> "1안 쿨다운"
                    }
                    if (v.isEmpty()) {
                        report.appendLine("| `$label` | $tag | (표본 없음) | - |")
                    } else {
                        report.appendLine(
                            "| `$label` | $tag | %.3f | %d/%d |".format(v.average(), v.count { it > 0 }, v.size),
                        )
                    }
                }
                report.appendLine()

                perRegimePerEvent[regime] = perEvent
            }

            report.appendLine(verdict(perRegimePerEvent))
            report.appendLine()
            report.appendLine(holdDaysSensitivity(strategy))
            report.appendLine()
        }

        report.appendLine("## 이 측정이 재지 못하는 성분 (A5h — 결론과 반드시 함께 읽을 것)")
        report.appendLine()
        report.appendLine("1. **재진입 슬리피지** — D1 에서 재진입가 = 청산가(`bar.open`)라 가격 갭이 구조적으로 0이다. #128 의 헤드라인(+1.80% 더 비싸게)은 여기 안 잡힌다.")
        report.appendLine("2. **`volatility_breakout` 의 라이브 트리거 divergence** — `target = 당일시가 + k×전일레인지` 라 라이브는 09:00 에 재진입 불가하고 장중 돌파 시점에 산다. 백테는 봉 D-1 돌파를 신호로 봉 D 시가에 재진입하므로 트리거 시점·가격·빈도가 모두 다르다.")
        report.appendLine("3. **라이브 신호의 당일 부분 봉** — `MarketDataStore` 가 분봉마다 당일 D1 을 upsert 해 09:00:10 신호 window 에 이미 들어 있다. 백테가 흉내내면 look-ahead 라 불가.")
        report.appendLine("4. **fixture 밖 티커** — #128 대표사례 4건 중 SOL·AVAX·ADA 3건이 fixture 에 없다(겹치는 건 DOGE 뿐).")
        report.appendLine("5. **표본** — 국면 2개, 마켓 상관 평균 0.49(BTC/ETH 0.90) → 실효 독립표본 ~2. 설명적 분석까지만 유효(A5g).")

        val out = File("build/reports/daily-reset-counterfactual.md")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println(report)
        println("리포트: ${out.absolutePath}")
    }

    /**
     * A4d — `maxHoldDays` 민감도. **primary 결론과 섞지 않는다**: 팔 비교는 `maxHoldDays=1` 고정이 원칙이고
     * (Decision 3, 교락 회피) 이 표는 "상한 자체를 며칠로 두느냐"라는 별개 축이다.
     */
    private suspend fun holdDaysSensitivity(strategy: String): String {
        val sb = StringBuilder("### maxHoldDays 민감도 (별도 축 — primary 와 섞지 말 것)\n\n")
        sb.append("`live-reproduction` 팔에서 상한만 바꾼다. 값은 마켓 균등가중 가법수익합%p / 리셋이벤트 수.\n\n")
        sb.append("| 국면 | h=1 | h=2 | h=3 | h=5 |\n|---|---|---|---|---|\n")
        for (regime in BacktestFixtures.ORIGINAL_REGIMES) {
            val cols = listOf(1, 2, 3, 5).map { h ->
                val cells = BacktestFixtures.markets(regime).mapNotNull { market ->
                    engine.run(
                        strategy,
                        BacktestFixtures.load(regime, market),
                        market,
                        BacktestConfig(maxHoldDays = h, reentryMode = ReentryMode.LIVE_SAME_BAR),
                    )?.let { toCell(it, BacktestConfig(maxHoldDays = h)) }
                }
                if (cells.isEmpty()) "-" else "%.2f / %.1f".format(
                    cells.map { it.sumPnl }.average(),
                    cells.map { it.resetEvents }.average(),
                )
            }
            sb.append("| ${regime.label} | ${cols.joinToString(" | ")} |\n")
        }
        return sb.toString()
    }

    private fun toCell(result: BacktestResult, config: BacktestConfig): Cell {
        val (end, real) = result.trades.partition { it.reason == "END" }
        return Cell(
            sumPnl = real.sumOf { it.pnlPercent },
            trades = real.size,
            resetEvents = real.count { it.reason == "TIME_EXIT" },
            feePct = real.size * config.feeRate * 2 * 100,
            endTrades = end.size,
            endPnl = end.sumOf { it.pnlPercent },
        )
    }

    /** 마켓 균등가중 평균 + 95% CI(A5b·A5c). CI 는 **참고값이며 게이트가 아니다**. */
    private fun summarize(values: List<Double>): String {
        if (values.isEmpty()) return "유효 마켓 없음 — 추정 불가."
        val mean = values.average()
        if (values.size < 2) return "마켓 균등가중 평균 **%.3f %%p/건** (N=1, CI 산출 불가)".format(mean)
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        val t = T95.getOrElse(values.size - 1) { T95.last() }
        val half = t * sd / sqrt(values.size.toDouble())
        val negative = values.count { it < 0 }
        return "마켓 균등가중 평균 **%.3f %%p/건** (N=%d, 95%%CI %.3f ~ %.3f — 참고값, 게이트 아님). 음수 %d/%d 마켓."
            .format(mean, values.size, mean - half, mean + half, negative, values.size)
    }

    /**
     * 사전 고정 판정(A5d·A5e·A5f·A5g).
     * 국면 간 부호 비교는 **`PAIRED_MARKETS` 로만** — BEAR 8 vs BULL 4 는 마켓 구성 교락이라 부호 차이가
     * 국면에서 온 건지 마켓에서 온 건지 가릴 수 없다.
     */
    private fun verdict(perRegime: Map<BacktestFixtures.Regime, MutableMap<String, Double>>): String {
        val sb = StringBuilder("### 판정 (사전 고정 기준)\n\n")

        val insufficient = perRegime.filterValues { it.size < MIN_MARKETS_PER_REGIME }
        if (insufficient.isNotEmpty()) {
            return sb.append(
                "**판정 유보** — ${insufficient.keys.joinToString { it.label }} 의 유효 마켓이 " +
                    "$MIN_MARKETS_PER_REGIME 개 미만이다(A5f).\n",
            ).toString()
        }

        val paired = perRegime.mapValues { (_, m) ->
            m.filterKeys { it in BacktestFixtures.PAIRED_MARKETS }.values.toList()
        }
        val pairedMeans = paired.mapValues { (_, v) -> if (v.isEmpty()) Double.NaN else v.average() }
        sb.append("국면 간 비교는 `PAIRED_MARKETS`(${BacktestFixtures.PAIRED_MARKETS.joinToString()}) 로만 한다(A5d).\n\n")
        val fullMeans = perRegime.mapValues { (_, m) -> if (m.isEmpty()) Double.NaN else m.values.average() }
        pairedMeans.forEach { (regime, mean) ->
            sb.append(
                "- ${regime.label}: paired %.3f %%p/건 (N=%d) / 전체마켓 %.3f (N=%d)\n".format(
                    mean, paired.getValue(regime).size,
                    fullMeans.getValue(regime), perRegime.getValue(regime).size,
                ),
            )
        }
        sb.append("\n")

        // 마켓 선택에 대한 강건성 — paired 부분집합과 전체의 부호가 갈리면 방향 주장이 표본 선택에 좌우된다는 뜻이다.
        // 판정 게이트는 아니고(사전 기준은 paired 고정) 결론 강도를 낮추는 공시다.
        val fragile = pairedMeans.keys.filter {
            val a = pairedMeans.getValue(it)
            val b = fullMeans.getValue(it)
            !a.isNaN() && !b.isNaN() && (a < 0) != (b < 0)
        }
        if (fragile.isNotEmpty()) {
            sb.append(
                "⚠️ **표본 선택에 취약** — ${fragile.joinToString { it.label }} 에서 paired 부분집합과 전체 마켓의 " +
                    "평균 부호가 반대다. 마켓 간 분산이 효과 크기를 압도한다는 뜻이므로 아래 방향 판정을 " +
                    "정량 근거로 쓰지 말 것.\n\n",
            )
        }

        if (pairedMeans.values.any { it.isNaN() }) {
            return sb.append("**판정 유보** — paired 마켓 표본이 비어 있는 국면이 있다(A5f).\n").toString()
        }

        val signs = pairedMeans.values.map { it < 0 }.toSet()
        return if (signs.size == 1) {
            val direction = if (pairedMeans.values.first() < 0) {
                "리셋 churn 이 **비용**이다(hold-through 가 유리)"
            } else {
                "리셋 churn 이 **이득**이다(신호 재평가가 유리)"
            }
            sb.append(
                "**방향성 있음** — 두 국면 모두 같은 부호(A5d). $direction.\n\n" +
                    "단 실효 독립표본 ~2 이므로 설명적 분석까지만 유효하다(A5g). 처방(리셋 제거/도입) 금지 — " +
                    "적용 전 소액 카나리아가 전제다.\n",
            ).toString()
        } else {
            sb.append(
                "**국면 의존 효과** — 국면별로 부호가 갈린다(A5e). 자동 유보가 아니라 이것이 결론이다: " +
                    "리셋의 손익은 시장 국면에 의존하므로 **국면 필터를 건 조건부 리셋**을 후속 이슈로 검토한다.\n\n" +
                    "실효 독립표본 ~2 이므로 설명적 분석까지만 유효하다(A5g).\n",
            ).toString()
        }
    }
}
