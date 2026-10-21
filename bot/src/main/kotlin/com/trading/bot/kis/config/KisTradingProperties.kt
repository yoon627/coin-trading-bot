package com.trading.bot.kis.config

import com.trading.common.config.TradingProperties
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * KIS 주식 매매 프로파일. 기본은 꺼져 있고 엔진에는 아직 연결되지 않았다.
 *
 * 생성 시 값을 검사하지 않는다. 이 클래스는 KIS 를 쓰지 않아도 바인딩되므로 오기 하나로 Upbit 앱 기동이 막힐 수 있다.
 * 검사는 KIS 클라이언트를 만들 때와 배포 preflight 가 한다([KisExitParamRanges]).
 */
@ConfigurationProperties(prefix = "kis.trading")
data class KisTradingProperties(
    val enabled: Boolean = false,
    // 꺼져 있으면 실전 계좌 클라이언트를 만들지 않는다. 모의계좌만 쓴다.
    val liveEnabled: Boolean = false,
    val investRatio: Double = UPBIT_DEFAULTS.investRatio,
    val maxInvestAmount: Long = 1_000_000,
    val maxOrderAmount: Long = 2_000_000,
    val takeProfitPct: Double = UPBIT_DEFAULTS.takeProfitPct,
    val maxLossPct: Double = UPBIT_DEFAULTS.maxLossPct,
    val trailingStopPct: Double = UPBIT_DEFAULTS.trailingStopPct,
    val trailingArmPct: Double = UPBIT_DEFAULTS.trailingArmPct,
    val maxHoldDays: Int = UPBIT_DEFAULTS.maxHoldDays,
    val intervalSeconds: Long = UPBIT_DEFAULTS.intervalSeconds,
    // 초당 건수가 아니라 요청 사이 최소 간격(ms)이다. 667 은 초당 약 1.5건.
    val paperMinRequestIntervalMs: Long = 667,
    val liveMinRequestIntervalMs: Long = 60,
) {
    private companion object {
        // 숫자를 다시 적으면 Upbit 기본값을 바꿀 때 KIS 쪽이 따라가지 않는다.
        val UPBIT_DEFAULTS = TradingProperties()
    }
}
