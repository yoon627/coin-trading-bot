package com.trading.bot.config

import com.trading.common.config.PriceEventsProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/** 비상 스위치라 배포가 넘기는 env 이름으로 실제로 꺼지는지 운영과 같은 OS 환경변수 소스로 확인한다. */
class PriceEventsPropertiesBindingTest {

    private fun bindFromEnv(vararg pairs: Pair<String, String>): PriceEventsProperties {
        val env = StandardEnvironment()
        env.propertySources.addFirst(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, pairs.toMap()),
        )
        return Binder(ConfigurationPropertySources.get(env))
            .bind("trading.price-events", PriceEventsProperties::class.java)
            .orElse(PriceEventsProperties())
    }

    @Test
    fun `TRADING_PRICE_EVENTS_ENABLED=false turns trade subscription off - this is the name deploy must pass`() {
        assertFalse(bindFromEnv("TRADING_PRICE_EVENTS_ENABLED" to "false").enabled) {
            "TRADING_PRICE_EVENTS_ENABLED 가 바인딩되지 않는다 — compose 목록에 넣어도 끌 수 없다"
        }
    }

    @Test
    fun `unset environment keeps trades on`() {
        assertTrue(bindFromEnv("SOMETHING_ELSE" to "1").enabled)
    }
}
