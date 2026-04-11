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
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration

@Component
class UpbitClient(
    private val upbitWebClient: WebClient,
    private val authProvider: UpbitAuthProvider?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getAccounts(): List<Account> {
        return Mono.defer {
            upbitWebClient.get()
                .uri("/v1/accounts")
                .header("Authorization", authProvider!!.authorizationHeader())
                .retrieve()
                .onStatus(HttpStatusCode::isError) { handleError(it) }
                .bodyToMono<List<Account>>()
        }.retryOnRateLimit().awaitSingle()
    }

    suspend fun getDayCandles(market: String, count: Int = 30): List<Candle> {
        return upbitWebClient.get()
            .uri("/v1/candles/days?market={market}&count={count}", market, count)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it) }
            .bodyToMono<List<Candle>>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    suspend fun getMinuteCandles(market: String, unit: Int = 1, count: Int = 200): List<Candle> {
        return upbitWebClient.get()
            .uri("/v1/candles/minutes/{unit}?market={market}&count={count}", unit, market, count)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it) }
            .bodyToMono<List<Candle>>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    suspend fun getTicker(markets: String): List<Ticker> {
        return upbitWebClient.get()
            .uri("/v1/ticker?markets={markets}", markets)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it) }
            .bodyToMono<List<Ticker>>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    suspend fun placeOrder(request: OrderRequest): Order {
        val params = request.toParamMap()
        val queryString = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        return Mono.defer {
            upbitWebClient.post()
                .uri("/v1/orders")
                .header("Authorization", authProvider!!.authorizationHeader(queryString))
                .bodyValue(params)
                .retrieve()
                .onStatus(HttpStatusCode::isError) { handleError(it) }
                .bodyToMono<Order>()
        }.retryOnRateLimit().awaitSingle()
    }

    suspend fun getOrder(uuid: String): Order {
        val queryString = "uuid=$uuid"
        return Mono.defer {
            upbitWebClient.get()
                .uri("/v1/order?uuid={uuid}", uuid)
                .header("Authorization", authProvider!!.authorizationHeader(queryString))
                .retrieve()
                .onStatus(HttpStatusCode::isError) { handleError(it) }
                .bodyToMono<Order>()
        }.retryOnRateLimit().awaitSingle()
    }

    suspend fun cancelOrder(uuid: String): Order {
        val queryString = "uuid=$uuid"
        return Mono.defer {
            upbitWebClient.delete()
                .uri("/v1/order?uuid={uuid}", uuid)
                .header("Authorization", authProvider!!.authorizationHeader(queryString))
                .retrieve()
                .onStatus(HttpStatusCode::isError) { handleError(it) }
                .bodyToMono<Order>()
        }.retryOnRateLimit().awaitSingle()
    }

    private fun handleError(response: ClientResponse): Mono<Throwable> {
        return response.bodyToMono<String>().defaultIfEmpty("(no body)").map { body ->
            log.error("Upbit API error: {} - {}", response.statusCode(), body)
            UpbitApiException("Upbit API error: ${response.statusCode()} - $body")
        }
    }

    private fun <T> Mono<T>.retryOnRateLimit(): Mono<T> {
        return this.retryWhen(
            Retry.backoff(2, Duration.ofSeconds(1))
                .filter { it is UpbitApiException && "429" in it.message.orEmpty() }
                .doBeforeRetry { log.warn("Retrying Upbit API call (rate limit): attempt ${it.totalRetries() + 1}") }
        )
    }
}

class UpbitApiException(message: String) : RuntimeException(message)
