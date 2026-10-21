package com.trading.bot.kis.client

import java.security.MessageDigest

/**
 * KIS 앱키·계좌 묶음. 원문은 이 객체 밖으로 문자열로 나가지 않게 한다 — [toString] 과 [fingerprint] 만 로그·맵 키에 쓴다.
 */
class KisAccess(
    val appKey: String,
    val appSecret: String,
    val cano: String,
    val productCode: String,
) {
    init {
        // 값 자체를 메시지에 넣지 않는다.
        require(appKey.isNotBlank()) { "KIS appKey 가 비었다" }
        require(appSecret.isNotBlank()) { "KIS appSecret 이 비었다" }
        require(CANO.matches(cano)) { "KIS 계좌번호 앞자리(CANO)는 숫자 8자리여야 한다" }
        require(PRODUCT_CODE.matches(productCode)) { "KIS 계좌상품코드는 숫자 2자리여야 한다" }
    }

    /** SHA-256(appKey + 구분자 + appSecret) hex 앞 16자. 키가 바뀌면 달라져 토큰·리미터 세션을 새로 만든다. */
    val fingerprint: String = sha256Hex(appKey + SEPARATOR + appSecret).take(16)

    // 맵 키 전용. 계좌번호는 10자리라 해시를 전수 대입으로 되돌릴 수 있으므로 로그·메시지에 쓰지 않는다.
    internal val accountFingerprint: String = sha256Hex(cano + SEPARATOR + productCode).take(16)

    override fun toString(): String = "KisAccess(fingerprint=$fingerprint, account=****${cano.takeLast(4)}-**)"

    companion object {
        private const val SEPARATOR = "\u0000"
        private val CANO = Regex("\\d{8}")
        private val PRODUCT_CODE = Regex("\\d{2}")
        private val ACCOUNT_NUMBER = Regex("(\\d{8})-?(\\d{2})")

        /** "12345678-01" 또는 "1234567801" 형태의 계좌번호를 나눠 받는다. */
        fun of(appKey: String, appSecret: String, accountNumber: String): KisAccess {
            val match = requireNotNull(ACCOUNT_NUMBER.matchEntire(accountNumber.trim())) {
                "KIS 계좌번호는 8-2 자리 숫자여야 한다"
            }
            return KisAccess(appKey, appSecret, match.groupValues[1], match.groupValues[2])
        }

        private fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
