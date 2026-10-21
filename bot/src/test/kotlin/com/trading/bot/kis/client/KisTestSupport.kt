package com.trading.bot.kis.client

import com.trading.bot.kis.config.KisApiProperties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

internal val TEST_ACCESS = KisAccess("app-key-raw-value", "app-sig-raw-value", "12345678", "01")

/** 토큰을 차례로 내주고 무효화 요청을 기록한다. */
internal class FakeTokens(private vararg val values: String = arrayOf("tok-1", "tok-2")) : KisTokenSource {
    private var index = 0
    val invalidated = mutableListOf<String>()

    override suspend fun token(): String = values[index]

    override fun invalidate(rejected: String) {
        invalidated += rejected
        if (rejected == values[index] && index < values.lastIndex) index++
    }
}

internal fun kisJson(body: String, trCont: String? = null, status: Int = 200): MockResponse =
    MockResponse().setResponseCode(status).setBody(body).addHeader("Content-Type", "application/json; charset=UTF-8")
        .apply { if (trCont != null) addHeader("tr_cont", trCont) }

internal fun MockWebServer.next(): RecordedRequest = requireNotNull(takeRequest(5, TimeUnit.SECONDS)) { "요청이 오지 않았다" }

internal fun RecordedRequest.query(name: String): String? = requestUrl?.queryParameter(name)

internal fun MockWebServer.baseUrl(): String = url("/").toString().trimEnd('/')

/** 가상 시간으로 도는 간격 1ms 리미터. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.virtualLimiter(): KisRateLimiter =
    KisRateLimiter(minIntervalMs = 1, shortWaitLimitMs = 2_000, longWaitLimitMs = 30_000) { testScheduler.currentTime }

internal fun MockWebServer.kisApi(
    scope: TestScope,
    environment: KisEnvironment = KisEnvironment.PAPER,
    tokens: KisTokenSource = FakeTokens(),
    properties: KisApiProperties = KisApiProperties(),
    sleeps: MutableList<Long> = mutableListOf(),
    limiter: KisRateLimiter = scope.virtualLimiter(),
): KisApiImpl = KisApiImpl(
    environment = environment,
    access = TEST_ACCESS,
    webClient = KisClientRegistry.buildWebClient(baseUrl(), properties),
    tokens = tokens,
    limiter = limiter,
    properties = properties,
    sleep = { sleeps += it },
)
