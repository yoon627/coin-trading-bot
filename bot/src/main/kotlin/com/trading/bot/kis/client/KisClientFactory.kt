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

    @Volatile
    private var defaultClientCache: KisClient? = null

    /** user 는 암호화 상태든 복호화 상태든 받아 내부에서 복호화한다. */
    fun forUser(user: UserEntity): KisClient {
        val userId = requireNotNull(user.id) { "user.id is null" }
        return clients.computeIfAbsent(userId) {
            val d = userSecretsService.decryptUserSecrets(user)
            buildClient(
                require(d.kisAppKey, d.id, "kisAppKey"),
                require(d.kisAppSecret, d.id, "kisAppSecret"),
                require(d.kisCano, d.id, "kisCano"),
                require(d.kisAcntPrdtCd, d.id, "kisAcntPrdtCd"),
                d.kisPaper,
            )
        }
    }

    /**
     * 전역(단일 계정) 클라이언트 — `.env`(KisProperties) 자격증명 사용. 미설정(appKey 공백)이면 null.
     * 멀티유저 대신 단일 운영자용. appSecret 은 .env 평문(전역 경로의 표준 — DB 경로만 암호화).
     */
    fun defaultClient(): KisClient? {
        defaultClientCache?.let { return it }
        if (kisProperties.appKey.isBlank()) return null
        return synchronized(this) {
            defaultClientCache ?: buildClient(
                kisProperties.appKey, kisProperties.appSecret, kisProperties.cano,
                kisProperties.acntPrdtCd, kisProperties.paper,
            ).also { defaultClientCache = it }
        }
    }

    fun invalidate(userId: Long) {
        clients.remove(userId)
    }

    private fun buildClient(appKey: String, appSecret: String, cano: String, acntPrdtCd: String, paper: Boolean): KisClient {
        val baseUrl = if (paper) kisProperties.paperBaseUrl else kisProperties.realBaseUrl
        val webClient = buildWebClient(baseUrl)
        val tokenProvider = KisTokenProvider(webClient, appKey, appSecret, kisProperties.tokenRefreshSkewSeconds)
        return KisClientImpl(webClient, tokenProvider, appKey, appSecret, cano, acntPrdtCd, paper, kisProperties.custType)
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
