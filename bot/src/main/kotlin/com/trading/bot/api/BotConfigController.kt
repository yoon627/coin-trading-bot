package com.trading.bot.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.trading.bot.auth.currentUserId
import com.trading.bot.persistence.BotConfigRepository
import com.trading.bot.persistence.entity.BotConfigEntity
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
) {

    @GetMapping("/configs")
    suspend fun getConfigs(): List<BotConfigResponse> {
        val userId = currentUserId()
        return botConfigRepository.findByUserId(userId)
            .collectList()
            .awaitSingle()
            .map { it.toResponse() }
    }

    @PostMapping("/config")
    suspend fun createConfig(@RequestBody request: BotConfigRequest): BotConfigResponse {
        val userId = currentUserId()

        val entity = BotConfigEntity(
            userId = userId,
            exchange = request.exchange,
            market = request.market,
            strategy = request.strategy,
            parameters = request.parameters ?: "{}",
            enabled = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

        return botConfigRepository.save(entity)
            .awaitSingle()
            .toResponse()
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
