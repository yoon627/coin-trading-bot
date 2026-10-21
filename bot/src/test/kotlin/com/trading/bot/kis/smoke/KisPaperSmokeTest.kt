package com.trading.bot.kis.smoke

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.kis.client.KisAccess
import com.trading.bot.kis.client.KisApi
import com.trading.bot.kis.client.KisCashOrderRequest
import com.trading.bot.kis.client.KisClientRegistry
import com.trading.bot.kis.client.KisEnvironment
import com.trading.bot.kis.client.KisException
import com.trading.bot.kis.client.KisLane
import com.trading.bot.kis.client.KisOrderAck
import com.trading.bot.kis.client.KisOrderRow
import com.trading.bot.kis.client.KisOrderType
import com.trading.bot.kis.client.KisRejectedException
import com.trading.bot.kis.client.KisSide
import com.trading.bot.kis.config.KisApiProperties
import com.trading.bot.kis.config.KisTradingProperties
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient

/**
 * 모의계좌 실호출 관측. `KIS_SMOKE=true` 일 때만 돈다 — CI·일반 빌드에서는 건너뛴다.
 *
 * 입력: KIS_SMOKE_APP_KEY, KIS_SMOKE_APP_SECRET, KIS_SMOKE_ACCOUNT("12345678-01"), KIS_SMOKE_SYMBOL(기본 005930),
 * KIS_SMOKE_BURST(리미터를 거치지 않는 현재가 GET 5회), KIS_SMOKE_PLACE(1주 시장가 매수·매도).
 * 결과는 표준 출력과 `build/kis-smoke-report.txt` 에 남긴다. 키·토큰·계좌번호는 원문으로 남기지 않는다.
 * 키가 셸 기록에 남지 않도록 본인 터미널에서 env 를 넣고 `./gradlew :bot:test --tests '*KisPaperSmokeTest*'` 로 돌린다.
 */
@EnabledIfEnvironmentVariable(named = "KIS_SMOKE", matches = "true")
class KisPaperSmokeTest {

    private val kst = ZoneId.of("Asia/Seoul")
    private val symbol = System.getenv("KIS_SMOKE_SYMBOL")?.takeIf { it.isNotBlank() } ?: "005930"
    private val report = mutableListOf<String>()
    private val failedSteps = mutableListOf<String>()

    /**
     * Spring 컨텍스트 없이 돌아 로그 레벨이 classpath 의 logback 기본 설정에만 달려 있다.
     * HTTP 계층이 DEBUG 가 되면 요청 쿼리(CANO)와 본문(appkey·appsecret)을 찍으므로 INFO 로 고정한다.
     */
    private fun quietHttpLogs() {
        listOf(org.slf4j.Logger.ROOT_LOGGER_NAME, "org.springframework.web.reactive.function.client", "org.springframework.http.codec", "reactor.netty")
            .forEach { (LoggerFactory.getLogger(it) as Logger).level = Level.INFO }
    }

    private fun env(name: String): String = requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) { "$name 환경변수가 필요하다" }

    private fun flag(name: String): Boolean = System.getenv(name).equals("true", ignoreCase = true)

    private fun line(text: String) {
        println("[KIS smoke] $text")
        report += text
    }

    private fun describe(e: Throwable): String = when (e) {
        is KisRejectedException -> "${e.javaClass.simpleName} http=${e.httpStatus} rt_cd=${e.rtCd} msg_cd=${e.msgCd} msg1=${e.msg1}"
        is KisException -> "${e.javaClass.simpleName} http=${e.httpStatus} msg_cd=${e.msgCd} msg1=${e.msg1} reason=${e.reason}"
        else -> e.javaClass.simpleName
    }

    private suspend fun <T> step(name: String, core: Boolean = false, summary: (T) -> String, block: suspend () -> T): T? {
        val started = System.nanoTime()
        return try {
            block().also { line("$name OK ${elapsedMs(started)}ms ${summary(it)}") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            line("$name FAIL ${elapsedMs(started)}ms ${describe(e)}")
            if (core) failedSteps += name
            null
        }
    }

    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    @Test
    fun `모의계좌 조회·유량·주문 관측`() = runBlocking {
        quietHttpLogs()
        val access = KisAccess.of(env("KIS_SMOKE_APP_KEY"), env("KIS_SMOKE_APP_SECRET"), env("KIS_SMOKE_ACCOUNT"))
        val registry = KisClientRegistry(KisTradingProperties(enabled = true), KisApiProperties())
        try {
            val api = registry.api(KisEnvironment.PAPER, access)
            line("start env=PAPER fingerprint=${access.fingerprint} symbol=$symbol at=${ZonedDateTime.now(kst)}")
            step("token", core = true, { "issued" }) { registry.session(KisEnvironment.PAPER, access).tokens.token() }
            reads(api)
            if (flag("KIS_SMOKE_PLACE")) place(api)
            // 리미터가 모르는 요청이라, 앞 단계(특히 주문)와 같은 초 창에 섞이지 않게 맨 끝에 둔다.
            if (flag("KIS_SMOKE_BURST")) burst(registry, access)
        } finally {
            registry.close()
            File("build").mkdirs()
            File("build/kis-smoke-report.txt").writeText(report.joinToString("\n", postfix = "\n"))
        }
        assertTrue(failedSteps.isEmpty()) { "조회 단계 실패: $failedSteps (build/kis-smoke-report.txt)" }
    }

    private suspend fun reads(api: KisApi) {
        val today = LocalDate.now(kst)
        step("current-price", core = true, { "price=${it.price} open=${it.open} high=${it.high} low=${it.low}" }) {
            api.currentPrice(symbol, KisLane.QUERY)
        }
        step("daily-candles", core = true, { "rows=${it.size} newest=${it.firstOrNull()?.let { c -> "${c.date} close=${c.close}" }} oldest=${it.lastOrNull()?.date}" }) {
            api.dailyCandles(symbol, today.minusDays(30), today, adjusted = true, lane = KisLane.QUERY)
        }
        step("balance", core = true, { "pages=${it.pages} holdings=${it.holdings.size} $symbol qty=${it.holdings.firstOrNull { h -> h.code == symbol }?.holdingQty ?: 0}" }) {
            api.balance(KisLane.QUERY)
        }
        step("buyable", core = true, { "nrcvb_buy_qty=${it.noReceivableBuyQty} max_buy_qty=${it.maxBuyQty}" }) {
            val price = api.currentPrice(symbol, KisLane.QUERY).price
            api.buyable(symbol, price, KisLane.QUERY)
        }
        step("holidays", summary = { "rows=${it.size} first=${it.firstOrNull()?.let { r -> "${r.date} opnd=${r.openDay}" }}" }) {
            api.holidays(today, KisLane.QUERY)
        }
        step("daily-orders", core = true, { "rows=${it.size}" }) {
            api.dailyOrders(today, lane = KisLane.QUERY)
        }
    }

    /** 리미터를 거치지 않고 현재가를 한꺼번에 보내 유량 초과 응답의 HTTP 상태·rt_cd·msg_cd 를 본다. GET 만 쓴다. */
    private suspend fun burst(registry: KisClientRegistry, access: KisAccess) {
        val session = registry.session(KisEnvironment.PAPER, access)
        val token = session.tokens.token()
        val results = coroutineScope {
            (1..BURST_COUNT).map { index -> async { burstOnce(index, session.webClient, token, access) } }.awaitAll()
        }
        results.forEach(::line)
    }

    private suspend fun burstOnce(index: Int, webClient: WebClient, token: String, access: KisAccess): String {
        val started = System.nanoTime()
        return try {
            val response = webClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", symbol)
                        .build()
                }
                .headers { h ->
                    h.set("authorization", "Bearer $token")
                    h.set("appkey", access.appKey)
                    h.set("appsecret", access.appSecret)
                    h.set("tr_id", "FHKST01010100")
                    h.set("custtype", "P")
                    h.set("tr_cont", "")
                    h.contentType = MediaType.APPLICATION_JSON
                }
                .exchangeToMono { it.toEntity(String::class.java) }
                .awaitSingle()
            val body = response.body?.let { runCatching { ObjectMapper().readTree(it) }.getOrNull() }
            "burst#$index http=${response.statusCode.value()} rt_cd=${body?.get("rt_cd")?.asText()} " +
                "msg_cd=${body?.get("msg_cd")?.asText()} msg1=${body?.get("msg1")?.asText()} ${elapsedMs(started)}ms"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "burst#$index FAIL ${e.javaClass.simpleName} ${elapsedMs(started)}ms"
        }
    }

    private suspend fun place(api: KisApi) {
        val now = ZonedDateTime.now(kst)
        val inSession = now.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) &&
            now.toLocalTime() >= LocalTime.of(9, 0) && now.toLocalTime() <= LocalTime.of(15, 30)
        if (!inSession) line("WARN 장 시간(KST 09:00~15:30) 밖이다 — 그대로 보내 거절 응답을 기록한다")

        val before = holdingQty(api) ?: return line("order 생략 — 주문 전 잔고 조회 실패")
        val bought = order(api, KisSide.BUY, before, expectedAfter = before + 1)
            ?: return line("sell 생략 — 매수 주문이 ack 없이 끝났다")
        val filledInRows = (bought.row?.filledQty ?: 0) > 0
        if (!filledInRows && !bought.balanceReflected) {
            line("sell 생략 — 체결 조회·잔고 어디에서도 매수 체결을 확인하지 못했다")
            return
        }
        if (!filledInRows) line("WARN 잔고에는 반영됐지만 체결 조회에서 ack ODNO 의 체결을 찾지 못했다")
        order(api, KisSide.SELL, before + 1, expectedAfter = before)
    }

    private class Observed(val row: KisOrderRow?, val balanceReflected: Boolean)

    /** 1주 시장가 주문을 보내고 체결 조회·잔고 반영을 관측한다. ack 를 받지 못했으면 null. */
    private suspend fun order(api: KisApi, side: KisSide, qtyBefore: Long, expectedAfter: Long): Observed? {
        val name = "order-${side.name.lowercase()}"
        val today = LocalDate.now(kst)
        val ack: KisOrderAck = step(name, summary = { "odno=${it.odno} ord_tmd=${it.orderTime}" }) {
            api.placeCashOrder(KisCashOrderRequest(symbol, side, KisOrderType.MARKET, qty = 1))
        } ?: run {
            // 결과 불명이면 실제로 접수됐는지가 관측 대상이다.
            step("$name after-failure", summary = ::describeRows) { api.dailyOrders(today, lane = KisLane.RECONCILE) }
            return null
        }
        val acked = System.nanoTime()
        var unfilteredChecked = false

        var last: KisOrderRow? = null
        for (poll in 1..FILL_POLLS) {
            delay(POLL_INTERVAL_MS)
            val rows = step("$name fill#$poll", summary = { rows -> "ack_odno=${ack.odno} ${describeRows(rows)}" }) {
                api.dailyOrders(today, ack.odno, KisLane.RECONCILE)
            } ?: continue
            if (rows.isEmpty() && !unfilteredChecked) {
                // ODNO 필터만으로 거르는지, 두 응답의 ODNO 자릿수가 같은지 구분하려고 필터 없이 한 번 본다.
                unfilteredChecked = true
                step("$name fill#$poll unfiltered", summary = ::describeRows) { api.dailyOrders(today, lane = KisLane.RECONCILE) }
            }
            last = rows.firstOrNull { it.odno == ack.odno } ?: continue
            if (last.remainingQty == 0L && last.filledQty > 0) break
        }

        val deadline = System.nanoTime() + BALANCE_WAIT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            delay(POLL_INTERVAL_MS)
            val qty = holdingQty(api) ?: continue
            if (qty == expectedAfter) {
                line("$name balance reflected after ${elapsedMs(acked)}ms (qty $qtyBefore -> $qty)")
                return Observed(last, balanceReflected = true)
            }
        }
        line("$name balance not reflected within ${BALANCE_WAIT_MS}ms (expected qty $expectedAfter)")
        return Observed(last, balanceReflected = false)
    }

    private fun describeRows(rows: List<KisOrderRow>): String =
        "rows=${rows.size} " + rows.joinToString {
            "odno=${it.odno} side=${it.side} filled=${it.filledQty} avg=${it.avgPrice} remaining=${it.remainingQty} cancelled=${it.cancelled}"
        }.ifEmpty { "not visible yet" }

    private suspend fun holdingQty(api: KisApi): Long? =
        step("balance-poll", summary = { "qty=$it" }) {
            api.balance(KisLane.RECONCILE).holdings.firstOrNull { it.code == symbol }?.holdingQty ?: 0L
        }

    private companion object {
        const val BURST_COUNT = 5
        const val FILL_POLLS = 10
        const val POLL_INTERVAL_MS = 1_000L
        const val BALANCE_WAIT_MS = 30_000L
    }
}
