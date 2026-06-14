package com.trading.bot.kis.client

import com.trading.bot.kis.config.KisProperties
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import io.netty.channel.ChannelOption
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 사용자별 [KisClient] 생성/캐싱. 토큰 캐시를 살리기 위해 userId 별로 클라이언트를 재사용한다.
 * 키 변경 시 [invalidate] 호출 필요(Upbit 의 reloadUserRuntime 과 동일 사상).
 */
@Component
class KisClientFactory(
    private val kisProperties: KisProperties,
    private val userSecretsService: UserSecretsService,
) {
    private val clients = ConcurrentHashMap<Long, KisClient>()

    /** user 는 암호화 상태든 복호화 상태든 받아 내부에서 복호화한다. */
    fun forUser(user: UserEntity): KisClient {
        val userId = requireNotNull(user.id) { "user.id is null" }
        return clients.computeIfAbsent(userId) { build(userSecretsService.decryptUserSecrets(user)) }
    }

    fun invalidate(userId: Long) {
        clients.remove(userId)
    }

    private fun build(decrypted: UserEntity): KisClient {
        val appKey = require(decrypted.kisAppKey, decrypted.id, "kisAppKey")
        val appSecret = require(decrypted.kisAppSecret, decrypted.id, "kisAppSecret")
        val cano = require(decrypted.kisCano, decrypted.id, "kisCano")
        val acntPrdtCd = require(decrypted.kisAcntPrdtCd, decrypted.id, "kisAcntPrdtCd")
        val baseUrl = if (decrypted.kisPaper) kisProperties.paperBaseUrl else kisProperties.realBaseUrl
        val webClient = buildWebClient(baseUrl)
        val tokenProvider = KisTokenProvider(webClient, appKey, appSecret, kisProperties.tokenRefreshSkewSeconds)
        return KisClientImpl(webClient, tokenProvider, appKey, appSecret, cano, acntPrdtCd, decrypted.kisPaper, kisProperties.custType)
    }

    private fun buildWebClient(baseUrl: String): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, kisProperties.connectTimeoutMs)
            .responseTimeout(Duration.ofSeconds(kisProperties.responseTimeoutSeconds))
        return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { it.defaultCodecs().maxInMemorySize(1024 * 1024) }
            .build()
    }

    private fun require(value: String?, userId: Long?, field: String): String =
        value?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("$field is not configured for user $userId")
}
