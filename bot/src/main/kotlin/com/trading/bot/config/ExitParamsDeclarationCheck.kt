package com.trading.bot.config

import com.trading.common.config.TradingProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * 청산 파라미터가 **선언되지 않은 채** 거래하는 상태를 드러낸다 (#179).
 *
 * 운영 청산값은 `TRADING_*` env 가 소유하고 [TradingProperties] 의 코드 기본값과 다를 수 있다
 * (2026-09-06~ 트레일링 1.5/0 vs 코드 2.0/3.0 — wiki `query/trailing-arm-finding-2026-09`).
 * 배포 시크릿에서 한 줄이 빠지면 봇이 **조용히** 다른 청산 규칙으로 거래한다. 그 침묵이 문제다.
 *
 * **기동이나 거래를 막지 않는다.** 막으면 보유 포지션의 손절·트레일링·보유상한이 전부 평가되지 않는
 * 공백이 생기는데, 그 손해가 파라미터 차이보다 크다. 실제 차단은 배포 앞단(`deploy/vultr/deploy.sh`
 * 의 preflight)이 맡는다 — 결손 `.env` 가 서버에 닿기 전에 배포를 멈춘다. 여기는 그 그물을 빠져나온
 * 경우(수동 `.env` 편집 등)를 위한 **탐지**다.
 */
@Component
class ExitParamsDeclarationCheck(
    private val tradingProperties: TradingProperties,
    private val environment: Environment,
) {

    /** 환경에 선언되지 않아 코드 기본값으로 떨어진 청산 키. 비어 있으면 정상이다. */
    val undeclaredKeys: List<String> by lazy {
        REQUIRED_KEYS.filterNot { environment.containsProperty(it) }
    }

    @PostConstruct
    fun reportOnStartup() = report("기동")

    /**
     * 거래를 시작하는 지점에서 부른다. `autoStart` 는 기동 시 복원 여부만 정할 뿐 거래 시작 조건이
     * 아니라서(`/api/bot/start` 가 그것을 보지 않는다) 기동 시점 보고만으로는 수동 기동을 놓친다.
     */
    fun report(context: String) {
        log.info(
            "[{}] 청산 파라미터 실효값: 익절 {}% / 손절 {}% / 트레일링 {}%(arm {}%) / 보유상한 {}일 — 명시 {}/{} 키",
            context, tradingProperties.takeProfitPct, tradingProperties.maxLossPct,
            tradingProperties.trailingStopPct, tradingProperties.trailingArmPct,
            tradingProperties.maxHoldDays, REQUIRED_KEYS.size - undeclaredKeys.size, REQUIRED_KEYS.size,
        )
        if (undeclaredKeys.isEmpty()) return
        // ERROR 라야 Discord 로 나간다(DiscordErrorLogAppender). 거래는 계속되므로 알림이 유일한 탐지 수단이다.
        log.error(
            "[{}] 청산 파라미터 {} 가 환경에 선언되지 않아 코드 기본값으로 거래합니다 — 운영값과 다를 수 있습니다. " +
                "배포 환경(.env·VULTR_DEPLOY_ENV 시크릿)에 해당 키를 넣으세요.",
            context, undeclaredKeys,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExitParamsDeclarationCheck::class.java)

        /**
         * 청산 판정을 직접 바꾸는 프로퍼티. 이름은 [TradingProperties] 생성자에서 **파생**한다 —
         * 문자열로 적으면 rename 시 옛 키가 계속 선언돼 있어 검사가 조용히 무력해진다
         * (`TradingEnvPassthroughTest` 가 같은 이유로 리플렉션을 쓴다).
         */
        private val REQUIRED_PROPERTIES = listOf(
            TradingProperties::takeProfitPct,
            TradingProperties::maxLossPct,
            TradingProperties::trailingStopPct,
            TradingProperties::trailingArmPct,
            TradingProperties::maxHoldDays,
            TradingProperties::chartExitEnabled,
        )

        val REQUIRED_KEYS: List<String> =
            REQUIRED_PROPERTIES.map { "trading." + it.name.replace(Regex("([A-Z])"), "-$1").lowercase() }
    }
}
