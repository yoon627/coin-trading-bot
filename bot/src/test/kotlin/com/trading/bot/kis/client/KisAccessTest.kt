package com.trading.bot.kis.client

import java.security.MessageDigest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KisAccessTest {

    @Test
    fun `toString 에는 지문과 계좌 끝 4자리만 있다`() {
        val text = TEST_ACCESS.toString()

        assertThat(text).contains(TEST_ACCESS.fingerprint, "5678")
        assertThat(text).doesNotContain(TEST_ACCESS.appKey, TEST_ACCESS.appSecret, TEST_ACCESS.cano)
    }

    @Test
    fun `지문은 앱키와 앱시크릿을 구분자로 이은 SHA-256 앞 16자다`() {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("${TEST_ACCESS.appKey}\u0000${TEST_ACCESS.appSecret}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

        assertThat(TEST_ACCESS.fingerprint).isEqualTo(expected)
        assertThat(KisAccess("ab", "c", "12345678", "01").fingerprint).isNotEqualTo(KisAccess("a", "bc", "12345678", "01").fingerprint)
    }

    @Test
    fun `형식이 틀리면 만들 수 없고 메시지에 원문이 없다`() {
        val errors = listOf(
            assertThrows<IllegalArgumentException> { KisAccess(" ", "s", "12345678", "01") },
            assertThrows<IllegalArgumentException> { KisAccess("k", "", "12345678", "01") },
            assertThrows<IllegalArgumentException> { KisAccess("k", "s", "1234567", "01") },
            assertThrows<IllegalArgumentException> { KisAccess("k", "s", "1234567a", "01") },
            assertThrows<IllegalArgumentException> { KisAccess("k", "s", "12345678", "1") },
            assertThrows<IllegalArgumentException> { KisAccess.of("k", "s", "1234567-012") },
        )

        errors.forEach { assertThat(it.message).doesNotContain("1234567") }
    }

    @Test
    fun `계좌번호 8-2 문자열을 나눠 받는다`() {
        val access = KisAccess.of("k", "s", "12345678-01")

        assertThat(access.cano).isEqualTo("12345678")
        assertThat(access.productCode).isEqualTo("01")
    }

    @Test
    fun `모의 tr_id 는 첫 글자 T·J·C 만 V 로 바꾼다`() {
        assertThat(listOf("TTTC0012U", "JTTT1002U", "CTCA0903R", "FHKST01010100").map { KisEnvironment.PAPER.trId(it) })
            .containsExactly("VTTC0012U", "VTTT1002U", "VTCA0903R", "FHKST01010100")
        assertThat(KisEnvironment.LIVE.trId("TTTC0012U")).isEqualTo("TTTC0012U")
    }
}
