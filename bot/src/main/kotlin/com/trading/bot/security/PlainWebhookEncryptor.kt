package com.trading.bot.security

import com.trading.bot.persistence.UserRepository
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 암호화하기 전에 평문으로 저장된 사용자 Discord webhook 을 기동 때 암호문으로 바꾼다. 키가 앱 env 에만 있어
 * Flyway 마이그레이션으로는 할 수 없다. 키가 틀렸거나 임시 키면 되돌릴 수 없게 암호화하므로 그때는 건너뛴다.
 */
// TODO: 운영 DB 의 평문 webhook 이 0건으로 확인되고 #401 이전 이미지로 돌아갈 일이 없으면 지운다 (webhook-encrypt plan Acceptance 5)
@Component
class PlainWebhookEncryptor(
    private val users: UserRepository,
    private val secrets: UserSecretsService,
    private val keys: SecretKeyMaterialProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("plain-webhook-encryptor"))

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        scope.launch {
            try {
                val encrypted = encryptRemaining()
                if (encrypted > 0) log.info("평문 Discord webhook {}개를 암호화했다", encrypted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("평문 Discord webhook 을 암호화하지 못했다 — 다음 기동 때 다시 한다: {}", e.javaClass.simpleName)
            }
        }
    }

    @PreDestroy
    fun stop() = scope.cancel()

    suspend fun encryptRemaining(): Int {
        if (!keys.encryptionSecretConfigured()) return 0
        val plain = users.findWithPlainWebhook().collectList().awaitSingle()
        if (plain.isEmpty()) return 0
        val existing = users.findAnyEncryptedSecret().awaitSingleOrNull()
        if (existing != null && !secrets.decryptsWithCurrentKey(existing)) {
            log.error("지금 암호화 키로 기존 암호문을 풀지 못해 평문 Discord webhook 암호화를 건너뛴다 — APP_ENCRYPTION_SECRET 을 확인한다")
            return 0
        }
        var encrypted = 0
        for (user in plain) {
            try {
                encrypted += users.replaceWebhookIfUnchanged(user.id!!, user.discordWebhookUrl!!, secrets.encryptWebhook(user.discordWebhookUrl)!!).awaitSingle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("사용자 {} 의 평문 Discord webhook 을 암호화하지 못했다 — 다음 기동 때 다시 한다: {}", user.id, e.javaClass.simpleName)
            }
        }
        return encrypted
    }
}
