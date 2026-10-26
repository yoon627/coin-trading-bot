package com.trading.bot.kis.keys

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.trading.bot.engine.RuntimeReloadFailedException
import com.trading.bot.engine.TradingManagers
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.kis.config.KisTradingProperties
import com.trading.bot.persistence.UserBrokerKeyRepository
import com.trading.bot.security.SecretsCrypto
import com.trading.common.domain.Exchange
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.reactor.mono
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks

/**
 * 상태 코드는 예외가 WebFlux 를 거쳐야 정해지므로 실제 파이프라인으로 본다.
 * 이 파이프라인에는 SafeErrorAttributes 가 없어 오류 본문이 비므로 오류 응답의 원문 여부는 여기서 보지 않는다.
 * 사유가 message 로 나가는지는 SafeErrorAttributesTest 가, 사유 문구에 원문이 없는지는 KisKeyStoreTest 가 본다.
 */
class KisKeyControllerTest {

    private val keys = InMemoryBrokerKeys()

    // 앱의 전역 Jackson 설정(snake_case, Spring Boot 기본인 ISO 날짜 문자열)과 같게 주고받는다.
    private val mapper: ObjectMapper = Jackson2ObjectMapperBuilder.json()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()

    private fun authFilter(): WebFilter = WebFilter { exchange, chain ->
        chain.filter(exchange).contextWrite(
            ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(1L, null, emptyList())),
        )
    }

    private fun client(
        liveEnabled: Boolean = false,
        dbTimeoutMs: Long = KisKeyStore.DB_TIMEOUT_MS,
        managers: TradingManagers = TradingManagers.of(),
    ): WebTestClient = WebTestClient
        .bindToController(KisKeyController(keyStore(keys, liveEnabled = liveEnabled, dbTimeoutMs = dbTimeoutMs), managers))
        .httpMessageCodecs {
            it.defaultCodecs().jackson2JsonDecoder(Jackson2JsonDecoder(mapper))
            it.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(mapper))
        }
        .webFilter<WebTestClient.ControllerSpec>(authFilter())
        .build()

    private fun body(
        appKey: String? = FAKE_APP_KEY,
        appSecret: String? = FAKE_APP_SECRET,
        accountNumber: String? = FAKE_ACCOUNT,
    ): Map<String, String> = buildMap {
        appKey?.let { put("app_key", it) }
        appSecret?.let { put("app_secret", it) }
        accountNumber?.let { put("account_number", it) }
    }

    private fun WebTestClient.put(exchange: String, body: Map<String, String> = body()): WebTestClient.ResponseSpec =
        put().uri("/api/kis/keys/{exchange}", exchange)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()

    private fun WebTestClient.ResponseSpec.assertNoRawValue(): WebTestClient.ResponseSpec = also { spec ->
        val text = spec.expectBody(String::class.java).returnResult().responseBody.orEmpty()
        FAKE_RAW_VALUES.forEach { raw -> assertFalse(text.contains(raw), "응답에 원문이 나왔다: $text") }
        assertFalse(text.contains("enc:"), "응답에 암호문이 나왔다: $text")
    }

    @Test
    fun `PUT 은 snake_case 본문을 저장하고 saved 를 돌려준다`() {
        client().put("KIS_PAPER")
            .expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("saved")

        assertTrue(keys.rows.getValue(1L to "KIS_PAPER").appKey.startsWith("enc:"))
    }

    @Test
    fun `GET 은 두 환경의 상태를 내고 원문·암호문은 내지 않는다`() {
        client().put("KIS_PAPER").expectStatus().isOk

        client().get().uri("/api/kis/keys").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].exchange").isEqualTo("KIS_PAPER")
            .jsonPath("$[0].configured").isEqualTo(true)
            .jsonPath("$[0].account").isEqualTo("****3456-01")
            .jsonPath("$[0].updated_at").isEqualTo("2026-10-21T00:30:00Z")
            .jsonPath("$[1].exchange").isEqualTo("KIS")
            .jsonPath("$[1].configured").isEqualTo(false)
        client().get().uri("/api/kis/keys").exchange().expectStatus().isOk.assertNoRawValue()
    }

    @Test
    fun `잘못된 입력·빠진 필드는 400 이다`() {
        listOf(
            body(appKey = "PSfake ${FAKE_APP_KEY}"),
            body(appSecret = "${FAKE_APP_SECRET}\u0000"),
            body(accountNumber = "50123456-0"),
            body(appSecret = null),
        ).forEach { request ->
            client().put("KIS_PAPER", request).expectStatus().isBadRequest
        }
        assertTrue(keys.rows.isEmpty())
    }

    @Test
    fun `KIS_PAPER·KIS 밖의 경로값은 저장소에 닿기 전에 400 이다`() {
        listOf("UPBIT", "kis_paper", "KIS-PAPER").forEach { exchange ->
            client().put(exchange).expectStatus().isBadRequest
            client().delete().uri("/api/kis/keys/{exchange}", exchange).exchange().expectStatus().isBadRequest
        }
        verify(exactly = 0) { keys.repository.upsert(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { keys.repository.deleteByUserIdAndExchange(any(), any()) }
    }

    @Test
    fun `실전 키는 live-enabled 가 꺼져 있으면 409 이고 켜져 있으면 저장한다`() {
        client().put("KIS").expectStatus().isEqualTo(HttpStatus.CONFLICT)
        assertTrue(keys.rows.isEmpty())

        client(liveEnabled = true).put("KIS").expectStatus().isOk
        assertTrue(keys.rows.containsKey(1L to "KIS"))
    }

    @Test
    fun `저장소가 응답하지 않거나 DB 오류면 503 이다`() {
        every { keys.repository.findByUserId(1L) } returns Flux.never()
        every { keys.repository.upsert(any(), any(), any(), any(), any()) } returns
            Mono.error(DataAccessResourceFailureException("connection refused"))
        val client = client(dbTimeoutMs = 50)

        client.get().uri("/api/kis/keys").exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        client.put("KIS_PAPER").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `DELETE 는 그 환경의 키를 지우고 deleted 를 돌려준다`() {
        client().put("KIS_PAPER").expectStatus().isOk

        client().delete().uri("/api/kis/keys/KIS_PAPER").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("deleted")
        assertTrue(keys.rows.isEmpty())
    }

    @Test
    fun `저장·삭제 뒤 그 환경의 매니저만 다시 읽히고 매니저가 없으면 저장만 한다`() {
        val upbit = mockk<UserTradingManager>(relaxed = true)
        val paper = mockk<UserTradingManager>(relaxed = true)
        val client = client(liveEnabled = true, managers = TradingManagers.of(Exchange.UPBIT to upbit, Exchange.KIS_PAPER to paper))

        client.put("KIS_PAPER").expectStatus().isOk
        client.delete().uri("/api/kis/keys/KIS_PAPER").exchange().expectStatus().isOk
        client.put("KIS").expectStatus().isOk
        client.delete().uri("/api/kis/keys/KIS").exchange().expectStatus().isOk

        coVerify(exactly = 2) { paper.reloadUserRuntime(1L) }
        coVerify(exactly = 0) { upbit.reloadUserRuntime(any()) }
    }

    @Test
    fun `저장 뒤 엔진 교체가 실패하면 503 이고 키는 저장돼 있다`() {
        val paper = mockk<UserTradingManager>()
        coEvery { paper.reloadUserRuntime(1L) } throws
            RuntimeReloadFailedException(1L, RuntimeException("kis down"), engineRestored = true)

        client(managers = TradingManagers.of(Exchange.KIS_PAPER to paper)).put("KIS_PAPER")
            .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertTrue(keys.rows.containsKey(1L to "KIS_PAPER"))
    }

    @Test
    fun `저장·삭제 중 요청이 끊겨도 끝까지 마치고 엔진이 다시 읽는다`() {
        val paper = mockk<UserTradingManager>(relaxed = true)
        val controller = KisKeyController(keyStore(keys), TradingManagers.of(Exchange.KIS_PAPER to paper))
        val auth = ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(1L, null, emptyList()))
        val saving = Sinks.one<Int>()
        val deleting = Sinks.one<Int>()
        every { keys.repository.upsert(any(), any(), any(), any(), any()) } returns saving.asMono()
        every { keys.repository.deleteByUserIdAndExchange(any(), any()) } returns deleting.asMono()

        val save = mono { controller.save("KIS_PAPER", KisKeyRequest(FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT)) }
            .contextWrite(auth).subscribe({}, {})
        verify(timeout = 2_000) { keys.repository.upsert(1L, "KIS_PAPER", any(), any(), any()) }
        save.dispose()
        saving.tryEmitValue(1)
        coVerify(timeout = 2_000, exactly = 1) { paper.reloadUserRuntime(1L) }

        val delete = mono { controller.delete("KIS_PAPER") }.contextWrite(auth).subscribe({}, {})
        verify(timeout = 2_000) { keys.repository.deleteByUserIdAndExchange(1L, "KIS_PAPER") }
        delete.dispose()
        deleting.tryEmitValue(1)
        coVerify(timeout = 2_000, exactly = 2) { paper.reloadUserRuntime(1L) }
    }

    @Test
    fun `요청 객체의 toString 은 원문을 내지 않는다`() {
        val text = KisKeyRequest(FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT).toString()
        FAKE_RAW_VALUES.forEach { assertFalse(text.contains(it), text) }
    }

    @EnableConfigurationProperties(KisTradingProperties::class)
    class Config

    @Test
    fun `kis_trading_enabled 가 없거나 false 면 저장소·컨트롤러 빈이 없고 true 면 있다`() {
        val runner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration::class.java))
            .withUserConfiguration(Config::class.java, KisKeyStore::class.java, KisKeyController::class.java)
            .withBean(UserBrokerKeyRepository::class.java, { mockk<UserBrokerKeyRepository>() })
            .withBean(SecretsCrypto::class.java, { testCrypto() })
            .withBean(TradingManagers::class.java, { TradingManagers.of() })

        listOf(runner, runner.withPropertyValues("kis.trading.enabled=false")).forEach {
            it.run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(KisKeyStore::class.java)
                assertThat(context).doesNotHaveBean(KisKeyController::class.java)
            }
        }
        runner.withPropertyValues("kis.trading.enabled=true").run { context ->
            assertThat(context).hasSingleBean(KisKeyStore::class.java)
            assertThat(context).hasSingleBean(KisKeyController::class.java)
        }
    }
}
