package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.engine.BotControlPersistFailedException
import com.trading.bot.engine.reloadFailureMessage
import com.trading.bot.engine.RuntimeReloadFailedException
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.UserRepository
import com.trading.bot.security.UserSecretsService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api")
class TradingController(
    private val userTradingManager: UserTradingManager,
    private val userRepository: UserRepository,
    private val requestValidators: RequestValidators,
    private val userSecretsService: UserSecretsService,
) {

    @PostMapping("/bot/start")
    suspend fun startBot(@RequestBody(required = false) req: StartBotRequest?): Map<String, Any> {
        val userId = currentUserId()
        val tickers = req?.tickers?.let(requestValidators::normalizeMarkets)
        return failOnError(persisting { userTradingManager.startBot(userId, tickers) })
    }

    // UserTradingManager returns {"error": "..."} for precondition failures
    // (no API keys, user missing, running with another ticker list). Surface those as proper 4xx
    // so clients can branch on status instead of having to inspect the body. Codes go first so the message wording
    // never decides a status that has a code.
    private fun failOnError(result: Map<String, Any>): Map<String, Any> {
        result["error"]?.let { msg ->
            val status = when {
                result["code"] == UserTradingManager.CONFLICT_CODE -> HttpStatus.CONFLICT
                (msg as? String)?.contains("not found", ignoreCase = true) == true -> HttpStatus.NOT_FOUND
                else -> HttpStatus.BAD_REQUEST
            }
            throw ResponseStatusException(status, msg.toString())
        }
        return result
    }

    @PostMapping("/bot/stop")
    suspend fun stopBot(): Map<String, Any> {
        return persisting { userTradingManager.stopBot(currentUserId()) }
    }

    @GetMapping("/bot/status")
    suspend fun getStatus(): Map<String, Any> {
        return userTradingManager.getStatus(currentUserId())
    }

    @PostMapping("/bot/halt/clear")
    suspend fun clearHalt(@RequestBody request: ClearHaltRequest): Map<String, Any> {
        // currentUserId() 로 자기 봇만 대상 — 타인 봇 halt 해제 불가.
        val ticker = requestValidators.normalizeMarkets(listOf(request.ticker)).firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ticker: ${request.ticker}")
        return persisting { userTradingManager.clearHalt(currentUserId(), ticker) }
    }

    /** #246: 막힌 pending 해제 — 정지된 자기 봇만(도는 중이면 409). 거래소에서 주문을 확인한 뒤 부른다. */
    @PostMapping("/bot/pending/clear")
    suspend fun clearPending(@RequestBody request: ClearPendingRequest): Map<String, Any> {
        val ticker = requestValidators.normalizeMarket(request.ticker)
        val side = requestValidators.normalizeTradeSide(request.side)
        return failOnError(persisting { userTradingManager.clearPending(currentUserId(), ticker, side) })
    }

    @GetMapping("/account")
    suspend fun getAccount(): Any {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.upbitAccessKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Upbit API keys not configured")
        }
        val client = userTradingManager.createUpbitClient(userSecretsService.decryptUserSecrets(user))
        return client.getAccounts()
    }

    @PostMapping("/user/keys")
    suspend fun setUpbitKeys(@RequestBody req: UpbitKeysRequest): Map<String, String> {
        val userId = currentUserId()
        userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found")
        val accessKey = requestValidators.normalizeApiKey(req.accessKey, "accessKey")
        val secretKey = requestValidators.normalizeApiKey(req.secretKey, "secretKey")
        val (encryptedAccessKey, encryptedSecretKey) = userSecretsService.encryptUpbitKeys(accessKey, secretKey)
        saveThenReload(userId) { userRepository.updateUpbitKeys(userId, encryptedAccessKey, encryptedSecretKey).awaitSingle() }
        return mapOf("status" to "saved")
    }

    @PostMapping("/user/settings")
    suspend fun updateSettings(@RequestBody req: UserSettingsRequest): Map<String, Any> {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found")
        // 필드가 없으면 바꿀 것이 없다 — 저장도 엔진 재기동(reloadUserRuntime)도 하지 않는다(캐시된 옛 화면이 보내는 공개 설정 등).
        val requested = req.discordWebhookUrl
            ?: return mapOf("has_discord_webhook" to !user.discordWebhookUrl.isNullOrBlank())
        // 해제는 빈 문자열이다(normalizeDiscordWebhookUrl 이 null 로 만든다).
        val nextWebhook = requestValidators.normalizeDiscordWebhookUrl(requested)
        saveThenReload(userId) { userRepository.updateDiscordWebhookUrl(userId, nextWebhook).awaitSingle() }
        return mapOf("has_discord_webhook" to !nextWebhook.isNullOrBlank())
    }

    @GetMapping("/user/me")
    suspend fun getMe(): Map<String, Any?> {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found")
        return mapOf(
            "id" to user.id,
            "username" to user.username,
            "has_upbit_keys" to (!user.upbitAccessKey.isNullOrBlank()),
            "has_discord_webhook" to (!user.discordWebhookUrl.isNullOrBlank()),
        )
    }

    /**
     * 저장한 설정을 도는 엔진에 반영한다. 저장은 요청이 끊겨도 끝까지 마친다(#283) — 커밋 뒤에 끊겨 reload 가 불리지 않으면 DB 와
     * 도는 엔진이 조용히 갈린다. 끝까지 가면 끊긴 요청은 reload 의 첫 대기에서 드러나 미반영을 알린다([UserTradingManager.reloadUserRuntime]).
     * 저장이 상한을 넘으면 커밋됐는지 알 수 없다 — reload 는 DB 를 다시 읽을 뿐이라 그래도 부르고, 결과를 모른다고 알린다.
     */
    private suspend fun saveThenReload(userId: Long, save: suspend () -> Unit) {
        val saved = withContext(NonCancellable) { withTimeoutOrNull(SETTINGS_SAVE_TIMEOUT_MS) { save() } != null }
        try {
            userTradingManager.reloadUserRuntime(userId)
        } catch (e: RuntimeReloadFailedException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reloadFailureMessage(e), e)
        }
        if (!saved) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, SETTINGS_SAVE_UNCONFIRMED_MESSAGE)
    }

    internal companion object {
        // 끊긴 요청에서도 기다리는 시간이라 상한을 둔다 — 걸린 DB 호출이 요청을 무기한 붙잡지 않게.
        private const val SETTINGS_SAVE_TIMEOUT_MS = 10_000L
        const val SETTINGS_SAVE_UNCONFIRMED_MESSAGE =
            "저장이 늦어져 반영됐는지 확인하지 못했습니다. 잠시 후 다시 저장하세요."
    }
}

/** 봇 제어의 상태 저장 실패를 503 + 매니저의 안내 문구로 — 런타임 교체 실패(`setUpbitKeys`)와 같은 변환. */
private inline fun <T> persisting(block: () -> T): T =
    try {
        block()
    } catch (e: BotControlPersistFailedException) {
        throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message, e)
    }

data class StartBotRequest(val tickers: List<String>? = null)
data class ClearHaltRequest(val ticker: String)
data class ClearPendingRequest(val ticker: String, val side: String)
data class UserSettingsRequest(val discordWebhookUrl: String? = null)
data class UpbitKeysRequest(val accessKey: String, val secretKey: String)
