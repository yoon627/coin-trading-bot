package com.trading.bot.engine

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * 배포는 `/actuator/health` 가 200 이 될 때까지 기다리고 끝내 아니면 롤백한다. 복원이 끝나기 전에는 OUT_OF_SERVICE,
 * 실패하면 DOWN(둘 다 503)이라, 봇이 다시 뜨지 않은 배포가 성공으로 기록되지 않는다. 한 컨테이너가 모든 거래소 엔진을
 * 돌리므로 어느 거래소든 실패하면 DOWN 이다.
 */
@Component
class BotRestoreHealthIndicator(private val managers: TradingManagers) : HealthIndicator {
    override fun health(): Health {
        val statuses = managers.all().associate { it.exchange.name to it.restoreStatus }
        val builder = when {
            RestoreStatus.FAILED in statuses.values -> Health.down()
            statuses.values.any { it == RestoreStatus.NOT_STARTED || it == RestoreStatus.IN_PROGRESS } -> Health.outOfService()
            else -> Health.up()
        }
        return builder.withDetails(statuses.mapValues { it.value.name }).build()
    }
}
