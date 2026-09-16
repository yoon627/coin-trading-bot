package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 현행 전략의 라이브 의미론 성적이 계기 해상도에 따라 뒤집히는 이유를 분해한다(#190).
 *
 * `exit-resolution-ladder-2026-09`: 기준선 240분 +235.9%p → 15분 −124.8 → 5분 −219.4. 240분봉 진입은 대체로 5분봉 진입의 부분집합이지만
 * 그 격차가 **추가 진입**(5분봉에만 있는 진입일)에서 오는지 **공통 진입의 체결가·청산 변화**에서 오는지 리포트가 분해하지 않았다.
 *
 * 분해(진입일·마켓 키): 5분 거래를 (a) 240분에도 있는 진입 (b) 5분 전용 진입으로, 240분 거래 중 5분에 없는 것을 (c) 240분 전용으로 나눈다.
 * (a) 안에서 페어별로 `pnl(5분 진입가, 240 청산가) − pnl240` = **체결가 효과**, `pnl5 − pnl(5분 진입가, 240 청산가)` = **청산 효과**.
 * 항등식: Σ(a) 체결가 + Σ(a) 청산 + Σ(b) − Σ(c) = Σpnl5 − Σpnl240. `combined` 의 RSI 가 부분봉에 따라 달라 (c) 가 비어 있지 않을 수 있다.
 *
 * 실행: `RUN_ENTRY_DECOMP=true BACKTEST_CACHE_DIR=~/.cache/coin-trading-bot/backtest-cache ./gradlew :bot:test --tests "*EntrySetDecompositionTest*" --rerun-tasks`
 */
class EntrySetDecompositionTest {

    private val props = TradingProperties()
    private val strategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }

    private data class Key(val market: String, val entryDate: String)

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_ENTRY_DECOMP", matches = "true")
    fun `decompose the resolution sign flip into entry sets and fill-versus-exit effects`() = runBlocking {
        val levels = LadderWindows.load(UNITS)
        val config = StrategySearchGrid.currentLivePoint().toConfig()
        val feePct = config.feeRate * 2 * 100
        fun pnl(entry: Double, exit: Double) = (exit - entry) / entry * 100.0 - feePct

        val byUnit = levels.associate { it.unit to LadderWindows.run(it, strategy, config, props) }
        val base = byUnit.getValue(240)
        // 자기검증: 분해 대상이 `exit-resolution-ladder-2026-09` 의 기준선과 같은 거래 집합인지(다른 기준선을 분해하는 사고 방지).
        for ((unit, expected) in PRIOR_BASELINE) {
            val t = byUnit[unit]?.values?.flatten() ?: continue
            assertEquals(expected.first, t.size, "$unit 분 기준 거래수가 선행 사다리와 다르다")
            assertTrue(abs(t.sumOf { it.netPnlPct } - expected.second) <= 0.05, "$unit 분 기준 Σpnl %.2f ≠ 선행 %.2f".format(t.sumOf { it.netPnlPct }, expected.second))
        }

        // 계기 항등식: Trade.netPnlPct == pnl(entryPrice, exitPrice) — 분해가 같은 식 위에 서 있음을 보장.
        for ((_, byWindow) in byUnit) for (t in byWindow.values.flatten()) {
            assertTrue(abs(pnl(t.entryPrice, t.exitPrice) - t.netPnlPct) < 1e-9, "손익식이 계기와 다르다: $t")
        }

        val out = StringBuilder()
        out.appendLine("# 해상도에 따른 기준선 부호 반전의 분해 — 진입 집합 × 체결가/청산 효과")
        out.appendLine()
        out.appendLine("현행 라이브(TP5/SL5/트레일1.5/arm0/k0.5/h1), 계기 `LiveSemanticsArm`, 10창. 키 = (마켓, 진입일). 수수료는 왕복 %.3f%%p 로 모든 항에 동일.".format(feePct))
        out.appendLine()
        out.appendLine("| 항 | 정의 |")
        out.appendLine("|---|---|")
        out.appendLine("| (a) 공통 진입 | 240분에도 같은 (마켓, 진입일) 진입이 있는 고해상도 거래 |")
        out.appendLine("| (a) 체결가 효과 | pnl(고해상도 진입가, 240분 청산가) − pnl240 — 진입가만 바뀐 효과 |")
        out.appendLine("| (a) 청산 효과 | pnl고해상도 − pnl(고해상도 진입가, 240분 청산가) — 청산만 바뀐 효과 |")
        out.appendLine("| (b) 고해상도 전용 진입 | 240분에는 없는 진입일 — 240분봉 안에서 되밀린 돌파 |")
        out.appendLine("| (c) 240분 전용 진입 | 고해상도에는 없는 진입일 — 부분봉이 달라 `combined` 의 RSI 조건이 갈린 경우 |")
        out.appendLine()
        out.appendLine("항등식: (a)체결가 + (a)청산 + Σ(b) − Σ(c) = Σpnl고해상도 − Σpnl240.")
        out.appendLine()

        for (unit in UNITS.filter { it != 240 }) {
            val hi = byUnit.getValue(unit)
            var aCount = 0; var aFill = 0.0; var aExit = 0.0; var aPnlHi = 0.0; var aPnl240 = 0.0
            val bTrades = ArrayList<LiveSemanticsArm.Trade>(); val cTrades = ArrayList<LiveSemanticsArm.Trade>()
            val aHi = ArrayList<LiveSemanticsArm.Trade>(); val a240 = ArrayList<LiveSemanticsArm.Trade>()
            var fillHigher = 0; var fillLower = 0
            for ((dir, hiTrades) in hi) {
                val b = base.getValue(dir).associateBy { Key(it.market, it.entryDate) }
                val h = hiTrades.associateBy { Key(it.market, it.entryDate) }
                assertEquals(hiTrades.size, h.size, "$unit m $dir: (마켓, 진입일) 이 유일하지 않다")
                for ((k, t) in h) {
                    val bt = b[k]
                    if (bt == null) { bTrades += t; continue }
                    aCount++; aHi += t; a240 += bt
                    val fillOnly = pnl(t.entryPrice, bt.exitPrice)
                    aFill += fillOnly - bt.netPnlPct
                    aExit += t.netPnlPct - fillOnly
                    aPnlHi += t.netPnlPct; aPnl240 += bt.netPnlPct
                    if (t.entryPrice > bt.entryPrice + 1e-9) fillHigher++ else if (t.entryPrice < bt.entryPrice - 1e-9) fillLower++
                }
                for ((k, bt) in b) if (k !in h) cTrades += bt
            }
            val sumHi = hi.values.flatten().sumOf { it.netPnlPct }; val sum240 = base.values.flatten().sumOf { it.netPnlPct }
            val bSum = bTrades.sumOf { it.netPnlPct }; val cSum = cTrades.sumOf { it.netPnlPct }
            val identity = aFill + aExit + bSum - cSum
            assertTrue(abs(identity - (sumHi - sum240)) < 0.01, "$unit m: 항등식 %.4f ≠ %.4f".format(identity, sumHi - sum240))

            out.appendLine("## ${unit}분봉 vs 240분봉")
            out.appendLine()
            out.appendLine("| 항 | 건수 | Σpnl %p | /거래 |")
            out.appendLine("|---|---|---|---|")
            out.appendLine("| 240분 기준 전체 | %d | %+.2f | %+.3f |".format(base.values.sumOf { it.size }, sum240, sum240 / base.values.sumOf { it.size }))
            out.appendLine("| ${unit}분 기준 전체 | %d | %+.2f | %+.3f |".format(hi.values.sumOf { it.size }, sumHi, sumHi / hi.values.sumOf { it.size }))
            out.appendLine("| **격차 (${unit}분 − 240분)** | | **%+.2f** | |".format(sumHi - sum240))
            out.appendLine("| (a) 공통 진입 — 240분 손익 | %d | %+.2f | %+.3f |".format(aCount, aPnl240, if (aCount == 0) 0.0 else aPnl240 / aCount))
            out.appendLine("| (a) 공통 진입 — ${unit}분 손익 | %d | %+.2f | %+.3f |".format(aCount, aPnlHi, if (aCount == 0) 0.0 else aPnlHi / aCount))
            out.appendLine("| (a) 체결가 효과 | %d | %+.2f | %+.3f |".format(aCount, aFill, if (aCount == 0) 0.0 else aFill / aCount))
            out.appendLine("| (a) 청산 효과 | %d | %+.2f | %+.3f |".format(aCount, aExit, if (aCount == 0) 0.0 else aExit / aCount))
            out.appendLine("| (b) ${unit}분 전용 진입 | %d | %+.2f | %+.3f |".format(bTrades.size, bSum, if (bTrades.isEmpty()) 0.0 else bSum / bTrades.size))
            out.appendLine("| (c) 240분 전용 진입 (빼는 항) | %d | %+.2f | %+.3f |".format(cTrades.size, cSum, if (cTrades.isEmpty()) 0.0 else cSum / cTrades.size))
            out.appendLine("| 항등식 검산 (a)+(b)−(c) | | %+.2f | |".format(identity))
            out.appendLine()
            out.appendLine("공통 진입 체결가: ${unit}분 진입가가 240분보다 높은 건 %d · 낮은 건 %d · 같은 건 %d (돌파선 위 첫 봉 시가라 고해상도가 보통 더 낮다 — 낮으면 유리).".format(fillHigher, fillLower, aCount - fillHigher - fillLower))
            out.appendLine()
            out.appendLine("| 청산 사유 | (a) 240분 | (a) ${unit}분 | (b) ${unit}분 전용 | (c) 240분 전용 |")
            out.appendLine("|---|---|---|---|---|")
            for (r in REASONS) {
                fun cell(ts: List<LiveSemanticsArm.Trade>) = ts.filter { it.reason == r }.let { "%d건 %+.1f".format(it.size, it.sumOf { x -> x.netPnlPct }) }
                out.appendLine("| $r | ${cell(a240)} | ${cell(aHi)} | ${cell(bTrades)} | ${cell(cTrades)} |")
            }
            out.appendLine()
            out.appendLine("### 창별 (b) ${unit}분 전용 진입")
            out.appendLine()
            out.appendLine("| 창 | 240분 기준 (건 · Σ) | ${unit}분 기준 (건 · Σ) | (a) 체결가 | (a) 청산 | (b) 건 · Σ | (c) 건 · Σ |")
            out.appendLine("|---|---|---|---|---|---|---|")
            for (w in levels.first().windows) {
                val b = base.getValue(w.dir); val h = hi.getValue(w.dir)
                val bk = b.associateBy { Key(it.market, it.entryDate) }; val hk = h.associateBy { Key(it.market, it.entryDate) }
                var f = 0.0; var e = 0.0
                for ((k, t) in hk) { val bt = bk[k] ?: continue; val fo = pnl(t.entryPrice, bt.exitPrice); f += fo - bt.netPnlPct; e += t.netPnlPct - fo }
                val bo = hk.filterKeys { it !in bk }.values; val co = bk.filterKeys { it !in hk }.values
                out.appendLine("| %s | %d · %+.1f | %d · %+.1f | %+.1f | %+.1f | %d · %+.1f | %d · %+.1f |".format(
                    w.label, b.size, b.sumOf { it.netPnlPct }, h.size, h.sumOf { it.netPnlPct }, f, e, bo.size, bo.sumOf { it.netPnlPct }, co.size, co.sumOf { it.netPnlPct }))
            }
            out.appendLine()
        }

        out.appendLine("## 한계")
        out.appendLine()
        out.appendLine("- 라이브 실측(`trade_records` 의 진입 빈도·체결가)과의 대조는 운영 DB 조회가 필요해 여기 없다 — 세 계기 중 무엇이 라이브에 가까운지는 그 대조가 답한다.")
        out.appendLine("- 분해는 산술 항등식이라 원인을 증명하지 않는다 — (b) 의 손익이 음수라는 것과 \"되밀린 돌파가 손실\" 은 같은 말이 아니다(그 진입들의 시장 상황은 따로 봐야 한다).")
        out.appendLine("- 5분봉 결측(허용 15%)이 있는 마켓-일은 봉이 적어 240분봉 쪽으로 굴러 (b) 를 과소 계산하는 방향.")

        val path = Path.of("build/reports/entry-set-decomposition.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[entry-decomp] 리포트: ${path.toAbsolutePath()}")
        println(out)
    }

    private companion object {
        val UNITS = listOf(240, 15, 5)
        /** `exit-resolution-ladder-2026-09` §0 의 기준선(거래수, Σpnl %p). */
        val PRIOR_BASELINE = mapOf(240 to (1_058 to 235.9), 15 to (1_659 to -124.8), 5 to (1_767 to -219.4))
        val REASONS = listOf("TRAILING_STOP", "TAKE_PROFIT", "STOP_LOSS", "TIME_EXIT", "END")
    }
}
