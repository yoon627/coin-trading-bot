package com.trading.bot.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/** 시세판 연결을 끄는 스위치가 배포가 넘기는 env 이름으로 실제로 꺼지는지 운영과 같은 OS 환경변수 소스로 확인한다. */
class MarketBoardPropertiesBindingTest {

    private fun bindFromEnv(vararg pairs: Pair<String, String>): MarketBoardProperties {
        val env = StandardEnvironment()
        env.propertySources.addFirst(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, pairs.toMap()),
        )
        return Binder(ConfigurationPropertySources.get(env))
            .bind("marketdata.board", MarketBoardProperties::class.java)
            .orElse(MarketBoardProperties())
    }

    @Test
    fun `MARKETDATA_BOARD_ENABLED=false turns the board connection off`() {
        assertFalse(bindFromEnv("MARKETDATA_BOARD_ENABLED" to "false").enabled)
    }

    @Test
    fun `unset environment keeps the board on`() {
        assertTrue(bindFromEnv("SOMETHING_ELSE" to "1").enabled)
    }
}
