package com.trading.bot.auth

import com.trading.bot.security.SecretKeyMaterialProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class JwtProviderTest {

    private lateinit var jwtProvider: JwtProvider

    @BeforeEach
    fun setup() {
        val secretProvider = mockk<SecretKeyMaterialProvider>()
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest("test-secret-key-for-jwt".toByteArray(StandardCharsets.UTF_8))
        every { secretProvider.jwtKeyBytes() } returns keyBytes
        every { secretProvider.jwtExpirationMs() } returns 86400000L
        jwtProvider = JwtProvider(secretProvider)
    }

    @Test
    fun `generateToken returns non-empty token`() {
        val token = jwtProvider.generateToken(1L, "testuser")
        assertNotNull(token)
        assertTrue(token.isNotBlank())
    }

    @Test
    fun `generateToken creates different tokens for different users`() {
        val token1 = jwtProvider.generateToken(1L, "user1")
        val token2 = jwtProvider.generateToken(2L, "user2")
        assertNotEquals(token1, token2)
    }

    @Test
    fun `validateAndGetUserId returns userId for valid token`() {
        val token = jwtProvider.generateToken(42L, "testuser")
        val userId = jwtProvider.validateAndGetUserId(token)
        assertEquals(42L, userId)
    }

    @Test
    fun `validateAndGetUserId returns null for invalid token`() {
        val userId = jwtProvider.validateAndGetUserId("invalid.token.here")
        assertNull(userId)
    }

    @Test
    fun `validateAndGetUserId returns null for empty token`() {
        val userId = jwtProvider.validateAndGetUserId("")
        assertNull(userId)
    }

    @Test
    fun `validateAndGetUserId returns null for tampered token`() {
        val token = jwtProvider.generateToken(1L, "testuser")
        val tampered = token.dropLast(5) + "XXXXX"
        val userId = jwtProvider.validateAndGetUserId(tampered)
        assertNull(userId)
    }

    @Test
    fun `validateAndGetUserId returns null for expired token`() {
        val secretProvider = mockk<SecretKeyMaterialProvider>()
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest("test-secret-key-for-jwt".toByteArray(StandardCharsets.UTF_8))
        every { secretProvider.jwtKeyBytes() } returns keyBytes
        every { secretProvider.jwtExpirationMs() } returns -1000L // already expired
        val expiredProvider = JwtProvider(secretProvider)

        val token = expiredProvider.generateToken(1L, "testuser")
        val userId = expiredProvider.validateAndGetUserId(token)
        assertNull(userId)
    }

    @Test
    fun `validateAndGetUserId returns null for token signed with different key`() {
        val otherProvider = mockk<SecretKeyMaterialProvider>()
        val otherKey = MessageDigest.getInstance("SHA-256")
            .digest("different-secret-key".toByteArray(StandardCharsets.UTF_8))
        every { otherProvider.jwtKeyBytes() } returns otherKey
        every { otherProvider.jwtExpirationMs() } returns 86400000L
        val otherJwt = JwtProvider(otherProvider)

        val token = otherJwt.generateToken(1L, "testuser")
        val userId = jwtProvider.validateAndGetUserId(token)
        assertNull(userId)
    }
}
