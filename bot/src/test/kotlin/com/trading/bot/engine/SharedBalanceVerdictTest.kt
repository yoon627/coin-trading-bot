package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * exit-resolution 판정(현행 라이브 vs 후보 E)을 **공유 잔고** 위에서 다시 센다(#180).
 *
 * 기존 리포트는 마켓별 고정 노셔널 10만원을 독립으로 굴린 합이다. 여기서는 같은 계기([LiveSemanticsArm]) 결과에
 * [SharedBalanceSim] 을 얹어 (i) 라이브 사이징 규칙(taper·복리)과 (ii) 동시 보유 슬롯 상한 아래에서 격차가 어떻게 되는지 본다.
 *
 * 사전고정(결과를 보기 전에 정한 주 통계량 셀 — plan `2026-09-16-shared-balance-sim` D5):
 * **yearly 1년 · 슬롯 S=1 · 격차 E−현행 · 마켓 순서 200 치환의 중앙값 순서에서 청산일 블록 부트스트랩 `P(격차≤0)`**.
 * 서술 통계다 — 발견 선언이 아니다. 나머지 셀은 민감도.
 *
 * 실행: `RUN_SHARED_BALANCE=true ./gradlew :bot:test --tests "*SharedBalanceVerdictTest*" --rerun-tasks`
 */
class SharedBalanceVerdictTest {

    private val props = TradingProperties()
    private val strategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }

    /** 현행 라이브 = 2026-09-06 승격(트레일링 1.5 / arm 0) — `RegimeExpansionTest` 의 "변형 A" 와 같은 점. */
    private fun current() = StrategySearchGrid.currentLivePoint()
    /** 2026-09-06 이전 기준선 — 공표 측정의 좌표. 참고용. */
    private fun legacy() = StrategySearchGrid.baselinePoint()
    private fun candidateE() = current().copy(kValue = 0.3, takeProfitPct = StrategySearchGrid.TAKE_PROFIT_OFF, maxLossPct = 7.0)

    private data class Window(val label: String, val dir: String, val daily: Map<String, List<Candle>>)

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_SHARED_BALANCE", matches = "true")
    fun `re-adjudicate the candidates on a single shared account`() = runBlocking {
        val arms = listOf(CURRENT to current(), "구 기준선(2026-09-06 이전)" to legacy(), CANDIDATE to candidateE())
        val windows = buildList {
            add(Window("1년 전체 (yearly)", "yearly", YearlyFixtures.loadAll()))
            for (r in BacktestFixtures.EXPANSION_2020_2023) add(Window(r.label, r.dir, BacktestFixtures.loadAll(r)))
        }

        // 창 × 팔 → 전 마켓 병합 Trade 목록.
        val tradesBy = LinkedHashMap<Pair<String, String>, List<LiveSemanticsArm.Trade>>()
        val rosterBy = LinkedHashMap<String, List<String>>()
        for (w in windows) {
            val intraday = IntradayFixtures.loadAll(w.dir, w.daily.keys)
            rosterBy[w.label] = w.daily.keys.toList()
            for ((label, point) in arms) {
                val all = ArrayList<LiveSemanticsArm.Trade>()
                for ((market, newestFirst) in w.daily) {
                    all += LiveSemanticsArm.run(market, strategy, newestFirst.reversed(), intraday.getValue(market).reversed(), point.toConfig(), props)
                }
                tradesBy[w.label to label] = all
            }
        }

        val out = StringBuilder()
        out.appendLine("# 공유 잔고 위에서의 재판정 — 현행 라이브 vs 후보 E")
        out.appendLine()
        out.appendLine("계기는 `LiveSemanticsArm`(240분봉, 진입·청산 라이브 규약) 그대로다. 그 위에 한 계좌의 현금 장부를 얹어 **어느 진입이 실제로 체결됐을까**를 다시 센다.")
        out.appendLine("기존 리포트의 금액은 마켓별 고정 노셔널 ${"%,.0f".format(NOTIONAL)}원을 독립으로 굴린 합이다(= 여기서의 **상한**).")
        out.appendLine()
        out.appendLine("**읽을 때 주의** — 후처리는 라이브 진입을 **과소** 계산한다: 라이브는 자금 부족으로 매수가 안 나가면 같은 날 다음 tick 에 다시 시도하지만,")
        out.appendLine("여기서는 skip 된 진입이 그날 통째로 없는 것으로 친다. 그 과소는 동시 진입이 많은 팔에 집중된다. 그래서 값은 **브래킷(하한 = skip 적용, 상한 = 독립)**이다.")
        out.appendLine("240분봉 안의 진입 순서는 알 수 없어 마켓 순서를 ${PERMUTATIONS}회 무작위 치환한 분포(중앙값·최악·최선)를 낸다 — 단일 순서를 헤드라인으로 쓰지 않는다.")
        out.appendLine()

        // ── (i) 라이브 규칙: min(cash × 0.1, 100k), 초기 1,000,000 ──
        val liveSizing = SharedBalanceSim.Sizing.live(INITIAL_KRW, props.investRatio, props.maxInvestAmount)
        out.appendLine("## (i) 라이브 사이징 규칙 — `min(현금 × ${props.investRatio}, ${"%,.0f".format(props.maxInvestAmount)})`, 초기 현금 ${"%,.0f".format(INITIAL_KRW)}원")
        out.appendLine()
        out.appendLine("이 규칙에서는 skip 이 나지 않는다(8마켓 동시 보유여도 현금 하한 ≈ ${"%,.0f".format(INITIAL_KRW * (1 - props.investRatio).pow(8))}원). 재는 것은 배제가 아니라 **동시 보유가 늘수록 뒤 마켓의 주문금액이 줄어드는 taper 와 복리**다.")
        out.appendLine("대조군 `독립 계좌` = 같은 규칙을 마켓마다 별도 계좌로 — `공유 − 독립계좌` 가 동시성 효과, `독립계좌 − 고정 10만원` 이 사이징·복리 효과. 부트스트랩은 걸지 않는다(경로 의존 값).")
        out.appendLine()
        out.appendLine("| 창 | 설정 | 거래수 | 고정 10만원 | 독립 계좌 | **공유 계좌** | 동시성 효과 | 평균 노셔널 | skip |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|")
        var liveSkipsTotal = 0
        for (w in windows) {
            for ((label, _) in arms) {
                val t = tradesBy.getValue(w.label to label)
                val shared = SharedBalanceSim.run(t, rosterBy.getValue(w.label), liveSizing)
                val indep = SharedBalanceSim.runIndependentAccounts(t, rosterBy.getValue(w.label), liveSizing)
                liveSkipsTotal += shared.skipped.size
                out.appendLine("| %s | %s | %d | %s | %s | **%s** | %s | %s | %d |".format(
                    w.label, label, t.size, krw(SharedBalanceSim.independentPnlKrw(t, NOTIONAL)), krw(indep.pnlKrw), krw(shared.pnlKrw),
                    krw(shared.pnlKrw - indep.pnlKrw), krw(shared.avgNotionalKrw), shared.skipped.size))
            }
        }
        out.appendLine()
        out.appendLine("### 초기 현금 민감도 — '동시성 효과' 는 초기 현금 가정에 종속된다")
        out.appendLine()
        out.appendLine("대조군 `독립 계좌` 는 마켓마다 초기 현금을 통째로 받아(총자본 N배) 항상 상한 100k 에 걸린다. 공유 계좌의 taper 는 초기 현금이 8마켓×100k 를 넉넉히 넘으면 사라진다 — 즉 이 값은 전략의 성질이 아니라 **계좌 규모의 함수**다.")
        out.appendLine()
        out.appendLine("| 초기 현금 | yearly 격차 E−현행 (공유) | yearly 동시성 효과 현행 / E | 7국면 pooled 격차 (공유, 국면별 리셋 합) |")
        out.appendLine("|---|---|---|---|")
        for (init in INITIAL_KRW_GRID) {
            val sz = SharedBalanceSim.Sizing.live(init, props.investRatio, props.maxInvestAmount)
            fun gapOf(w: Window): Triple<Double, Double, Double> {
                val roster = rosterBy.getValue(w.label)
                val cur = tradesBy.getValue(w.label to CURRENT); val cand = tradesBy.getValue(w.label to CANDIDATE)
                val sc = SharedBalanceSim.run(cur, roster, sz); val se = SharedBalanceSim.run(cand, roster, sz)
                val ic = SharedBalanceSim.runIndependentAccounts(cur, roster, sz); val ie = SharedBalanceSim.runIndependentAccounts(cand, roster, sz)
                return Triple(se.pnlKrw - sc.pnlKrw, sc.pnlKrw - ic.pnlKrw, se.pnlKrw - ie.pnlKrw)
            }
            val y = gapOf(windows.first())
            val pooled = windows.drop(1).sumOf { gapOf(it).first }
            out.appendLine("| %s | %s | %s / %s | %s |".format(krw(init), krw(y.first), krw(y.second), krw(y.third), krw(pooled)))
        }
        out.appendLine()

        // ── (ii) 슬롯 규칙: 고정 10만원, 동시 보유 S 개 ──
        out.appendLine("## (ii) 슬롯 규칙 — 고정 ${"%,.0f".format(NOTIONAL)}원, 동시 보유 상한 S")
        out.appendLine()
        out.appendLine("S = 마켓 수면 독립과 같아야 한다(자기검증). 격차 = 후보 E − 현행. 분포는 마켓 순서 ${PERMUTATIONS}회 치환(고정 seed).")
        out.appendLine()
        out.appendLine("현행·후보 E·격차·skip 은 **격차가 중앙값인 그 치환 하나**에서 뽑은 값(세 칸의 산수가 맞는다). 최악·최선은 200 치환 중.")
        out.appendLine()
        out.appendLine("| 창 | S | 현행 KRW | 후보 E KRW | **격차** | 격차 최악 | 격차 최선 | 독립 격차(상한) | E skip | 현행 skip |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        var primary: Primary? = null
        val pooledBySlot = LinkedHashMap<Int, DoubleArray>() // 7국면 pooled 격차(치환 index 별 합) — 국면마다 현금 리셋 후 합산
        val pooledWorstSum = HashMap<Int, Double>() // 창별 최악의 합(치환 index 결합과 별개의 진짜 하한)
        val pooledBestSum = HashMap<Int, Double>()
        for (w in windows) {
            val roster = rosterBy.getValue(w.label)
            val cur = tradesBy.getValue(w.label to CURRENT)
            val cand = tradesBy.getValue(w.label to CANDIDATE)
            val indepGap = SharedBalanceSim.independentPnlKrw(cand, NOTIONAL) - SharedBalanceSim.independentPnlKrw(cur, NOTIONAL)
            for (s in slotLevels(roster.size)) {
                val rng = Random(SEED)
                val gaps = DoubleArray(PERMUTATIONS)
                val curK = DoubleArray(PERMUTATIONS)
                val candK = DoubleArray(PERMUTATIONS)
                val candSkip = IntArray(PERMUTATIONS)
                val curSkip = IntArray(PERMUTATIONS)
                val orders = ArrayList<List<String>>(PERMUTATIONS)
                for (i in 0 until PERMUTATIONS) {
                    val order = roster.shuffled(rng)
                    orders += order
                    val sizing = SharedBalanceSim.Sizing.slots(s, NOTIONAL)
                    val a = SharedBalanceSim.run(cur, order, sizing)
                    val b = SharedBalanceSim.run(cand, order, sizing)
                    curK[i] = a.pnlKrw; candK[i] = b.pnlKrw; gaps[i] = b.pnlKrw - a.pnlKrw
                    curSkip[i] = a.skipped.size; candSkip[i] = b.skipped.size
                }
                if (s == roster.size) {
                    // 자기검증: 슬롯 = 마켓 수 → 팔마다 독립과 일치(1원 오차) — 격차만 보면 두 팔에 같이 작용하는 버그가 상쇄된다.
                    assertTrue(abs(curK[0] - SharedBalanceSim.independentPnlKrw(cur, NOTIONAL)) < 1.0, "S=N 현행 ${curK[0]} ≠ 독립 (${w.label})")
                    assertTrue(abs(candK[0] - SharedBalanceSim.independentPnlKrw(cand, NOTIONAL)) < 1.0, "S=N 후보 ${candK[0]} ≠ 독립 (${w.label})")
                    assertEquals(0, candSkip[0] + curSkip[0], "S=N 에서 skip 이 났다(${w.label})")
                }
                val medianIdx = medianIndex(gaps)
                out.appendLine("| %s | %d | %s | %s | **%s** | %s | %s | %s | %d | %d |".format(
                    w.label, s, krw(curK[medianIdx]), krw(candK[medianIdx]), krw(gaps[medianIdx]), krw(gaps.min()), krw(gaps.max()), krw(indepGap),
                    candSkip[medianIdx], curSkip[medianIdx]))
                if (w.dir != "yearly") {
                    pooledBySlot.getOrPut(s) { DoubleArray(PERMUTATIONS) }.let { acc -> for (i in gaps.indices) acc[i] += gaps[i] }
                    pooledWorstSum[s] = (pooledWorstSum[s] ?: 0.0) + gaps.min()
                    pooledBestSum[s] = (pooledBestSum[s] ?: 0.0) + gaps.max()
                }
                if (w.dir == "yearly" && s == PRIMARY_SLOTS) primary = Primary(orders[medianIdx], gaps[medianIdx], indepGap)
            }
        }
        out.appendLine()
        out.appendLine("### 확장 7국면 pooled(국면마다 현금 리셋 후 합산)")
        out.appendLine()
        out.appendLine("`치환 분포` 열은 같은 치환 index(seed 가 창마다 같아 로스터 위치 rank 패턴이 같다)를 7국면에서 더한 결합 분포다 — 그 최악은 창별 최악의 합이 아니다. 진짜 하한·상한은 `창별 최악 합`·`창별 최선 합`.")
        out.appendLine()
        out.appendLine("| S | 격차 합 중앙값(치환 분포) | 치환 분포 최악 | 치환 분포 최선 | 창별 최악 합 | 창별 최선 합 |")
        out.appendLine("|---|---|---|---|---|---|")
        for ((s, acc) in pooledBySlot) out.appendLine("| %d | %s | %s | %s | %s | %s |".format(s, krw(median(acc)), krw(acc.min()), krw(acc.max()), krw(pooledWorstSum.getValue(s)), krw(pooledBestSum.getValue(s))))
        out.appendLine()

        // ── 주 통계량(사전고정): yearly · S=1 · 중앙값 순서 · 청산일 블록 부트스트랩 ──
        val p = requireNotNull(primary)
        val yCur = tradesBy.getValue(windows.first().label to CURRENT)
        val yCand = tradesBy.getValue(windows.first().label to CANDIDATE)
        val sizing1 = SharedBalanceSim.Sizing.slots(PRIMARY_SLOTS, NOTIONAL)
        val curFills = SharedBalanceSim.run(yCur, p.order, sizing1).fills
        val candFills = SharedBalanceSim.run(yCand, p.order, sizing1).fills
        val dates = (curFills.map { it.trade.exitDate } + candFills.map { it.trade.exitDate }).distinct()
        val byDate = dates.associateWith { d -> candFills.filter { it.trade.exitDate == d }.sumOf { it.pnlKrw } - curFills.filter { it.trade.exitDate == d }.sumOf { it.pnlKrw } }
        val boot = DateBlockBootstrap.of(byDate)
        out.appendLine("## 주 통계량(사전고정) — yearly · S=$PRIMARY_SLOTS · 격차 E−현행 · 치환 중앙값 순서")
        out.appendLine()
        out.appendLine("- 순서: `${p.order.joinToString(" → ")}`")
        out.appendLine("- 격차(공유, 하한): **${krw(p.gap)}** · 독립(상한): ${krw(p.indepGap)}")
        out.appendLine("- 청산일 블록 부트스트랩(${DateBlockBootstrap.RESAMPLES}회, seed 고정): 5% 하한 ${krw(boot.p05)} · **P(격차 ≤ 0) = ${"%.3f".format(boot.pLeZero)}** (서술 통계 — 발견 선언 아님. 체결 집합은 슬롯 규칙에서도 경로 의존이라 날짜 교환가능은 근사다)")
        out.appendLine()

        // ── 2일 집중 재계산 ──
        val indepByDate = { t: List<LiveSemanticsArm.Trade> -> t.groupBy { it.exitDate }.mapValues { (_, v) -> v.sumOf { it.netPnlPct } * NOTIONAL / 100.0 } }
        val iCur = indepByDate(yCur); val iCand = indepByDate(yCand)
        out.appendLine("## 후보 E 의 2일 집중(2026-03-17 · 2026-01-06) — 청산일 기여(E−현행)로 재계산")
        out.appendLine()
        out.appendLine("⚠️ 이 표의 수치는 `exit-resolution-verdict-2026-09` 의 \"1년 우위의 92.6% 가 이 두 날\" 과 **같은 양이 아니다**. 그 92.6% 는 일봉 청산모델 계기에서 **구 기준선(`baselinePoint()`) 대비** 격차의 몫이고,")
        out.appendLine("여기 값은 240분봉 라이브 의미론 계기에서 **현행 라이브(`currentLivePoint()`) 대비**다 — 계기와 기준선이 둘 다 달라 \"92.6% → x%\" 로 읽을 수 없다. 여기서 말할 수 있는 것은 \"이 계기·이 기준선에서는 두 날에 집중이 없다\" 뿐이다.")
        out.appendLine()
        out.appendLine("| 날짜 | 독립(상한) 기여 | 공유 S=$PRIMARY_SLOTS(하한) 기여 | S=$PRIMARY_SLOTS 체결(현행/E, 청산일) | S=$PRIMARY_SLOTS skip(현행/E, 청산일) | S=$PRIMARY_SLOTS skip(현행/E, 진입일) |")
        out.appendLine("|---|---|---|---|---|---|")
        val curRun = SharedBalanceSim.run(yCur, p.order, sizing1)
        val candRun = SharedBalanceSim.run(yCand, p.order, sizing1)
        for (d in CONCENTRATION_DAYS) {
            out.appendLine("| %s | %s | %s | %d / %d | %d / %d | %d / %d |".format(
                d, krw((iCand[d] ?: 0.0) - (iCur[d] ?: 0.0)), krw(byDate[d] ?: 0.0),
                curRun.fills.count { it.trade.exitDate == d }, candRun.fills.count { it.trade.exitDate == d },
                curRun.skipped.count { it.exitDate == d }, candRun.skipped.count { it.exitDate == d },
                curRun.skipped.count { it.entryDate == d }, candRun.skipped.count { it.entryDate == d }))
        }
        val indepTotal = SharedBalanceSim.independentPnlKrw(yCand, NOTIONAL) - SharedBalanceSim.independentPnlKrw(yCur, NOTIONAL)
        val twoDaysIndep = CONCENTRATION_DAYS.sumOf { (iCand[it] ?: 0.0) - (iCur[it] ?: 0.0) }
        val twoDaysShared = CONCENTRATION_DAYS.sumOf { byDate[it] ?: 0.0 }
        out.appendLine()
        out.appendLine("두 날의 몫: 독립 ${krw(twoDaysIndep)} / ${krw(indepTotal)} = ${pct(twoDaysIndep, indepTotal)} · 공유 ${krw(twoDaysShared)} / ${krw(p.gap)} = ${pct(twoDaysShared, p.gap)}")
        out.appendLine()

        // ── 보유 이월 비율(근사 폭 표기) ──
        out.appendLine("## 한계")
        out.appendLine()
        val carry = { t: List<LiveSemanticsArm.Trade> -> t.count { it.entryDate != it.exitDate }.toDouble() / t.size }
        out.appendLine("- 후처리는 라이브 진입을 과소 계산한다(위). 보유 이월(진입일≠청산일, 대부분 09:00 TIME_EXIT) 비율: 현행 ${"%.1f".format(carry(yCur) * 100)}% · 후보 E ${"%.1f".format(carry(yCand) * 100)}% — 이월 거래의 skip 은 다음 날도 비워 줬을 진입을 빼앗는 방향이라 하한을 더 낮춘다.")
        out.appendLine("- 240분봉(하루 6봉)이라 같은 봉의 마켓 순서는 임의다 — 치환 분포의 폭은 그 임의성의 **상한**이다(여기서는 한 순서를 창 전체에 고정했지만 라이브 순서는 매일 바뀌어 날짜 간 평균화로 실제 폭은 더 좁다).")
        out.appendLine("- 라이브 순회 순서(보유 우선 + 유니버스 랭크)는 재현하지 않았다. 초기 현금 ${"%,.0f".format(INITIAL_KRW)}원은 가정이다.")
        out.appendLine("- 생존편향·슬리피지·호가 마찰은 계기의 한계 그대로.")

        val path = Path.of("build/reports/shared-balance.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[shared-balance] 리포트: ${path.toAbsolutePath()}")
        println(out)
        assertEquals(0, liveSkipsTotal, "라이브 규칙에서 skip 이 났다 — 사전 예측(0건)과 다르다: 구현 또는 가정 점검")
    }

    private data class Primary(val order: List<String>, val gap: Double, val indepGap: Double)

    private fun slotLevels(n: Int) = (listOf(1, 2, 4) + n).distinct().filter { it <= n }
    private fun krw(v: Double) = "%,.0f원".format(v)
    /** 총 격차가 음수(E 열세)면 '몫' 은 정의되지 않는다 — 부호가 반대인 분모의 비율을 비율로 읽게 두지 않는다. */
    private fun pct(a: Double, b: Double) = if (b <= 1e-9) "n/a(총 격차 ≤ 0)" else "%.1f%%".format(a / b * 100)
    private fun median(a: DoubleArray): Double { val s = a.sortedArray(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    /** 중앙값에 가장 가까운 원소의 인덱스 — 그 치환의 순서를 주 통계량에 쓴다. */
    private fun medianIndex(a: DoubleArray): Int { val m = median(a); return a.indices.minByOrNull { abs(a[it] - m) }!! }

    private companion object {
        const val CURRENT = "현행 라이브 (트레일링 1.5 / arm 0)"
        const val CANDIDATE = "후보 E (k0.3 · TP off · SL7 · 트레일링 1.5/0)"
        const val NOTIONAL = 100_000.0
        const val INITIAL_KRW = 1_000_000.0
        val INITIAL_KRW_GRID = listOf(500_000.0, 1_000_000.0, 4_000_000.0, 8_000_000.0)
        const val PERMUTATIONS = 200
        const val SEED = 20260916L
        const val PRIMARY_SLOTS = 1
        val CONCENTRATION_DAYS = listOf("2026-03-17", "2026-01-06")
    }
}
