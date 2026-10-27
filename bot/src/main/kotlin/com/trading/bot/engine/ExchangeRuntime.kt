package com.trading.bot.engine

import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.exchange.FillAccounting
import com.trading.bot.exchange.FillPollPolicy
import com.trading.bot.exchange.MarketRules
import com.trading.bot.exchange.OrderErrorClassifier
import com.trading.bot.exchange.OrderGate
import com.trading.bot.exchange.TradingCalendar
import com.trading.bot.exchange.UnknownOrderPolicy
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.entity.UserEntity
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import java.time.Duration

/** 한 사용자의 엔진을 만들 거래소 부품. 조립은 [UserTradingManager.createEngine] 한 곳에서 한다. */
data class ExchangeParts(
    val exchange: Exchange,
    val client: ExchangeClient,
    val rules: MarketRules,
    val calendar: TradingCalendar,
    val orderGate: OrderGate,
    val fillPoll: FillPollPolicy,
    val fillAccounting: FillAccounting,
    val errorClassifier: OrderErrorClassifier,
    val unknownOrderPolicy: UnknownOrderPolicy,
    val profile: TradingProperties,
    val marketDataStore: MarketDataStore?,
    val dailyCandleCache: DailyCandleCache?,
    /** 운영 안내 문구에 넣는 거래소 이름. */
    val displayName: String,
)

/**
 * 매니저가 거래소마다 달리 하는 것. 자격증명의 존재 판정과 클라이언트 서명은 runtime 이 한다.
 * 복호화는 아직 매니저가 [com.trading.bot.security.UserSecretsService] 로 Upbit 필드만 한다.
 */
interface ExchangeRuntime {
    /** bot_state 의 exchange 칸에 [Exchange.name] 으로 쓴다. */
    val exchange: Exchange

    /** 매니저의 자동 복원 여부·기본 티커와 엔진 설정의 단일 출처. [ExchangeParts.profile] 과 같은 값이어야 한다. */
    val profile: TradingProperties

    /**
     * 매니저 정지(엔진 정지·정지 저장)의 시간 예산. 매니저는 동기 `stop()` 이라 Spring `timeout-per-shutdown-phase` 가
     * 끊지 못하고 이 값이 상한이다. 예산을 넘기면 `stop()` 은 돌아오지만 진행 중인 주문 구간(NonCancellable)은 계속 돈다.
     * 컨테이너 `stop_grace_period` 는 이 값을 포함한 정지 상한 합보다 길어야 한다(`ShutdownBudgetTest`).
     */
    val stopBudget: Duration

    /** 매니저의 SmartLifecycle phase. 같은 phase 의 매니저는 차례로 멈춰 정지 예산이 더해진다. */
    val lifecyclePhase: Int get() = ENGINE_LIFECYCLE_PHASE

    companion object {
        /**
         * 웹 서버 정지(`WebServerStartStopLifecycle`, MAX-2048)보다 낮아 진행 중인 웹 요청이 끝난 뒤 멈추고,
         * 공유 HTTP 자원을 닫는 `ReactorResourceFactory`(phase 0)보다 높아 그보다 먼저 멈춘다. 같은 phase 안의 순서는
         * 계약이 없어 0 을 쓰지 않는다. 예산 안에 정지가 끝난 엔진만 이 순서의 보호를 받는다.
         */
        const val ENGINE_LIFECYCLE_PHASE: Int = 100
    }

    /** 수동 시작에 필요한 자격증명이 저장돼 있는지. 복호화 전 값으로 본다. */
    fun canStart(user: UserEntity): Boolean

    /** 기동 복원에 필요한 자격증명이 저장돼 있는지. 복호화 전 값으로 본다. */
    fun canRestore(user: UserEntity): Boolean

    /** [user] 는 복호화된 값이다. */
    fun partsFor(user: UserEntity): ExchangeParts

    /** 계좌·포트폴리오 조회용. [user] 는 복호화된 값이다. */
    fun queryClient(user: UserEntity): ExchangeClient
}
