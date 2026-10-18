package com.trading.bot.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.Ticker
import com.trading.common.domain.Candle
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import reactor.util.retry.Retry

class UpbitClientImpl(
    private val upbitWebClient: WebClient,
    private val authProvider: UpbitAuthProvider?,
    private val retryBackoffBase: Duration = Duration.ofSeconds(1),
) : UpbitClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun getAccounts(): List<Account> {
        return Mono.defer {
            upbitWebClient.get()
                .uri("/v1/accounts")
                .header("Authorization", authProvider!!.authorizationHeader())
                .retrieve()
                .onStatus(HttpStatusCode::isError) { handleError(it) }
                .bodyToMono<List<Account>>()
        }.retryOnRateLimit().awaitSingle()
    }

    override suspend fun getDayCandles(market: String, count: Int): List<Candle> {
        return upbitWebClient.get()
            .uri("/v1/candles/days?market={market}&count={count}", market, count)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it) }
            .bodyToMono<List<Candle>>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    override suspend fun getMinuteCandles(market: String, unit: Int, count: Int): List<Candle> {
        return upbitWebClient.get()
            .uri("/v1/candles/minutes/{unit}?market={market}&count={count}", unit, market, count)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it) }
            .bodyToMono<List<Candle>>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    override suspend fun getTicker(markets: String): List<Ticker> {
        return upbitWebClient.get()
            .uri("/v1/ticker?markets={markets}", markets)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it) }
            .bodyToMono<List<Ticker>>()
            .retryOnRateLimit()
            .awaitSingle()
    }

    override suspend fun placeOrder(request: OrderRequest): Order {
        val params = request.toParamMap()
        val queryString = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, StandardCharsets.UTF_8)}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}"
        }
        // 주문 생성은 멱등이 아니라 재시도하면 중복 주문이 날 수 있다. 접수 여부는 호출부가 identifier 로 확인한다.
        return upbitWebClient.post()
            .uri("/v1/orders")
            .header("Authorization", authProvider!!.authorizationHeader(queryString))
            .bodyValue(params)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { handleError(it) }
            .bodyToMono<Order>()
            .awaitSingle()
    }

    override suspend fun getOrder(uuid: String): Order {
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

    override suspend fun getOrderByIdentifier(identifier: String): Order? {
        val queryString = "identifier=$identifier"
        return try {
            Mono.defer {
                upbitWebClient.get()
                    .uri("/v1/order?identifier={identifier}", identifier)
                    .header("Authorization", authProvider!!.authorizationHeader(queryString))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError) { handleError(it, expected = ::isOrderNotFound) }
                    .bodyToMono<Order>()
            }.retryOnRateLimit().awaitSingle()
        } catch (e: UpbitApiException) {
            if (isOrderNotFound(e)) null else throw e
        }
    }

    private fun isOrderNotFound(e: UpbitApiException) = e.statusCode == 404 && e.errorName == "order_not_found"

    override suspend fun cancelOrder(uuid: String): Order {
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

    // ERROR 는 Discord 로 나가므로 호출자가 정상으로 다루는 오류(expected)는 INFO 로 남긴다.
    private fun handleError(
        response: ClientResponse,
        expected: (UpbitApiException) -> Boolean = { false },
    ): Mono<Throwable> {
        val statusValue = response.statusCode().value()
        return response.bodyToMono<String>().defaultIfEmpty("(no body)").map { body ->
            val (errorName, errorMessage) = parseUpbitErrorBody(body)
            UpbitApiException(statusValue, errorName, errorMessage, body).also {
                if (expected(it)) {
                    log.info("Upbit API: {} - {}", response.statusCode(), body)
                } else {
                    log.error("Upbit API error: {} - {}", response.statusCode(), body)
                }
            }
        }
    }

    private fun parseUpbitErrorBody(body: String): Pair<String?, String?> {
        return try {
            val root = ERROR_BODY_MAPPER.readTree(body)
            val error = root.get("error") ?: return null to null
            error.get("name")?.asText() to error.get("message")?.asText()
        } catch (_: Exception) {
            null to null
        }
    }

    private fun <T> Mono<T>.retryOnRateLimit(): Mono<T> {
        return this.retryWhen(
            Retry.backoff(2, retryBackoffBase)
                .filter { it is UpbitApiException && it.statusCode == 429 }
                .doBeforeRetry { log.warn("Retrying Upbit API call (rate limit): attempt {}", it.totalRetries() + 1) }
                // 기본값은 retry-exhausted 예외로 감싸 500 이 되므로 원래 예외를 던져 429 로 매핑되게 한다.
                .onRetryExhaustedThrow { _, signal -> signal.failure() }
        )
    }

    companion object {
        private val ERROR_BODY_MAPPER = ObjectMapper()
    }
}
