package com.trading.bot.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * G3(plateau) 게이트가 **별칭(alias)을 세고 있는가** — #181.
 *
 * G3 는 "한 스텝 이웃의 70% 이상이 함께 G1+G5 를 통과해야 한다" 로 고립 좌표를 거른다. 그런데 그리드에는
 * **거래가 바이트 단위로 같은 좌표**가 섞여 있다(익절 센티널 위쪽, 발동하지 않는 손절 등).
 * 자기 자신과 같은 행동을 하는 이웃이 통과에 기여하면 plateau 는 "효과가 이웃으로 이어진다" 가 아니라
 * **"이웃이 사실상 나 자신이다"** 를 세게 된다.
 *
 * 이 감사가 필요한 이유: 승격된 변형 A 는 사전고정 판정에서 **G3 단독 탈락**(이웃 6/10)이었는데
 * 미관측 7국면에서는 통과했다([[trailing-arm-finding-2026-09]]) — **두 사전고정이 반대를 가리킨다.**
 * 별칭이 원인이면 게이트 쪽 결함이고, 아니면 G3 탈락은 실재하는 신호다.
 *
 * 실행: `RUN_PLATEAU_AUDIT=true ./gradlew :bot:test --tests "*PlateauAliasAuditTest*" --rerun-tasks`
 */
class PlateauAliasAuditTest {

    private val search = StrategySearch()

    private fun live() = PlateauAuditPoints.live()
    private fun variantA() = PlateauAuditPoints.variantA()
    private fun survivors() = PlateauAuditPoints.survivors().map { it.second }

    /** A 에서 어느 축이 어떻게 바뀐 이웃인지 — 표에서 한눈에 읽히게. */
    private fun changedAxis(a: SweepPoint, n: SweepPoint): String = buildList {
        if (n.kValue != a.kValue) add("k ${a.kValue}→${n.kValue}")
        if (n.takeProfitPct != a.takeProfitPct) add("TP ${fmtTp(a.takeProfitPct)}→${fmtTp(n.takeProfitPct)}")
        if (n.maxLossPct != a.maxLossPct) add("SL ${a.maxLossPct}→${n.maxLossPct}")
        if (n.trailingStopPct != a.trailingStopPct) add("트레일 ${a.trailingStopPct}→${n.trailingStopPct}")
        if (n.trailingArmPct != a.trailingArmPct) add("arm ${a.trailingArmPct}→${n.trailingArmPct}")
        if (n.maxHoldDays != a.maxHoldDays) add("보유 ${a.maxHoldDays}→${n.maxHoldDays}")
        if (n.marketFilterMa != a.marketFilterMa) add("필터 ${a.marketFilterMa}→${n.marketFilterMa}")
    }.joinToString(", ").ifEmpty { "(동일)" }

    private fun fmtTp(v: Double) = if (v >= StrategySearchGrid.TAKE_PROFIT_OFF) "off" else "%.0f".format(v)

    private data class Verdict(val raw: Pair<Int, Int>, val selfExcluded: Pair<Int, Int>, val dedup: Pair<Int, Int>, val both: Pair<Int, Int>)

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_PLATEAU_AUDIT", matches = "true")
    fun `does folding aliases change the plateau verdict`() = runBlocking {
        val yearly = YearlyFixtures.loadAll()
        val grid = StrategySearchGrid.stageA()
        val baseline = live()
        val all = (grid.points + baseline).distinct()
        println("[plateau] 좌표 ${all.size} 측정 시작")
        val select = search.measure(yearly, StrategySearch.SELECT, all)
        val base = select.getValue(baseline)

        // StageA 와 같은 정의: G5 를 함께 걸어야 "이웃이 아무것도 안 해서" plateau 가 채워지는 것을 막는다.
        for (p in listOf(variantA()) + survivors()) {
            require(p in grid.points) { "감사 대상이 격자 밖이다: ${p.label()} — 이웃·분모가 무의미해진다" }
        }

        val g1Pass = grid.points.filter { p ->
            val m = select.getValue(p)
            StrategySearchGates.g5(m.trades, m.zeroTradeMarkets) &&
                StrategySearchGates.g1(StrategySearchGates.pairedDeltas(m.returnByMarket, base.returnByMarket))
        }.toHashSet()
        println("[plateau] G1+G5 통과 좌표 ${g1Pass.size}")

        fun verdictOf(p: SweepPoint): Verdict {
            val own = select.getValue(p).fingerprint
            val ns = grid.neighbours(p)
            fun ratio(list: List<SweepPoint>) = list.count { it in g1Pass } to list.size
            val notSelf = ns.filter { select.getValue(it).fingerprint != own }
            val dedup = ns.distinctBy { select.getValue(it).fingerprint }
            val both = notSelf.distinctBy { select.getValue(it).fingerprint }
            return Verdict(ratio(ns), ratio(notSelf), ratio(dedup), ratio(both))
        }
        fun pass(v: Pair<Int, Int>) = v.second > 0 && v.first.toDouble() / v.second >= StrategySearchGates.PLATEAU_MIN_RATIO
        fun cell(v: Pair<Int, Int>) = if (v.second == 0) "이웃 0" else "%d/%d %s".format(v.first, v.second, if (pass(v)) "통과" else "**탈락**")

        val out = StringBuilder()
        out.appendLine("# G3(plateau) 별칭 감사 — 이웃을 지문으로 접으면 판정이 바뀌는가")
        out.appendLine()
        out.appendLine("G3 는 한 스텝 이웃의 **${(StrategySearchGates.PLATEAU_MIN_RATIO * 100).toInt()}% 이상**이 함께 G1+G5 를 통과할 것을 요구한다.")
        out.appendLine("네 가지 세는 법을 나란히 놓는다 — 차이가 곧 별칭이 판정에 기여한 몫이다.")
        out.appendLine()
        out.appendLine("| 세는 법 | 정의 |")
        out.appendLine("|---|---|")
        out.appendLine("| 원안 | 현행. 이웃 전부를 센다 |")
        out.appendLine("| 자기별칭 제외 | **거래 지문이 자기 자신과 같은** 이웃을 뺀다 — 그건 '이웃' 이 아니라 나다 |")
        out.appendLine("| 지문 dedup | 지문이 같은 이웃끼리 하나로 접는다 |")
        out.appendLine("| 둘 다 | 자기별칭 제외 + dedup |")
        out.appendLine()
        out.appendLine("## 대상 좌표")
        out.appendLine()
        out.appendLine("| 좌표 | 원안 | 자기별칭 제외 | 지문 dedup | 둘 다 |")
        out.appendLine("|---|---|---|---|---|")
        val targets = listOf("변형 A (승격됨)" to variantA()) + PlateauAuditPoints.survivors()
        for ((name, p) in targets) {
            val v = verdictOf(p)
            out.appendLine("| %s | %s | %s | %s | %s |".format(name, cell(v.raw), cell(v.selfExcluded), cell(v.dedup), cell(v.both)))
        }
        out.appendLine()
        out.appendLine("> **네 열을 독립 확인 4개로 읽지 말 것.** 출발 좌표가 G1+G5 를 통과하면 자기별칭 이웃도 **반드시** 통과한다")
        out.appendLine("> (지문이 같으면 거래가 같고 G1·G5 입력이 전부 거래 파생이다). 그러면 `자기별칭 제외`는 통과 이웃만 덜어내")
        out.appendLine("> 비율을 **낮추기만** 한다 — 탈락을 통과로 뒤집을 수학적 가능성이 0 이다(`자기별칭 제외`·`둘 다` 두 열 모두).")
        out.appendLine("> 즉 **A 를 구제할 수 있었던 세는 법은 `지문 dedup` 하나**인데, A 의 이웃 지문이 서로 달라 그것은 항등이었다:")
        run {
            val a = variantA()
            val ns = grid.neighbours(a)
            val fps = ns.map { select.getValue(it).fingerprint }
            out.appendLine("> 이웃 %d 개 → 서로 다른 지문 %d 개(접힌 이웃 %d 개), 자기별칭 %d 개.".format(
                ns.size, fps.distinct().size, ns.size - fps.distinct().size,
                fps.count { it == select.getValue(a).fingerprint }))
            out.appendLine("> **애초에 별칭 가설로는 A 를 되살릴 수 없었다.** 이 감사가 실제로 배제한 것은")
            out.appendLine("> \"A 의 plateau 가 별칭 때문에 부풀거나 꺼졌다\" 가 아니라 아래 **그리드 전역의 방향성**이다.")
        }
        out.appendLine()

        // 변형 A 가 얼마나 뾰족한가 — 이웃 하나하나의 성적. plateau 탈락이 실재한다면 이게 그 실체다.
        out.appendLine("## 변형 A 의 이웃 상세 — 얼마나 뾰족한가")
        out.appendLine()
        out.appendLine("plateau 탈락이 별칭 탓이 아니라면, 실제로 이웃들이 못 따라온다는 뜻이다. 그 실체를 본다.")
        out.appendLine("`G1` 은 선택창 paired delta 중앙 ≥ +${StrategySearchGates.G1_MEDIAN_MIN}%p **그리고** 양수 마켓 ≥ ${StrategySearchGates.G1_POSITIVE_MARKETS_MIN}/8.")
        out.appendLine()
        out.appendLine("| 이웃 (A 에서 바뀐 축) | 선택창 중앙 %p | 양수 마켓 | 거래수 | G5 | G1 | 자기별칭 |")
        out.appendLine("|---|---|---|---|---|---|---|")
        run {
            val a = variantA()
            val aFp = select.getValue(a).fingerprint
            val am = select.getValue(a)
            val ad = StrategySearchGates.pairedDeltas(am.returnByMarket, base.returnByMarket)
            out.appendLine("| **A 자신** | **%+.2f** | **%d/8** | %d | — | — | — |".format(
                SwingMetrics.median(ad), ad.count { it > 0 }, am.trades))
            for (n in grid.neighbours(a)) {
                val m = select.getValue(n)
                val d = StrategySearchGates.pairedDeltas(m.returnByMarket, base.returnByMarket)
                val g5 = StrategySearchGates.g5(m.trades, m.zeroTradeMarkets)
                val g1 = StrategySearchGates.g1(d)
                out.appendLine("| %s | %+.2f | %d/8 | %d | %s | %s | %s |".format(
                    changedAxis(a, n), SwingMetrics.median(d), d.count { it > 0 }, m.trades,
                    if (g5) "통과" else "탈락", if (g1) "**통과**" else "탈락",
                    if (m.fingerprint == aFp) "예" else "—"))
            }
        }
        out.appendLine()

        // "단일 축 효과는 구조적으로 불리하다" 는 변호를 **산술로 검증**한다. 인정해도 통과하지 못하면 그 변호는 못 쓴다.
        out.appendLine("## 변호 검증 — \"단일 축 효과라 구조적으로 불리하다\" 가 A 를 구제하는가")
        out.appendLine()
        out.appendLine("A 의 우위는 트레일링 축 하나에서 온다. 그 축을 되돌린 이웃은 효과를 잃으니 구조적으로 불리하다 —")
        out.appendLine("는 변호가 가능하다. 그 손실을 인정해 분모에서 빼면 판정이 바뀌는가?")
        out.appendLine()
        out.appendLine("(전제부터 약하다: **\"되돌리면 정의상 탈락\" 은 거짓**이다 — 생존 E1 의 트레일↑ 이웃은 +2.13%p·6/8 로 G1 을 통과한다.")
        out.appendLine("`PlateauStructureAuditTest` §1. 되돌림이 A 에서만 치명적인 것은 G1 이 **절대 임계**(2.0%p)라 G3 가 사실상 마진 테스트로")
        out.appendLine("작동하기 때문이다 — A 의 여유는 +1.28%p, E1 은 +2.92%p 이고 트레일 한 스텝 비용은 양쪽 −2.5%p 안팎이다.)")
        out.appendLine()
        run {
            val a = variantA()
            val ns = grid.neighbours(a)
            val passing = ns.count { it in g1Pass }
            // 효과 축(트레일링) 이웃 = 트레일링만 다른 이웃.
            val effectAxis = ns.filter { it.trailingStopPct != a.trailingStopPct }
            val effectAxisFailing = effectAxis.count { it !in g1Pass }
            val n2 = ns.size - effectAxisFailing
            val r1 = passing.toDouble() / ns.size
            val r2 = if (n2 > 0) passing.toDouble() / n2 else 0.0
            out.appendLine("| 기준 | 비율 | 판정 |")
            out.appendLine("|---|---|---|")
            out.appendLine("| 원안 | %d/%d = %.1f%% | %s |".format(passing, ns.size, r1 * 100, if (r1 >= StrategySearchGates.PLATEAU_MIN_RATIO) "통과" else "**탈락**"))
            out.appendLine("| 효과 축 되돌림 이웃 제외 | %d/%d = %.1f%% | %s |".format(passing, n2, r2 * 100, if (r2 >= StrategySearchGates.PLATEAU_MIN_RATIO) "통과" else "**탈락**"))
            out.appendLine()
            out.appendLine("**→ 구조적 편향을 인정해도 A 는 70% 를 넘지 못한다.** 효과 축 이웃은 하나뿐인데(트레일 1.5 는 축의 경계값)")
            out.appendLine("실패 이웃은 넷이라, 하나를 빼도 나머지 셋이 남는다. **이 변호로는 탈락이 설명되지 않는다.**")
            out.appendLine()
            // 더 강한 진술: 그 편향이 애초에 이 격자에서 구속력이 있는가. 이웃이 n 개면 상한은 (n-1)/n.
            val minDeg = grid.points.minOf { grid.neighbours(it).size }
            val cap = (minDeg - 1).toDouble() / minDeg
            val binding = grid.points.count { val d = grid.neighbours(it).size; (d - 1).toDouble() / d < StrategySearchGates.PLATEAU_MIN_RATIO }
            out.appendLine("게다가 이 편향은 **이 격자에서 애초에 구속력이 없다**. 이웃이 n 개면 효과 축 하나를 잃어도 상한은 (n-1)/n 이다.")
            out.appendLine("격자 최소 이웃 수는 **%d**(축 값이 경계이거나 arm 이 트레일에 종속돼 잘린 좌표)라 상한이 최소 **%.1f%%** 이고, 기준 %.0f%% 를 밑도는 좌표는 **%d 개**다.".format(minDeg, cap * 100, StrategySearchGates.PLATEAU_MIN_RATIO * 100, binding))
            out.appendLine("A 는 이웃 %d 개 → 상한 %.1f%%. **여유가 %.1f%%p 남는데 실측이 %.1f%% 다** — 구속한 것은 편향이 아니라 실패 이웃 셋이다.".format(ns.size, (ns.size - 1) * 100.0 / ns.size, (ns.size - 1) * 100.0 / ns.size - StrategySearchGates.PLATEAU_MIN_RATIO * 100, r1 * 100))
        }
        out.appendLine()

        // 그리드 전체: 세는 법을 바꾸면 몇 좌표의 판정이 뒤집히나.
        out.appendLine("## 그리드 전체 — 판정이 뒤집히는 좌표 수")
        out.appendLine()
        out.appendLine("실제 게이트는 G5→G1→G3 순이라 **G1+G5 를 통과한 좌표만 G3 판정을 받는다**(`StrategySearchStageA`).")
        out.appendLine("그리드 명목 좌표 수를 분모로 쓰면 뒤집힐 수 없는 좌표가 98% 를 채워 통계가 무의미해지므로, 여기 분모는 그 통과 집합이다.")
        out.appendLine()
        var flipToFail = 0
        var flipToPass = 0
        var selfAliasEdges = 0
        var withSelfAlias = 0
        var rawG3Pass = 0
        val flippedToFail = ArrayList<SweepPoint>()
        for (p in g1Pass) {
            val v = verdictOf(p)
            val a = pass(v.raw)
            val b = pass(v.both)
            if (a) rawG3Pass++
            if (a && !b) { flipToFail++; flippedToFail += p }
            if (!a && b) flipToPass++
            val selfAliases = v.raw.second - v.selfExcluded.second
            selfAliasEdges += selfAliases
            if (selfAliases > 0) withSelfAlias++
        }
        val watched = (listOf(variantA()) + survivors()).toSet()
        out.appendLine("| 항목 | 값 |")
        out.appendLine("|---|---|")
        out.appendLine("| 그리드 명목 좌표 | ${grid.points.size} |")
        out.appendLine("| **G3 판정 대상**(G1+G5 통과) | **${g1Pass.size}** |")
        out.appendLine("| 그중 원안 G3 통과 | $rawG3Pass |")
        out.appendLine("| 자기별칭 이웃을 가진 좌표 | $withSelfAlias (자기별칭 **간선** ${selfAliasEdges / 2} 개 — 이웃 관계가 대칭이라 방향당 1회씩 총 $selfAliasEdges 회 세어진다) |")
        out.appendLine("| 원안 통과 → 둘 다 적용 시 탈락 | **$flipToFail** (원안 통과 $rawG3Pass 의 %.1f%%) |".format(if (rawG3Pass > 0) flipToFail * 100.0 / rawG3Pass else 0.0))
        out.appendLine("| 원안 탈락 → 둘 다 적용 시 통과 | **$flipToPass** |")
        out.appendLine("| 뒤집힌 좌표에 A·생존 3좌표 포함 여부 | ${if (flippedToFail.any { it in watched }) "**포함**" else "없음"} |")
        out.appendLine()
        out.appendLine("## 읽는 법")
        out.appendLine()
        out.appendLine("- **탈락→통과 0건**: 별칭이 어떤 좌표의 plateau 를 *깎아* 억울하게 떨어뜨린 사례는 없다. A 도 그중 하나가 아니다.")
        out.appendLine("- **통과→탈락 %d건(%.1f%%)**: 반대 방향은 실재한다 — 원안 G3 통과의 열에 하나는 **별칭이 만든 plateau** 였다.".format(flipToFail, if (rawG3Pass > 0) flipToFail * 100.0 / rawG3Pass else 0.0))
        out.appendLine("  사전고정 판정이 그만큼 무른 쪽으로 틀렸다는 뜻이고, 이건 게이트 눈금 과제(#185)에 속한다. **A 는 이 17 에 없다.**")
        out.appendLine("- 즉 별칭은 **G3 를 무르게 만드는 방향으로만** 작동한다. A 의 탈락을 설명하지 못한다.")
        out.appendLine()
        out.appendLine("## 결론")
        out.appendLine()
        out.appendLine("1. **정확일치 별칭 아티팩트는 배제됐다** — 지문을 접는 어떤 방식으로도 A 는 탈락하고(구제 가능했던 것은")
        out.appendLine("   `지문 dedup` 하나인데 A 에서 항등이었다), G3 대상 집합 전체로도 **탈락→통과는 0건**이다.")
        out.appendLine("   별칭은 판정을 무르게 할 뿐 엄하게 하지 않는다.")
        out.appendLine("2. **단일 축 구조 편향으로도 설명되지 않는다** — 분모에서 빼도 66.7%로 미달이고, 애초에 그 편향은 이 격자에서 구속력이 없다(A 의 상한 90.0%, 격자 최소 80.0%).")
        out.appendLine("3. ~~실패한 넷은 이미 나쁘다고 아는 방향이다~~ — **철회한다.** 축·방향별 전역 통과율을 재보니")
        out.appendLine("   전역으로 죽은 방향은 **필터↑(0.3%) 하나뿐**이고 보유↑는 53.1%, SL↓는 62.9%로 대체로 통과한다.")
        out.appendLine("   즉 A 의 실패 넷 중 **셋은 A 국소 사실**이며, 그것이 바로 G3 가 재려던 것이다(`PlateauStructureAuditTest` §2).")
        out.appendLine("   생존 3좌표도 보유↑·필터↑ 를 똑같이 잃고도 통과한다 — 그 변호는 A 와 E 를 가르지 못한다.")
        out.appendLine("4. 다만 A 가 자기 효과 축에서 칼날인 것은 아니다 — 격자 밖 해상도로 트레일링을 스캔하면")
        out.appendLine("   1.00 +6.27(8/8) → 1.50 +3.28(7/8) → 2.00 +0.80(5/8) 로 **단조 감쇠**한다. 스파이크가 아니라 능선이다.")
        out.appendLine("   **대신 그 스캔이 승격값을 흔든다 — D1 최적은 1.5 가 아니라 1.0 이고, 1.5 는 격자 하한이 만든 값이다.**")
        out.appendLine()
        out.appendLine("**A 는 G3 를 실제로 통과하지 못한다.** G3(선택창 이웃 구조)와 7국면 확증(미관측 구간 성과)은 서로 다른 것을 재며,")
        out.appendLine("2026-09-06 승격은 **후자만을 근거로** 이뤄졌다. 이 감사는 그것을 정당화하지 않는다.")
        out.appendLine()
        out.appendLine("> **다만 \"게이트에 결함이 없다\" 고까지 말하지 않는다** — 이 감사가 배제한 결함 후보는 *정확일치 별칭* 하나다.")
        out.appendLine("> 다른 후보가 실제로 남아 있다: G1+G5 통과 %d 좌표 중 원안 G3 통과는 %d(%.1f%%)뿐이고 plateau 비율 중앙값은 %.3f 라,".format(
            g1Pass.size, rawG3Pass, rawG3Pass * 100.0 / g1Pass.size, SwingMetrics.median(g1Pass.map { p -> grid.neighbours(p).let { ns -> ns.count { it in g1Pass }.toDouble() / ns.size } })))
        out.appendLine("> **A 의 0.600 은 중앙보다 위**다. 70% 라는 임계는 어떤 null 에도 보정된 적이 없고(#185), G1 이 절대 임계라")
        out.appendLine("> G3 는 plateau(표면의 평탄성)가 아니라 사실상 **마진 테스트**로 작동한다. 둘 다 이 감사의 범위 밖이다.")

        val path = Path.of("build/reports/plateau-alias-audit.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[plateau] 리포트: ${path.toAbsolutePath()}")

        // 리포트 산문은 사람이 쓴 것이라 값이 바뀌어도 조용히 낡는다. 판정 자체를 고정해 그때 테스트가 깨지게 한다.
        assertFalse(pass(verdictOf(variantA()).raw), "변형 A 가 G3 를 통과하게 바뀌었다 — 리포트·wiki 결론을 다시 써야 한다")
        assertFalse(pass(verdictOf(variantA()).both), "변형 A 가 별칭을 접으면 통과하게 바뀌었다 — 결론 1 이 무효다")
        assertTrue(survivors().all { pass(verdictOf(it).raw) && pass(verdictOf(it).both) }, "생존 3좌표 중 G3 를 놓친 것이 있다")
        assertEquals(0, flipToPass, "별칭을 접었더니 탈락→통과가 생겼다 — 결론 1 이 무효다")
    }
}
