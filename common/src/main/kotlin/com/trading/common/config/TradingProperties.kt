package com.trading.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "trading")
data class TradingProperties(
    val tickers: String = "KRW-BTC",
    val strategy: String = "combined",
    val investRatio: Double = 0.1,
    val maxInvestAmount: Double = 100_000.0,
    val kValue: Double = 0.5,
    val takeProfitPct: Double = 5.0,
    val maxLossPct: Double = 5.0,
    val trailingStopPct: Double = 2.0,
    // 트레일링 arm 임계(%): 고점 수익률이 이 값에 도달한 뒤에만 트레일링 평가. 0 이면 수익 중 즉시.
    // takeProfitPct 가 이 값·trailingStopPct 보다 커야 트레일링이 실효한다 — 그렇지 않으면 익절이
    // 항상 먼저 걸려 트레일링이 죽는다(TradingEngine 이 부팅 시 경고). 기본값 TP5/trail2/arm3 은
    // 그 조건을 만족시키려고 잡은 값이다.
    val trailingArmPct: Double = 3.0,
    // 보유 상한(거래일, KST 09:00 경계): 매수 후 N 거래일 경과 시 강제 청산. 1=현행 일일리셋.
    val maxHoldDays: Int = 1,
    // 왕복(매수+매도) 수수료 비율 — Upbit 0.05%×2. 기록용 pnlPercent 차감에만 쓰며 청산 게이트는 gross.
    val roundTripFeeRate: Double = 0.001,
    val intervalSeconds: Long = 10,
    val autoStart: Boolean = false,
    // 차트/지표 기반 청산(shouldSell) 활성화. 기본 off — 켜기 전 백테스트 검증 권장.
    val chartExitEnabled: Boolean = false,
    // #19: reconcilePendingBuy 의 getOrder·잔고조회가 연속으로 이 횟수만큼 실패하면 해당 ticker 를 halt.
    // interval-seconds(기본 10s) 기준 20회 ≈ 200초 이상 지속 장애 시 무한 재시도를 멈춘다.
    val reconcileHaltThreshold: Int = 20,
) {
    init {
        // 0 이하면 첫 실패에서 곧바로 halt 되어 모든 ticker 의 매수가 멈춘다 — 기동 시 걸러낸다.
        require(reconcileHaltThreshold >= 1) { "trading.reconcile-halt-threshold must be >= 1, got $reconcileHaltThreshold" }
    }

    fun tickerList(): List<String> = tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
