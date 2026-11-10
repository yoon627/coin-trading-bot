package com.trading.bot.engine

import com.trading.bot.notification.TradeBalanceLookup
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.domain.Exchange
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Component

/** 그 거래소 매니저의 조회 클라이언트로 원화 잔고를 읽는다. 매니저가 없거나 조회가 실패·지연되면 null. */
@Component
class ExchangeBalanceLookup(
    private val managers: TradingManagers,
    private val userSecretsService: UserSecretsService,
) : TradeBalanceLookup {

    override suspend fun krwBalance(user: UserEntity, exchange: String): Double? =
        try {
            val manager = managers.find(Exchange.valueOf(exchange)) ?: return null
            withTimeoutOrNull(TIMEOUT.toMillis()) {
                manager.queryClient(userSecretsService.decryptUserSecrets(user)).getAccounts()
                    .find { it.currency == "KRW" }?.balanceDouble()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
