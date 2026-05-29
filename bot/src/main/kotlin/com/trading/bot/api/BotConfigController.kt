package com.trading.bot.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.auth.currentUserId
import com.trading.bot.persistence.BotConfigRepository
import com.trading.bot.persistence.entity.BotConfigEntity
import com.trading.common.domain.Exchange
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
@RequestMapping("/api/bot")
class BotConfigController(
    private val botConfigRepository: BotConfigRepository,
    private val requestValidators: RequestValidators,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private const val MAX_CONFIGS_PER_USER = 50
        private const val MAX_PARAMETERS_LENGTH = 4000
    }

    @GetMapping("/configs")
    suspend fun getConfigs(): List<BotConfigResponse> {
        val userId = currentUserId()
        return botConfigRepository.findByUserId(userId)
            .take(MAX_CONFIGS_PER_USER.toLong())
            .collectList()
            .awaitSingle()
            .map { it.toResponse() }
    }

    @PostMapping("/config")
    suspend fun createConfig(@RequestBody request: BotConfigRequest): BotConfigResponse {
        val userId = currentUserId()

        val exchange = try {
            Exchange.valueOf(request.exchange.trim().uppercase())
        } catch (_: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid exchange: ${request.exchange}")
        }
        val market = requestValidators.normalizeMarket(request.market)
        val strategy = requestValidators.normalizeStrategy(request.strategy)
        val parameters = validateParameters(request.parameters)

        // per-user 상한 — 무제한 생성에 의한 메모리/응답 팽창 방지.
        val existing = botConfigRepository.findByUserId(userId).collectList().awaitSingle()
        if (existing.size >= MAX_CONFIGS_PER_USER) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Maximum $MAX_CONFIGS_PER_USER configs per user")
        }

        val entity = BotConfigEntity(
            userId = userId,
            exchange = exchange.name,
            market = market,
            strategy = strategy,
            parameters = parameters,
            enabled = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

        return botConfigRepository.save(entity)
            .awaitSingle()
            .toResponse()
    }

    // parameters 는 JSONB 컬럼이므로 유효한 JSON 인지 미리 검증해 insert 시 500(캐스트 에러)을 방지.
    private fun validateParameters(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return "{}"
        if (value.length > MAX_PARAMETERS_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "parameters too large")
        }
        return try {
            objectMapper.readTree(value)
            value
        } catch (_: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "parameters must be valid JSON")
        }
    }

    @DeleteMapping("/config/{id}")
    suspend fun deleteConfig(@PathVariable id: Long) {
        val userId = currentUserId()
        val deleted = botConfigRepository.deleteByIdAndUserId(id, userId).awaitSingleOrNull() ?: 0
        if (deleted == 0L) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Config not found")
        }
    }

    data class BotConfigRequest(
        val exchange: String,
        val market: String,
        val strategy: String,
        val parameters: String? = null,
    )

    data class BotConfigResponse(
        val id: Long?,
        val exchange: String,
        val market: String,
        val strategy: String,
        val parameters: String,
        val enabled: Boolean,
        @JsonProperty("created_at") val createdAt: Instant,
    )

    private fun BotConfigEntity.toResponse() = BotConfigResponse(
        id = id,
        exchange = exchange,
        market = market,
        strategy = strategy,
        parameters = parameters,
        enabled = enabled,
        createdAt = createdAt,
    )
}
