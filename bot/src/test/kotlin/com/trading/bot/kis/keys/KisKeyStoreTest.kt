package com.trading.bot.kis.keys

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.trading.common.domain.Exchange
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.springframework.dao.DataIntegrityViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class KisKeyStoreTest {

    private val keys = InMemoryBrokerKeys()
    private val crypto = testCrypto()
    private val store = keyStore(keys, crypto = crypto)

    private fun stored(exchange: String = "KIS_PAPER") = keys.rows.getValue(1L to exchange)

    private fun assertNoRawValue(text: String?) {
        FAKE_RAW_VALUES.forEach { raw -> assertFalse(text.orEmpty().contains(raw), "원문이 나왔다: $text") }
    }

    @Test
    fun `저장값은 세 칸 모두 enc 로 시작하는 암호문이고 원문을 담지 않는다`() = runTest {
        store.save(1L, Exchange.KIS_PAPER, " $FAKE_APP_KEY ", FAKE_APP_SECRET, "5012345601")

        val row = stored()
        listOf(row.appKey, row.appSecret, row.accountNo).forEach { value ->
            assertTrue(value.startsWith("enc:"), "암호문이 아니다")
            assertNoRawValue(value)
        }
        // 하이픈 없는 계좌번호도 8-2 형태로 맞춰 저장한다.
        assertEquals(FAKE_ACCOUNT, crypto.decryptIfNeeded(row.accountNo))
    }

    @Test
    fun `load 는 저장한 값과 같은 KisAccess 를 돌려준다`() = runTest {
        store.save(1L, Exchange.KIS_PAPER, FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT)

        val access = requireNotNull(store.load(1L, Exchange.KIS_PAPER))

        assertEquals(FAKE_APP_KEY, access.appKey)
        assertEquals(FAKE_APP_SECRET, access.appSecret)
        assertEquals("50123456", access.cano)
        assertEquals("01", access.productCode)
        assertNull(store.load(1L, Exchange.KIS), "다른 환경의 키를 내주면 모의 키로 실전 주문을 낼 수 있다")
    }

    @Test
    fun `형식·길이·공백 오류는 원문 없는 메시지로 거절하고 저장하지 않는다`() = runTest {
        val cases = listOf(
            Triple("  ", FAKE_APP_SECRET, FAKE_ACCOUNT),
            Triple("PSfake AppKey", FAKE_APP_SECRET, FAKE_ACCOUNT),
            Triple(FAKE_APP_KEY, "${FAKE_APP_SECRET}\u0007", FAKE_ACCOUNT),
            Triple(FAKE_APP_KEY, "${FAKE_APP_SECRET}\t$FAKE_APP_SECRET", FAKE_ACCOUNT),
            Triple("${FAKE_APP_KEY}키", FAKE_APP_SECRET, FAKE_ACCOUNT),
            Triple("A".repeat(KisKeyStore.APP_KEY_MAX_LENGTH + 1), FAKE_APP_SECRET, FAKE_ACCOUNT),
            Triple(FAKE_APP_KEY, "B".repeat(KisKeyStore.APP_SECRET_MAX_LENGTH + 1), FAKE_ACCOUNT),
            Triple(FAKE_APP_KEY, FAKE_APP_SECRET, "50123456-1"),
            Triple(FAKE_APP_KEY, FAKE_APP_SECRET, "50123456 01"),
        )
        cases.forEach { (appKey, appSecret, account) ->
            val error = assertThrows<KisKeyInvalidException> { store.save(1L, Exchange.KIS_PAPER, appKey, appSecret, account) }
            assertNoRawValue(error.message)
            assertNull(error.cause)
        }
        verify(exactly = 0) { keys.repository.upsert(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `길이 상한의 키도 V31 열 길이 안에 들어간다`() = runTest {
        store.save(
            1L,
            Exchange.KIS_PAPER,
            "A".repeat(KisKeyStore.APP_KEY_MAX_LENGTH),
            "~".repeat(KisKeyStore.APP_SECRET_MAX_LENGTH),
            FAKE_ACCOUNT,
        )

        val row = stored()
        assertTrue(row.appKey.length <= 512, "app_key ${row.appKey.length}")
        assertTrue(row.appSecret.length <= 1024, "app_secret ${row.appSecret.length}")
        assertTrue(row.accountNo.length <= 255, "account_no ${row.accountNo.length}")
    }

    @Test
    fun `실전 키는 live-enabled 가 꺼져 있으면 거절하고 켜져 있으면 저장한다`() = runTest {
        assertThrows<KisLiveKeysDisabledException> { store.save(1L, Exchange.KIS, FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT) }
        assertTrue(keys.rows.isEmpty())

        keyStore(keys, liveEnabled = true).save(1L, Exchange.KIS, FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT)
        assertTrue(stored("KIS").appKey.startsWith("enc:"))
    }

    @Test
    fun `UPBIT 는 저장·조회·삭제 모두 거절한다`() = runTest {
        assertThrows<KisKeyInvalidException> { store.save(1L, Exchange.UPBIT, FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT) }
        assertThrows<KisKeyInvalidException> { store.load(1L, Exchange.UPBIT) }
        assertThrows<KisKeyInvalidException> { store.delete(1L, Exchange.UPBIT) }
        verify(exactly = 0) { keys.repository.upsert(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { keys.repository.deleteByUserIdAndExchange(any(), any()) }
    }

    @Test
    fun `status 는 두 환경을 순서대로 내고 계좌만 마스킹해 담는다`() = runTest {
        store.save(1L, Exchange.KIS_PAPER, FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT)

        val status = store.status(1L)

        assertEquals(
            listOf(
                KisKeyStatus("KIS_PAPER", configured = true, account = "****3456-01", updatedAt = InMemoryBrokerKeys.UPDATED_AT),
                KisKeyStatus("KIS", configured = false, account = null, updatedAt = null),
            ),
            status,
        )
        val json = ObjectMapper().registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .writeValueAsString(status)
        assertNoRawValue(json)
        assertFalse(json.contains("enc:"), json)
    }

    @Test
    fun `다른 암호화 키로 만든 행은 원인 없이 원문·암호문 없는 메시지로 실패하고 status 는 계좌만 비운다`() = runTest {
        keyStore(keys, crypto = testCrypto("rotated-away-material"))
            .save(1L, Exchange.KIS_PAPER, FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT)
        val row = stored()

        val error = assertThrows<IllegalStateException> { store.load(1L, Exchange.KIS_PAPER) }

        assertNull(error.cause)
        assertNoRawValue(error.message)
        listOf(row.appKey, row.appSecret, row.accountNo).forEach {
            assertFalse(error.message!!.contains(it.removePrefix("enc:").take(16)), "암호문 조각이 나왔다")
        }
        assertEquals(KisKeyStatus("KIS_PAPER", true, null, InMemoryBrokerKeys.UPDATED_AT), store.status(1L).first())
    }

    @Test
    fun `enc 접두가 없는 값은 평문 키로 쓰지 않고 복호화 실패로 본다`() = runTest {
        store.save(1L, Exchange.KIS_PAPER, FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT)
        keys.rows[1L to "KIS_PAPER"] = stored().copy(appKey = FAKE_APP_KEY)

        val error = assertThrows<IllegalStateException> { store.load(1L, Exchange.KIS_PAPER) }
        assertNoRawValue(error.message)
    }

    @Test
    fun `복호화한 값의 형식이 틀리면 원인 없는 IllegalStateException 이고 status 는 계좌만 비운다`() = runTest {
        store.save(1L, Exchange.KIS_PAPER, FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT)
        keys.rows[1L to "KIS_PAPER"] = stored().copy(accountNo = crypto.encrypt("50123456"))

        val error = assertThrows<IllegalStateException> { store.load(1L, Exchange.KIS_PAPER) }

        assertNull(error.cause)
        assertNoRawValue(error.message)
        assertNull(store.status(1L).first().account)
    }

    @Test
    fun `delete 는 그 환경의 행만 지우고 지운 것이 있었는지 돌려준다`() = runTest {
        val live = keyStore(keys, liveEnabled = true)
        live.save(1L, Exchange.KIS_PAPER, FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT)
        live.save(1L, Exchange.KIS, FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT)

        assertTrue(store.delete(1L, Exchange.KIS_PAPER))
        assertFalse(store.delete(1L, Exchange.KIS_PAPER))
        assertEquals(setOf(1L to "KIS"), keys.rows.keys)
    }

    @Test
    fun `DB 가 응답하지 않으면 상한 뒤 저장소 지연 오류로 끝난다`() = runTest {
        every { keys.repository.findByUserId(1L) } returns Flux.never()

        assertThrows<KisKeyStoreUnavailableException> { store.status(1L) }
    }

    @Test
    fun `DB 오류는 원인 없이 저장소 오류로 바꾼다`() = runTest {
        // Postgres 제약 위반 메시지는 실패한 행을 통째로 담는다.
        every { keys.repository.upsert(any(), any(), any(), any(), any()) } returns
            Mono.error(DataIntegrityViolationException("Failing row contains (enc:$FAKE_APP_KEY)"))

        val error = assertThrows<KisKeyStoreUnavailableException> {
            store.save(1L, Exchange.KIS_PAPER, FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_ACCOUNT)
        }

        assertNull(error.cause)
        assertNoRawValue(error.message)
    }
}
