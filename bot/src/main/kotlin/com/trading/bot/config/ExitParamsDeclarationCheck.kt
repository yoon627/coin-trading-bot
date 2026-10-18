package com.trading.bot.config

import com.trading.common.config.ExitParamRanges
import com.trading.common.config.ShadowExitProperties
import com.trading.common.config.TradingProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * 청산 파라미터가 env 에 선언되지 않아 코드 기본값으로 떨어졌거나 의미상 구간 밖이면 ERROR 로 알린다.
 * 기동·거래는 막지 않는다. 막으면 보유 포지션의 손절·트레일링이 평가되지 않아 그쪽 손해가 더 크고,
 * 실제 차단은 배포 단계의 preflight_exit_params.sh 가 맡는다.
 */
@Component
class ExitParamsDeclarationCheck(
    private val tradingProperties: TradingProperties,
    private val environment: Environment,
    private val shadowExitProperties: ShadowExitProperties = ShadowExitProperties(),
) {

    val undeclaredKeys: List<String> by lazy {
        REQUIRED_KEYS.filterNot { environment.containsProperty(it) }
    }

    // Discord appender 가 붙은 뒤, 포지션 복원보다 앞서 돌아야 거래 재개 전에 ERROR 가 Discord 로 나간다.
    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    fun reportOnStartup() = report("기동")

    // 수동 시작은 기동 시점 보고를 거치지 않으므로 거래를 시작하는 곳에서도 부른다.
    fun report(context: String) {
        try {
            log.info(
                "[{}] 청산 파라미터 실효값: 익절 {}% / 손절 {}% / 트레일링 {}%(arm {}%) / 보유상한 {}일 — 명시 {}/{} 키",
                context, tradingProperties.takeProfitPct, tradingProperties.maxLossPct,
                tradingProperties.trailingStopPct, tradingProperties.trailingArmPct,
                tradingProperties.maxHoldDays, REQUIRED_KEYS.size - undeclaredKeys.size, REQUIRED_KEYS.size,
            )
            // 거래는 계속되므로 Discord 로 나가는 ERROR 가 유일한 탐지 수단이다.
            val outOfRange = ExitParamRanges.violations(tradingProperties)
            if (outOfRange.isNotEmpty()) {
                log.error(
                    "[{}] 청산·주문 파라미터가 의미상 구간 밖입니다: {} — 이 값으로 거래합니다. 이 값으로 연 포지션은 청산될 때까지 " +
                        "진입 시점 값을 유지합니다. 배포 환경(.env·VULTR_DEPLOY_ENV 시크릿)을 고치세요.",
                    context, outOfRange,
                )
            }
            val shadowOutOfRange = if (shadowExitProperties.enabled) ExitParamRanges.violations(shadowExitProperties) else emptyList()
            if (shadowOutOfRange.isNotEmpty()) {
                log.error(
                    "[{}] 그림자 관측 설정이 구간 밖이라 관측을 끕니다: {} — 거래에는 영향이 없습니다. 배포 환경(.env·VULTR_DEPLOY_ENV 시크릿)을 고치세요.",
                    context, shadowOutOfRange,
                )
            }
            if (undeclaredKeys.isNotEmpty()) {
                log.error(
                    "[{}] 청산 파라미터 {} 가 환경에 선언되지 않아 코드 기본값으로 거래합니다 — 운영값과 다를 수 있습니다. " +
                        "배포 환경(.env·VULTR_DEPLOY_ENV 시크릿)에 해당 키를 넣으세요.",
                    context, undeclaredKeys,
                )
            }
        } catch (e: Exception) {
            log.error("[{}] 청산 파라미터 점검에 실패했습니다 — 거래는 막지 않습니다", context, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExitParamsDeclarationCheck::class.java)

        // 키를 문자열로 적으면 프로퍼티 rename 때 옛 키가 남아 검사가 조용히 무력해져 프로퍼티에서 파생한다.
        private val REQUIRED_PROPERTIES = listOf(
            TradingProperties::takeProfitPct,
            TradingProperties::maxLossPct,
            TradingProperties::trailingStopPct,
            TradingProperties::trailingArmPct,
            TradingProperties::maxHoldDays,
        )

        val REQUIRED_KEYS: List<String> =
            REQUIRED_PROPERTIES.map { "trading." + it.name.replace(Regex("([A-Z])"), "-$1").lowercase() }
    }
}
