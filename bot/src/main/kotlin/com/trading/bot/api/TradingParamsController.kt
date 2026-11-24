package com.trading.bot.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.trading.bot.auth.currentUserId
import com.trading.bot.domain.UserTradingParams
import com.trading.bot.engine.BotChangeFailedException
import com.trading.bot.engine.BotControlPersistFailedException
import com.trading.bot.engine.RuntimeReloadFailedException
import com.trading.bot.engine.TradingManagers
import com.trading.bot.engine.reloadFailureMessage
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 사용자 매매 값(업비트 봇). 빠진 항목은 env 기본이다(PUT 은 전체 교체 — 모르는 키는 400 이라 오타가 한도를 풀지 않는다). 저장하면
 * 보유 포지션의 청산 값도 바뀌어 곧바로 팔릴 수 있으므로, 바뀌는 포지션이 있으면 미리보기의 `confirm_token` 이 지금 저장할 내용과
 * 맞을 때만 저장하고 아니면 409 와 새 미리보기를 돌려준다.
 */
@RestController
@RequestMapping("/api/bot/params")
class TradingParamsController(
    private val managers: TradingManagers,
    private val prices: BoardPrices,
) {
    private val manager get() = managers.get(Exchange.UPBIT)

    @GetMapping
    suspend fun params(): Map<String, Any?> {
        val saved = manager.savedParams(currentUserId())
        val defaults = manager.defaultProfile()
        return mapOf("defaults" to view(defaults), "params" to saved, "effective" to view(saved.applyTo(defaults)))
    }

    @PostMapping("/preview")
    suspend fun preview(@RequestBody req: ParamsRequest): Map<String, Any> {
        val params = validated(req)
        val preview = manager.previewParams(currentUserId(), params, prices::price)
        return mapOf("changes" to preview.changes, "confirm_token" to preview.confirmToken, "warnings" to manager.paramsWarnings(params))
    }

    @PutMapping
    suspend fun save(@RequestBody req: ParamsRequest): ResponseEntity<Map<String, Any>> {
        val params = validated(req)
        val result = try {
            manager.saveParams(currentUserId(), params, req.confirmToken, prices::price)
        } catch (e: BotChangeFailedException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message, e)
        } catch (e: BotControlPersistFailedException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message, e)
        } catch (e: RuntimeReloadFailedException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reloadFailureMessage(e), e)
        }
        result["error"]?.let { throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, it.toString()) }
        val body = result + ("warnings" to manager.paramsWarnings(params))
        val status = if (result["status"] == "confirm_required") HttpStatus.CONFLICT else HttpStatus.OK
        return ResponseEntity.status(status).body(body)
    }

    private fun validated(req: ParamsRequest): UserTradingParams {
        val params = req.toParams()
        // 범위 위반은 env 키(trading.max-loss-pct)로 나오므로 화면이 보내는 키(max_loss_pct)로 바꿔 알린다.
        val problems = manager.paramsProblems(params).map {
            if (it.startsWith("trading.")) it.substringBefore('=').removePrefix("trading.").replace('-', '_') + "=" + it.substringAfter('=') else it
        }
        if (problems.isNotEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid params: ${problems.joinToString(", ")}")
        return params
    }

    private fun view(p: TradingProperties) = mapOf(
        "k_value" to p.kValue,
        "invest_ratio" to p.investRatio,
        "max_invest_amount" to p.maxInvestAmount,
        "take_profit_pct" to p.takeProfitPct,
        "max_loss_pct" to p.maxLossPct,
        "trailing_stop_pct" to p.trailingStopPct,
        "trailing_arm_pct" to p.trailingArmPct,
        "max_hold_days" to p.maxHoldDays,
    )

    @JsonIgnoreProperties(ignoreUnknown = false)
    data class ParamsRequest(
        @get:JsonProperty("k_value") @param:JsonProperty("k_value") val kValue: Double? = null,
        val investRatio: Double? = null,
        val maxInvestAmount: Double? = null,
        val takeProfitPct: Double? = null,
        val maxLossPct: Double? = null,
        val trailingStopPct: Double? = null,
        val trailingArmPct: Double? = null,
        val maxHoldDays: Int? = null,
        val confirmToken: String? = null,
    ) {
        fun toParams() = UserTradingParams(kValue, investRatio, maxInvestAmount, takeProfitPct, maxLossPct, trailingStopPct, trailingArmPct, maxHoldDays)
    }
}
