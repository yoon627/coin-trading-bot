package com.trading.bot.engine

import com.trading.bot.client.UpbitAuthProvider
import com.trading.bot.client.UpbitClient
import com.trading.bot.client.UpbitClientImpl
import com.trading.bot.client.UpbitOrderErrorClassifier
import com.trading.bot.exchange.FillPollPolicy
import com.trading.bot.exchange.OrderGate
import com.trading.bot.exchange.UnknownOrderPolicy
import com.trading.bot.exchange.UpbitMarketRules
import com.trading.bot.exchange.UpbitTradingCalendar
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.entity.UserEntity
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import java.time.Duration
import org.springframework.web.reactive.function.client.WebClient

class UpbitExchangeRuntime(
    private val upbitWebClient: WebClient,
    override val profile: TradingProperties,
    private val marketDataStore: MarketDataStore?,
    private val dailyCandleCache: DailyCandleCache?,
) : ExchangeRuntime {
    override val exchange: Exchange = Exchange.UPBIT

    override val stopBudget: Duration = Duration.ofSeconds(25)

    override fun canStart(user: UserEntity): Boolean =
        !user.upbitAccessKey.isNullOrBlank() && !user.upbitSecretKey.isNullOrBlank()

    override fun canRestore(user: UserEntity): Boolean = !user.upbitAccessKey.isNullOrBlank()

    override fun partsFor(user: UserEntity): ExchangeParts = partsFor(queryClient(user))

    fun partsFor(client: UpbitClient): ExchangeParts = ExchangeParts(
        exchange = exchange,
        client = client,
        rules = UpbitMarketRules,
        calendar = UpbitTradingCalendar,
        orderGate = OrderGate.OPEN,
        fillPoll = FillPollPolicy.UPBIT,
        fillAccounting = BalanceFillAccounting(client),
        errorClassifier = UpbitOrderErrorClassifier,
        unknownOrderPolicy = UnknownOrderPolicy.UPBIT,
        profile = profile,
        marketDataStore = marketDataStore,
        dailyCandleCache = dailyCandleCache,
        displayName = "Upbit",
    )

    override fun queryClient(user: UserEntity): UpbitClient {
        val authProvider = UpbitAuthProvider(
            accessKey = user.upbitAccessKey ?: "",
            secretKey = user.upbitSecretKey ?: "",
        )
        return UpbitClientImpl(upbitWebClient, authProvider)
    }
}
