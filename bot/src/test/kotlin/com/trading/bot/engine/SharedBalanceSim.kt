package com.trading.bot.engine

import com.trading.bot.engine.LiveSemanticsArm.Trade

/**
 * 한 계좌의 현금 장부 위에서 "[LiveSemanticsArm] 이 낸 진입 중 무엇이 실제로 체결됐을까" 를 다시 센다(#180).
 *
 * 계기는 마켓마다 독립으로 돌아 마켓별 고정 노셔널을 가정한다. 실제 계좌는 하나라 상관이 높은 날 여러 마켓이
 * 동시에 신호를 내면 `min(KRW × investRatio, maxInvestAmount)` 로 뒤 마켓의 주문금액이 줄고, 현금이 바닥이면
 * 들어가지 못한다. 이 객체는 계기 결과를 **후처리**해 그 제약만 얹는다 — 계기의 진입·청산 규칙은 건드리지 않는다.
 *
 * 순서 규칙(라이브 `TradingEngine.runLoop` 미러): 이벤트를 봉 시각으로 정렬하고, 같은 봉에서는 **다른 마켓의 청산 →
 * 진입**(라이브가 같은 tick 에서 청산을 먼저 평가) → 진입 봉 안에서 끝난 자기 청산(`exitOnEntryBar`) 순. 진입끼리는
 * [marketOrder](라이브의 `activeTickers` 순회 순서 대신 — 실제 순서는 보유 우선 + 유니버스 랭크라 재현 불가, 호출 측이
 * 치환 분포로 민감도를 잰다).
 *
 * **라이브 진입을 과소 계산한다**: 라이브는 자금 부족으로 매수가 안 나가면 `boughtToday` 를 세우지 않아 같은 날 다음
 * tick 에 다시 시도하지만, 여기서는 skip 된 진입이 그날 통째로 없는 것으로 친다(계기에 "나중 봉의 진입" 이 없다).
 * 그 과소는 동시 진입이 많은 팔에 집중된다 — 결과는 하한이고, 상한은 [independentPnlKrw](전부 체결)다.
 */
internal object SharedBalanceSim {

    /** `PositionManager.MIN_ORDER_AMOUNT_KRW` — 이보다 작은 주문은 라이브가 내지 않는다. */
    const val MIN_ORDER_KRW = 5_000.0

    sealed interface Sizing {
        val initialKrw: Double

        /** 라이브 규칙 — `PositionManager.calculateInvestAmount`: `min(cash × investRatio, maxInvestAmount)`, [minOrderKrw] 미만은 skip. */
        data class Live(
            override val initialKrw: Double,
            val investRatio: Double,
            val maxInvestAmount: Double,
            val minOrderKrw: Double = MIN_ORDER_KRW,
        ) : Sizing

        /** 슬롯 규칙 — 고정 노셔널, 동시 보유 [slots] 개까지. 동시 진입 허용 폭 하나만 흔들기 위한 규칙(현금 잔량이 아니라 슬롯 수로 막는다). */
        data class Slots(val slots: Int, val notionalKrw: Double) : Sizing {
            override val initialKrw: Double get() = slots * notionalKrw
        }

        companion object {
            fun live(initialKrw: Double, investRatio: Double, maxInvestAmount: Double, minOrderKrw: Double = MIN_ORDER_KRW): Sizing =
                Live(initialKrw, investRatio, maxInvestAmount, minOrderKrw)

            fun slots(slots: Int, notionalKrw: Double): Sizing = Slots(slots, notionalKrw)
        }
    }

    data class Fill(val trade: Trade, val notionalKrw: Double, val pnlKrw: Double)

    data class Result(
        val fills: List<Fill>,
        val skipped: List<Trade>,
        val initialKrw: Double,
        val finalCashKrw: Double,
    ) {
        val pnlKrw: Double get() = finalCashKrw - initialKrw
        val avgNotionalKrw: Double get() = if (fills.isEmpty()) 0.0 else fills.sumOf { it.notionalKrw } / fills.size
    }

    /** 독립(마켓별 고정 노셔널) 기준 — 기존 리포트들이 쓰는 `Σ pnl% × 노셔널 / 100`. 공유 잔고 결과의 상한이기도 하다. */
    fun independentPnlKrw(trades: List<Trade>, notionalKrw: Double): Double = trades.sumOf { it.netPnlPct } * notionalKrw / 100.0

    /** 같은 사이징 규칙을 마켓마다 **별도 계좌**로 굴린 합 — 공유 계좌와의 차가 동시성 효과, 고정 노셔널과의 차가 사이징(taper·복리) 효과. */
    fun runIndependentAccounts(trades: List<Trade>, marketOrder: List<String>, sizing: Sizing): Result {
        val perMarket = marketOrder.map { m -> run(trades.filter { it.market == m }, listOf(m), sizing) }
        return Result(
            fills = perMarket.flatMap { it.fills },
            skipped = perMarket.flatMap { it.skipped },
            initialKrw = perMarket.sumOf { it.initialKrw },
            finalCashKrw = perMarket.sumOf { it.finalCashKrw },
        )
    }

    fun run(trades: List<Trade>, marketOrder: List<String>, sizing: Sizing): Result {
        val marketIndex = marketOrder.withIndex().associate { it.value to it.index }
        val events = ArrayList<Event>(trades.size * 2)
        for ((seq, t) in trades.withIndex()) {
            require(t.entryBarUtc.isNotEmpty() && t.exitBarUtc.isNotEmpty()) {
                "봉 시각이 없는 Trade 는 공유 잔고 순서를 정할 수 없다(옛 계기 출력?): $t"
            }
            // 사전식 정렬·같은 봉 판정은 고정폭 `yyyy-MM-ddTHH:mm:ss` 에 기댄다 — 오프셋·밀리초가 섞이면 순서가 조용히 틀린다.
            require(t.entryBarUtc.length == BAR_UTC_LENGTH && t.exitBarUtc.length == BAR_UTC_LENGTH) { "봉 시각 형식이 다르다: $t" }
            val mi = requireNotNull(marketIndex[t.market]) { "marketOrder 에 없는 마켓: ${t.market}" }
            events += Event(t.entryBarUtc, KIND_ENTRY, mi, seq, t)
            val exitKind = if (t.exitBarUtc == t.entryBarUtc) KIND_SELF_EXIT_SAME_BAR else KIND_EXIT
            events += Event(t.exitBarUtc, exitKind, mi, seq, t)
        }
        events.sortWith(compareBy<Event>({ it.time }, { it.kind }, { it.marketIndex }, { it.seq }))

        var cash = sizing.initialKrw
        val open = HashMap<Int, Double>() // seq → notional
        val fills = ArrayList<Fill>()
        val skipped = ArrayList<Trade>()
        for (e in events) {
            if (e.kind == KIND_ENTRY) {
                val amount = amountFor(sizing, cash, open.size)
                if (amount == null) {
                    skipped += e.trade
                } else {
                    cash -= amount
                    open[e.seq] = amount
                }
            } else {
                val notional = open.remove(e.seq) ?: continue // skip 된 진입의 청산 — 장부에 없다
                val pnl = notional * e.trade.netPnlPct / 100.0
                cash += notional + pnl
                fills += Fill(e.trade, notional, pnl)
            }
            // 슬롯 규칙은 고정 노셔널이라(독립 기준과 같은 의미) 누적 손실 뒤 장부 현금이 음수가 될 수 있다 — 현금으로 막는 건 라이브 규칙만.
            check(sizing is Sizing.Slots || cash > -1e-6) { "현금이 음수가 됐다: $cash at ${e.time} ${e.trade.market}" }
        }
        check(open.isEmpty()) { "청산 이벤트가 없는 진입이 남았다: ${open.keys}" }
        check(fills.size + skipped.size == trades.size) { "체결 ${fills.size} + skip ${skipped.size} ≠ 거래 ${trades.size}" }
        return Result(fills, skipped, sizing.initialKrw, cash)
    }

    private fun amountFor(sizing: Sizing, cash: Double, openCount: Int): Double? = when (sizing) {
        is Sizing.Live -> {
            val amount = minOf(cash * sizing.investRatio, sizing.maxInvestAmount)
            if (amount < sizing.minOrderKrw) null else amount
        }
        is Sizing.Slots -> if (openCount < sizing.slots) sizing.notionalKrw else null
    }

    private class Event(val time: String, val kind: Int, val marketIndex: Int, val seq: Int, val trade: Trade)

    private const val BAR_UTC_LENGTH = "yyyy-MM-ddTHH:mm:ss".length
    private const val KIND_EXIT = 0
    private const val KIND_ENTRY = 1
    /** 진입 봉 안에서 끝난 자기 청산 — 열리기 전에 닫히지 않게 진입 뒤로. 다른 마켓의 같은 봉 진입에는 현금을 돌려주지 않는다(봉 안 순서 미상 — 보수적). */
    private const val KIND_SELF_EXIT_SAME_BAR = 2
}
