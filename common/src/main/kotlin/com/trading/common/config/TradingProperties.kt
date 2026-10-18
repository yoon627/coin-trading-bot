package com.trading.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "trading")
data class TradingProperties(
    val tickers: String = "KRW-BTC",
    val investRatio: Double = 0.1,
    val maxInvestAmount: Double = 100_000.0,
    val kValue: Double = 0.5,
    val takeProfitPct: Double = 5.0,
    val maxLossPct: Double = 5.0,
    // 운영값은 env 로 덮어쓴다. 기본 생성자를 쓰는 테스트가 이 값을 전제한다.
    val trailingStopPct: Double = 2.0,
    // 고점 수익률이 이 값(%)에 닿은 뒤에만 트레일링을 평가한다. takeProfitPct 가 이 값과 trailingStopPct 보다
    // 커야 트레일링이 작동한다. 아니면 익절이 항상 먼저 걸린다.
    val trailingArmPct: Double = 3.0,
    // 매수 후 이 거래일 수(KST 09:00 경계)가 지나면 강제 청산한다.
    val maxHoldDays: Int = 1,
    // 기록용 pnlPercent 에서만 뺀다. 청산 판정은 수수료 전 수익률로 한다.
    val roundTripFeeRate: Double = 0.001,
    val intervalSeconds: Long = 10,
    val autoStart: Boolean = false,
    // reconcilePendingBuy 의 주문·잔고 조회가 연속으로 이 횟수만큼 실패하면 그 ticker 를 halt 한다.
    val reconcileHaltThreshold: Int = 20,
) {
    init {
        // 0 이하면 첫 실패에서 halt 돼 모든 ticker 의 매수가 멈춘다.
        require(reconcileHaltThreshold >= 1) { "trading.reconcile-halt-threshold must be >= 1, got $reconcileHaltThreshold" }
        // 0 이하면 tick 루프가 폭주하고 막힌 매도 알림도 첫 reconcile 에서 바로 나간다.
        require(intervalSeconds >= 1) { "trading.interval-seconds must be >= 1, got $intervalSeconds" }
    }

    fun tickerList(): List<String> = tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
