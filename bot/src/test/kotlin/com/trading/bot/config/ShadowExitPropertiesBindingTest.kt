package com.trading.bot.config

import com.trading.common.config.ShadowExitProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * 배포는 `TRADING_*` 를 이름으로 나열해 넘기므로 env 이름이 틀리면 설정이 조용히 먹지 않는다.
 * relaxed binding 은 `_` 를 `.` 로도 `-` 로도 볼 수 있어 실제 `SystemEnvironmentPropertySource` 로 확인한다.
 */
class ShadowExitPropertiesBindingTest {

    private fun bindFromEnv(vararg pairs: Pair<String, String>): ShadowExitProperties {
        // MapPropertySource 는 relaxed 규칙이 달라 운영과 같은 OS 환경변수 소스를 쓴다.
        val env = StandardEnvironment()
        env.propertySources.addFirst(
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                pairs.toMap(),
            ),
        )
        val binder = Binder(ConfigurationPropertySources.get(env))
        return binder.bind("trading.shadow-exit", ShadowExitProperties::class.java)
            .orElse(ShadowExitProperties())
    }

    @Test
    fun `default is off so the observer never starts by accident`() {
        assertFalse(ShadowExitProperties().enabled)
    }

    @Test
    fun `TRADING_SHADOW_EXIT_ prefix binds - this is the name deploy must pass`() {
        val p = bindFromEnv(
            "TRADING_SHADOW_EXIT_ENABLED" to "true",
            "TRADING_SHADOW_EXIT_TRAILING_STOP_PCT" to "1.5",
            "TRADING_SHADOW_EXIT_TRAILING_ARM_PCT" to "0",
        )
        assertTrue(p.enabled) { "TRADING_SHADOW_EXIT_ENABLED 가 바인딩되지 않는다 — compose 목록에 넣어도 무효다" }
        assertEquals(1.5, p.trailingStopPct)
        assertEquals(0.0, p.trailingArmPct)
    }

    @Test
    fun `unset environment leaves the defaults intact`() {
        val p = bindFromEnv("SOMETHING_ELSE" to "1")
        assertFalse(p.enabled)
        assertEquals(1.5, p.trailingStopPct)
    }
}
