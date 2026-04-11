package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.ml.HyperparameterTuner
import com.trading.bot.ml.MlModelService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.security.UserSecretsService
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/ml")
class MlController(
    private val mlModelService: MlModelService,
    private val userTradingManager: UserTradingManager,
    private val userRepository: UserRepository,
    private val userSecretsService: UserSecretsService,
    private val hyperparameterTuner: HyperparameterTuner,
) {
    @PostMapping("/train")
    suspend fun train(@RequestBody req: TrainRequest): Any {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.upbitAccessKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Upbit API keys required")
        }

        val client = userTradingManager.createUpbitClient(user)
        val ticker = req.ticker ?: "KRW-BTC"
        val days = (req.days ?: 200).coerceIn(60, 200)
        val candles = client.getDayCandles(ticker, days)

        val result = mlModelService.train(
            ticker = ticker,
            candles = candles,
            targetPct = req.targetPct ?: 2.0,
            horizon = req.horizon ?: 5,
        )

        return mapOf(
            "success" to result.success,
            "error" to result.error,
            "metrics" to result.metrics?.let {
                mapOf(
                    "accuracy" to "%.1f%%".format(it.accuracy * 100),
                    "precision" to "%.1f%%".format(it.precision * 100),
                    "recall" to "%.1f%%".format(it.recall * 100),
                    "train_size" to it.trainSize,
                    "test_size" to it.testSize,
                    "positive_rate" to "%.1f%%".format(it.positiveRate * 100),
                    "top_features" to it.featureImportance.entries.take(5).map {
                        e -> mapOf("name" to e.key, "importance" to "%.2f".format(e.value))
                    },
                )
            },
        )
    }

    @GetMapping("/predict")
    suspend fun predict(@RequestParam(defaultValue = "KRW-BTC") ticker: String): Any {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.upbitAccessKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Upbit API keys required")
        }

        if (!mlModelService.hasModel(ticker)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No trained model for $ticker. Train first via POST /api/ml/train")
        }

        val client = userTradingManager.createUpbitClient(user)
        val candles = client.getDayCandles(ticker, 60)
        val prediction = mlModelService.predict(ticker, candles)
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Prediction failed")

        return mapOf(
            "ticker" to ticker,
            "signal" to if (prediction.signal) "BUY" else "HOLD",
            "confidence" to "%.1f%%".format(prediction.probability * 100),
            "key_features" to prediction.features.entries.take(5).map {
                mapOf("name" to it.key, "value" to "%.4f".format(it.value))
            },
        )
    }

    @GetMapping("/status")
    fun getModelStatus(@RequestParam(defaultValue = "KRW-BTC") ticker: String): Any {
        val hasModel = mlModelService.hasModel(ticker)
        val metrics = mlModelService.getMetrics(ticker)
        return mapOf(
            "ticker" to ticker,
            "model_trained" to hasModel,
            "metrics" to metrics?.let {
                mapOf(
                    "accuracy" to "%.1f%%".format(it.accuracy * 100),
                    "precision" to "%.1f%%".format(it.precision * 100),
                    "recall" to "%.1f%%".format(it.recall * 100),
                    "positive_rate" to "%.1f%%".format(it.positiveRate * 100),
                )
            },
        )
    }

    @PostMapping("/tune")
    suspend fun tuneHyperparameters(@RequestBody req: TrainRequest): Any {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.upbitAccessKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Upbit API keys required")
        }

        val client = userTradingManager.createUpbitClient(userSecretsService.decryptUserSecrets(user))
        val ticker = req.ticker ?: "KRW-BTC"
        val days = (req.days ?: 200).coerceIn(60, 200)
        val candles = client.getDayCandles(ticker, days)

        val tuneResult = hyperparameterTuner.tune(
            candles = candles,
            targetPct = req.targetPct ?: 2.0,
            horizon = req.horizon ?: 5,
        ) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient data for tuning")

        // Train final model with best params
        val trainResult = mlModelService.trainWithParams(
            ticker = ticker,
            candles = candles,
            params = tuneResult.bestParams,
            targetPct = req.targetPct ?: 2.0,
            horizon = req.horizon ?: 5,
        )

        return mapOf(
            "best_params" to mapOf(
                "ntrees" to tuneResult.bestParams.ntrees,
                "max_depth" to tuneResult.bestParams.maxDepth,
                "shrinkage" to tuneResult.bestParams.shrinkage,
                "subsample" to tuneResult.bestParams.subsample,
            ),
            "best_f1" to "%.4f".format(tuneResult.bestF1),
            "final_model" to mapOf(
                "success" to trainResult.success,
                "accuracy" to trainResult.metrics?.let { "%.1f%%".format(it.accuracy * 100) },
                "precision" to trainResult.metrics?.let { "%.1f%%".format(it.precision * 100) },
                "recall" to trainResult.metrics?.let { "%.1f%%".format(it.recall * 100) },
            ),
            "top_results" to tuneResult.allResults.take(5),
        )
    }
}

data class TrainRequest(
    val ticker: String? = null,
    val days: Int? = null,
    val targetPct: Double? = null,
    val horizon: Int? = null,
)
