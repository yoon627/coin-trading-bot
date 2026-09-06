package com.trading.bot.engine

/**
 * 두 plateau 감사([PlateauAliasAuditTest]·[PlateauStructureAuditTest])가 재는 좌표의 단일 정의 (#181).
 *
 * 각자 정의하면 좌표나 라벨이 갈려 두 리포트가 서로 다른 대상을 재고 교차인용이 깨진다(실제로 갈렸었다).
 */
internal object PlateauAuditPoints {

    fun live() = StrategySearchGrid.baselinePoint()

    /** 2026-09-06 라이브로 승격된 좌표 — 트레일링 1축(+arm) 변경. */
    fun variantA() = live().copy(trailingStopPct = 1.5, trailingArmPct = 0.0)

    /** 사전고정 Stage A 가 공표한 생존 3좌표(같은 행동 계열) — 대조군. */
    fun survivors(): List<Pair<String, SweepPoint>> = listOf(
        "E1 TPoff/SL7" to live().copy(kValue = 0.3, takeProfitPct = StrategySearchGrid.TAKE_PROFIT_OFF, maxLossPct = 7.0, trailingStopPct = 1.5, trailingArmPct = 0.0),
        "E2 TP12/SL7" to live().copy(kValue = 0.3, takeProfitPct = 12.0, maxLossPct = 7.0, trailingStopPct = 1.5, trailingArmPct = 0.0),
        "E3 TP8/SL7" to live().copy(kValue = 0.3, takeProfitPct = 8.0, maxLossPct = 7.0, trailingStopPct = 1.5, trailingArmPct = 0.0),
    )
}
