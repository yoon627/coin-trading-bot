package com.trading.bot.api

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.UserTradingParams
import com.trading.bot.engine.BotChangeFailedException
import com.trading.bot.engine.ParamsPreviewResult
import com.trading.bot.engine.PositionChange
import com.trading.bot.engine.TradingManagers
import com.trading.bot.engine.UserTradingManager
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter

/** 화면이 읽고 보내는 모양(snake_case)과 상태 코드: 범위 밖 400, 확인 필요 409(미리보기 포함), 적용 실패 503. */
class TradingParamsControllerTest {
    private val env = TradingProperties(takeProfitPct = 5.0, maxLossPct = 5.0)
    private val manager = mockk<UserTradingManager> {
        every { defaultProfile() } returns env
        every { paramsProblems(any()) } returns emptyList()
        every { paramsWarnings(any()) } returns emptyList()
    }
    private val mapper = jacksonObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    private val client = WebTestClient
        .bindToController(TradingParamsController(TradingManagers.of(Exchange.UPBIT to manager), BoardPrices { 97.0 }))
        .httpMessageCodecs {
            it.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(mapper))
            it.defaultCodecs().jackson2JsonDecoder(Jackson2JsonDecoder(mapper))
        }
        .webFilter<WebTestClient.ControllerSpec>(
            WebFilter { exchange, chain ->
                chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(1L, null, emptyList())))
            },
        )
        .build()
    private val change = PositionChange(
        "KRW-BTC",
        ExitParamsSnapshot(5.0, 5.0, 2.0, 3.0, 1),
        ExitParamsSnapshot(5.0, 2.0, 2.0, 3.0, 1),
        97.0, -3.0, listOf("stop_loss"),
    )

    @Test
    fun `get shows env defaults, the user's own values and the result`() {
        coEvery { manager.savedParams(1L) } returns UserTradingParams(maxLossPct = 2.0)

        client.get().uri("/api/bot/params").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.defaults.max_loss_pct").isEqualTo(5.0)
            .jsonPath("$.params.max_loss_pct").isEqualTo(2.0)
            .jsonPath("$.params.take_profit_pct").doesNotExist()
            .jsonPath("$.effective.max_loss_pct").isEqualTo(2.0)
            .jsonPath("$.effective.take_profit_pct").isEqualTo(5.0)
    }

    // 기본 snake_case 규칙은 kValue 를 "kvalue" 로 바꿔 화면이 보낸 k 가 조용히 버려진다.
    @Test
    fun `k is read and written as k_value`() {
        coEvery { manager.savedParams(1L) } returns UserTradingParams(kValue = 0.7)
        coEvery { manager.saveParams(1L, UserTradingParams(kValue = 0.8), null, any()) } returns mapOf("status" to "saved", "changes" to emptyList<Any>())

        client.get().uri("/api/bot/params").exchange().expectBody().jsonPath("$.params.k_value").isEqualTo(0.7)
        client.put().uri("/api/bot/params").bodyValue(mapOf("k_value" to 0.8)).exchange().expectStatus().isOk
    }

    @Test
    fun `out-of-range values are 400 in the screen's keys and never reach the manager`() {
        every { manager.paramsProblems(UserTradingParams(maxLossPct = 0.0)) } returns listOf("trading.max-loss-pct=0.0 (허용 (0, 100))")

        client.put().uri("/api/bot/params").bodyValue(mapOf("max_loss_pct" to 0)).exchange()
            .expectStatus().isBadRequest

        coVerify(exactly = 0) { manager.saveParams(any(), any(), any(), any()) }
    }

    // PUT 은 전체 교체라 키를 잘못 적으면 모든 값이 기본으로 돌아간다 — 모르는 키는 받지 않는다.
    @Test
    fun `an unknown key is 400 instead of silently resetting everything`() {
        client.put().uri("/api/bot/params").bodyValue(mapOf("maxLossPct" to 2.0)).exchange().expectStatus().isBadRequest

        coVerify(exactly = 0) { manager.saveParams(any(), any(), any(), any()) }
    }

    @Test
    fun `an empty body reverts every value to env`() {
        coEvery { manager.saveParams(1L, UserTradingParams.NONE, null, any()) } returns mapOf("status" to "saved", "changes" to emptyList<Any>())

        client.put().uri("/api/bot/params").bodyValue(emptyMap<String, Any>()).exchange().expectStatus().isOk
    }

    // 보유 포지션이 바뀌면 확인 없이는 저장하지 않는다 — 화면이 빠져도 서버가 막는다.
    @Test
    fun `a save that moves held positions without confirm is 409 with the preview`() {
        coEvery { manager.saveParams(1L, UserTradingParams(maxLossPct = 2.0), null, any()) } returns
            mapOf("status" to "confirm_required", "changes" to listOf(change), "confirm_token" to "t1")

        client.put().uri("/api/bot/params").bodyValue(mapOf("max_loss_pct" to 2.0)).exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.changes[0].ticker").isEqualTo("KRW-BTC")
            .jsonPath("$.changes[0].triggers[0]").isEqualTo("stop_loss")
            .jsonPath("$.changes[0].after.max_loss_pct").isEqualTo(2.0)
            .jsonPath("$.confirm_token").isEqualTo("t1")
    }

    @Test
    fun `a confirmed save returns the result with combination warnings`() {
        coEvery { manager.saveParams(1L, UserTradingParams(takeProfitPct = 1.0), "t1", any()) } returns mapOf("status" to "applied", "changes" to emptyList<Any>())
        every { manager.paramsWarnings(UserTradingParams(takeProfitPct = 1.0)) } returns listOf("takeProfitPct(1.0) <= trailingStopPct")

        client.put().uri("/api/bot/params").bodyValue(mapOf("take_profit_pct" to 1.0, "confirm_token" to "t1")).exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("applied")
            .jsonPath("$.warnings[0]").value<String> { assert(it.contains("takeProfitPct(1.0)")) }
    }

    @Test
    fun `a change that could not be applied is 503 with what actually happened`() {
        coEvery { manager.saveParams(any(), any(), any(), any()) } throws
            BotChangeFailedException(1L, UserTradingManager.PARAMS_NOT_APPLIED_MESSAGE)

        client.put().uri("/api/bot/params").bodyValue(mapOf("max_loss_pct" to 2.0, "confirm_token" to "t1")).exchange()
            .expectStatus().isEqualTo(503)
    }

    @Test
    fun `preview uses the board price and returns the changes`() {
        coEvery { manager.previewParams(1L, UserTradingParams(maxLossPct = 2.0), any()) } returns ParamsPreviewResult(listOf(change), "t1")

        client.post().uri("/api/bot/params/preview").bodyValue(mapOf("max_loss_pct" to 2.0)).exchange()
            .expectStatus().isOk.expectBody().jsonPath("$.changes[0].pnl_pct").isEqualTo(-3.0)
    }
}
