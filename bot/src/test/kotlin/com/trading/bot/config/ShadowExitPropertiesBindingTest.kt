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
 * **환경변수 이름이 실제로 바인딩되는지**를 실측한다 — 추측하면 안 되는 지점이다.
 *
 * 이 repo 는 배포 계층이 앱 기본값을 덮는 구조라(`docker-compose.prod.yml` 이 `TRADING_*` 를 이름으로
 * 나열해 전달, #75) **env 이름이 틀리면 조용히 아무 일도 일어나지 않는다**. 켰다고 믿는데 안 켜진 상태가
 * 가장 나쁘다 — 관측이 0건인지 설정이 안 먹은 건지 구분할 수 없다.
 *
 * Spring 의 relaxed binding 은 `_` 를 `.` 로도 `-` 로도 볼 수 있어 `trading.shadow-exit.enabled` 에
 * 어떤 env 이름이 맞는지는 문서만으로 확정할 수 없다. 그래서 **실제 `SystemEnvironmentPropertySource` 로 건다.**
 */
class ShadowExitPropertiesBindingTest {

    private fun bindFromEnv(vararg pairs: Pair<String, String>): ShadowExitProperties {
        // 운영과 같은 경로: OS 환경변수 소스. MapPropertySource 로 하면 relaxed 규칙이 달라 검증이 무의미하다.
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
