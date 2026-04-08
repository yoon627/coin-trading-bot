package com.trading.bot.client

import com.trading.bot.domain.Account
import com.trading.bot.domain.Candle
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.Ticker
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration

@Component
class UpbitClient(
    private val upbitWebClient: WebClient,
    private val authProvider: UpbitAuthProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getAccounts(): List<Account> {
        return upbitWebClient.get()
            .uri("/v1/accounts")
            .header("Authorization", authProvider.authorizationHeader())
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it.statusCode()) }
            .bodyToMono<List<Account>>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    suspend fun getDayCandles(market: String, count: Int = 30): List<Candle> {
        return upbitWebClient.get()
            .uri("/v1/candles/days?market={market}&count={count}", market, count)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it.statusCode()) }
            .bodyToMono<List<Candle>>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    suspend fun getMinuteCandles(market: String, unit: Int = 1, count: Int = 200): List<Candle> {
        return upbitWebClient.get()
            .uri("/v1/candles/minutes/{unit}?market={market}&count={count}", unit, market, count)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it.statusCode()) }
            .bodyToMono<List<Candle>>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    suspend fun getTicker(markets: String): List<Ticker> {
        return upbitWebClient.get()
            .uri("/v1/ticker?markets={markets}", markets)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it.statusCode()) }
            .bodyToMono<List<Ticker>>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    suspend fun placeOrder(request: OrderRequest): Order {
        val queryString = request.toQueryString()
        return upbitWebClient.post()
            .uri("/v1/orders")
            .header("Authorization", authProvider.authorizationHeader(queryString))
            .bodyValue(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it.statusCode()) }
            .bodyToMono<Order>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    suspend fun getOrder(uuid: String): Order {
        val queryString = "uuid=$uuid"
        return upbitWebClient.get()
            .uri("/v1/order?uuid={uuid}", uuid)
            .header("Authorization", authProvider.authorizationHeader(queryString))
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it.statusCode()) }
            .bodyToMono<Order>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    suspend fun cancelOrder(uuid: String): Order {
        val queryString = "uuid=$uuid"
        return upbitWebClient.delete()
            .uri("/v1/order?uuid={uuid}", uuid)
            .header("Authorization", authProvider.authorizationHeader(queryString))
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it.statusCode()) }
            .bodyToMono<Order>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    private fun handleError(status: HttpStatusCode): Mono<Throwable> {
        return Mono.error(UpbitApiException("Upbit API error: $status"))
    }

    private fun <T> Mono<T>.retryOnRateLimit(): Mono<T> {
        return this.retryWhen(
            Retry.backoff(3, Duration.ofSeconds(1))
                .filter { it is UpbitApiException }
                .doBeforeRetry { log.warn("Retrying Upbit API call: attempt ${it.totalRetries() + 1}") }
        )
    }
}

class UpbitApiException(message: String) : RuntimeException(message)
