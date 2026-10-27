package com.trading.bot.engine

import com.trading.common.domain.Exchange
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.HttpCodeStatusMapper
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * deploy.sh 는 `/actuator/health` 가 200 이 될 때까지 기다리고, 끝내 아니면 배포 실패로 롤백한다. 복원이 끝나기 전과
 * 실패한 뒤에는 503 이어야 한다. actuator 자동 설정으로 실제 health 응답에 indicator 가 들어가는지까지 본다.
 */
class BotRestoreHealthIndicatorTest {

    private fun manager(exchange: Exchange, status: RestoreStatus): UserTradingManager = mockk {
        every { this@mockk.exchange } returns exchange
        every { restoreStatus } returns status
    }

    private fun runner(vararg managers: Pair<Exchange, RestoreStatus>) = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EndpointAutoConfiguration::class.java,
                HealthContributorAutoConfiguration::class.java,
                HealthEndpointAutoConfiguration::class.java,
            ),
        )
        .withBean(TradingManagers::class.java, { TradingManagers.of(*managers.map { (e, s) -> e to manager(e, s) }.toTypedArray()) })
        .withUserConfiguration(BotRestoreHealthIndicator::class.java)

    private fun httpCode(vararg managers: Pair<Exchange, RestoreStatus>): Int {
        var code = 0
        runner(*managers).run { context ->
            val endpoint = context.getBean(HealthEndpoint::class.java)
            assertThat(endpoint.healthForPath("botRestore")).describedAs("indicator 가 health 에 등록되지 않았다").isNotNull()
            code = context.getBean(HttpCodeStatusMapper::class.java).getStatusCode(endpoint.health().status)
        }
        return code
    }

    @ParameterizedTest
    @EnumSource(RestoreStatus::class, names = ["NOT_STARTED", "IN_PROGRESS", "FAILED"])
    fun `복원이 끝나기 전이나 실패한 뒤에는 503 이다`(status: RestoreStatus) {
        assertThat(httpCode(Exchange.UPBIT to status)).isEqualTo(503)
    }

    @ParameterizedTest
    @EnumSource(RestoreStatus::class, names = ["DONE", "DISABLED"])
    fun `복원이 끝났거나 자동 시작이 꺼져 있으면 200 이다`(status: RestoreStatus) {
        assertThat(httpCode(Exchange.UPBIT to status)).isEqualTo(200)
    }

    @Test
    fun `한 거래소라도 복원에 실패하면 503 이다`() {
        assertThat(httpCode(Exchange.UPBIT to RestoreStatus.DONE, Exchange.KIS_PAPER to RestoreStatus.FAILED)).isEqualTo(503)
    }
}
