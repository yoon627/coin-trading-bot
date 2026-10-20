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

    /** 매니저 정지에 쓰는 시간 예산. Spring timeout-per-shutdown-phase 보다 짧아야 정지 저장이 끊기지 않는다. */
    val stopBudget: Duration

    val lifecyclePhase: Int

    /** 수동 시작에 필요한 자격증명이 저장돼 있는지. 복호화 전 값으로 본다. */
    fun canStart(user: UserEntity): Boolean

    /** 기동 복원에 필요한 자격증명이 저장돼 있는지. 복호화 전 값으로 본다. */
    fun canRestore(user: UserEntity): Boolean

    /** [user] 는 복호화된 값이다. */
    fun partsFor(user: UserEntity): ExchangeParts

    /** 계좌·포트폴리오 조회용. [user] 는 복호화된 값이다. */
    fun queryClient(user: UserEntity): ExchangeClient
}
