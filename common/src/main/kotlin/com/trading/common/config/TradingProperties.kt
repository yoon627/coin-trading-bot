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
    // 트레일링 arm 임계(%): 고점 수익률이 이 값에 도달한 뒤에만 트레일링 평가. 0=수익 중 즉시(현행).
    // trailingStopPct 보다 클 때만 실효 — TP 를 크게 두고 추세를 트레일링으로 익절하는 구성용 (#27).
    val trailingArmPct: Double = 0.0,
    // 보유 상한(거래일, KST 09:00 경계): 매수 후 N 거래일 경과 시 강제 청산. 1=현행 일일리셋.
    val maxHoldDays: Int = 1,
    // 왕복(매수+매도) 수수료 비율 — Upbit 0.05%×2. 기록용 pnlPercent 차감에만 쓰며 청산 게이트는 gross.
    val roundTripFeeRate: Double = 0.001,
    val intervalSeconds: Long = 10,
    val autoStart: Boolean = false,
    // 차트/지표 기반 청산(shouldSell) 활성화. 기본 off — 켜기 전 백테스트 검증 권장.
    val chartExitEnabled: Boolean = false,
) {
    fun tickerList(): List<String> = tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
