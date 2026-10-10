package com.trading.bot.config

import com.trading.common.config.ShadowExitProperties
import com.trading.common.config.TradingProperties
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 배포가 서버 `.env` → compose → 앱으로 **이름만** 넘기는 설정 클래스의 단일 목록.
 * [DeployEnvPassthroughTest]·[ExitParamsPreflightScriptTest] 가 같은 목록을 쓰고, 앱의 설정 클래스 전체가
 * [PASSED] 와 [EXCLUDED] 중 정확히 한쪽에 있는지는 [DeployEnvPassthroughTest] 가 스캔으로 확인한다.
 * 분류는 클래스 단위다 — 일부 키만 넘기는 클래스는 표현할 수 없다.
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

    /** 이 클래스들의 Long·Int 는 전부 양의 ms 다 — preflight 가 1 이상 정수만 받는다. TRADING_* 의 정수는 #271. */
    val POSITIVE_INTEGER_CLASSES: List<KClass<*>> = listOf(MarketDataWatchdogProperties::class)

    fun prefix(klass: KClass<*>): String = klass.findAnnotation<ConfigurationProperties>()?.prefix
        ?: error("${klass.simpleName} 에 @ConfigurationProperties prefix 가 없다")

    /** 생성자 파라미터 = 실제 설정 입력. 파생 캐시(private val 등)는 env 로 주는 값이 아니다. */
    fun parameters(klass: KClass<*>) = klass.primaryConstructor?.parameters
        ?: error("${klass.simpleName} 에 주 생성자가 없다")

    /** `trading.shadow-exit` + `trailingStopPct` → `TRADING_SHADOW_EXIT_TRAILING_STOP_PCT`. */
    fun envName(prefix: String, property: String): String {
        val head = prefix.replace('.', '_').replace('-', '_')
        val tail = property.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        return "${head}_$tail".uppercase()
    }

    fun envNames(klass: KClass<*>): List<String> = parameters(klass).mapNotNull { it.name }.map { envName(prefix(klass), it) }

    /** 전달 대상 키의 env 접두어(`TRADING_`·`WATCHLIST_`·`MARKETDATA_WATCHDOG_`) — compose 의 다른 항목과 가른다. */
    val ENV_PREFIXES: Set<String> = PASSED.map { envName(prefix(it), "x").removeSuffix("X") }.toSet()
}
