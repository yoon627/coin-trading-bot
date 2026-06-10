package com.trading.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "trading")
data class TradingProperties(
    val tickers: String = "KRW-BTC",
    val strategy: String = "combined",
    val investRatio: Double = 0.1,
    val maxInvestAmount: Double = 100_000.0,
    val kValue: Double = 0.5,
    val takeProfitPct: Double = 2.0,
    val maxLossPct: Double = 5.0,
    val trailingStopPct: Double = 2.0,
    // 왕복(매수+매도) 수수료 비율 — Upbit 0.05%×2. 기록용 pnlPercent 차감에만 쓰며 청산 게이트는 gross.
    val roundTripFeeRate: Double = 0.001,
    val intervalSeconds: Long = 10,
    val autoStart: Boolean = false,
    // 차트/지표 기반 청산(shouldSell) 활성화. 기본 off — 켜기 전 백테스트 검증 권장.
    val chartExitEnabled: Boolean = false,
) {
    fun tickerList(): List<String> = tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
