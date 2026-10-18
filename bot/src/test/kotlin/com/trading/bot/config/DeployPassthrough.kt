package com.trading.bot.config

import com.trading.common.config.ShadowExitProperties
import com.trading.common.config.TradingProperties
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 배포가 서버 `.env` → compose → 앱으로 이름만 넘기는 설정 클래스 목록. 앱의 설정 클래스는 [PASSED] 와 [EXCLUDED] 중
 * 정확히 한쪽에 있어야 하고 [DeployEnvPassthroughTest] 가 스캔으로 확인한다. 분류는 클래스 단위다.
 */
internal object DeployPassthrough {

    val PASSED: List<KClass<*>> = listOf(
        TradingProperties::class,
        ShadowExitProperties::class,
        WatchlistProperties::class,
        MarketDataWatchdogProperties::class,
    )

    val EXCLUDED: Map<KClass<*>, String> = mapOf(
        AppProperties::class to "비밀 값 — compose 가 `:?` 로 필수 전달한다",
        UpbitProperties::class to "거래소 주소 — 운영에서 바꾸지 않는다",
        DiscordProperties::class to "compose 가 값과 함께(`\${…:-}`) 따로 넘긴다",
        ErrorAlertProperties::class to "compose 가 값과 함께 따로 넘긴다 — enabled 오기는 preflight 가 보지 않는다",
    )

    /**
     * 티커 목록으로 검사할 String 프로퍼티. String 은 타입만으로 뜻이 정해지지 않아 손으로 분류하고,
     * 여기 없는 String 은 [ExitParamsPreflightScriptTest] 가 실패시킨다.
     */
    val TICKER_LIST: Set<KProperty1<*, String>> = setOf(TradingProperties::tickers, WatchlistProperties::tickers)

    fun prefix(klass: KClass<*>): String = klass.findAnnotation<ConfigurationProperties>()?.prefix
        ?: error("${klass.simpleName} 에 @ConfigurationProperties prefix 가 없다")

    /** 생성자 파라미터만 설정 입력이다. 파생 값은 env 로 주지 않는다. */
    fun parameters(klass: KClass<*>) = klass.primaryConstructor?.parameters
        ?: error("${klass.simpleName} 에 주 생성자가 없다")

    /** `trading.shadow-exit` + `trailingStopPct` → `TRADING_SHADOW_EXIT_TRAILING_STOP_PCT`. */
    fun envName(prefix: String, property: String): String {
        val head = prefix.replace('.', '_').replace('-', '_')
        val tail = property.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        return "${head}_$tail".uppercase()
    }

    fun envNames(klass: KClass<*>): List<String> = parameters(klass).mapNotNull { it.name }.map { envName(prefix(klass), it) }

    /** 전달 대상 키의 env 접두어. compose 의 다른 항목과 가르는 데 쓴다. */
    val ENV_PREFIXES: Set<String> = PASSED.map { envName(prefix(it), "x").removeSuffix("X") }.toSet()
}
