package com.trading.bot.ml

import com.trading.bot.client.UpbitClient
import com.trading.bot.config.MlProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

@Component
class MlRetrainScheduler(
    private val mlModelService: MlModelService,
    private val mlProperties: MlProperties,
    private val upbitWebClient: WebClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${ml.retrain-cron:0 0 4 * * *}")
    fun retrainModels() {
        if (!mlProperties.autoRetrainEnabled) return

        log.info("Starting scheduled ML model retraining...")

        // Create unauthenticated client for public candle data
        val publicClient = UpbitClient(upbitWebClient, null)

        val tickersToRetrain = mlModelService.getTrainedTickers()
        if (tickersToRetrain.isEmpty()) {
            log.info("No trained models to retrain")
            return
        }

        for (ticker in tickersToRetrain) {
            try {
                val candles = kotlinx.coroutines.runBlocking {
                    publicClient.getDayCandles(ticker, 200)
                }

                if (candles.size < 60) {
                    log.warn("Insufficient candles for {}: {} (need 60+)", ticker, candles.size)
                    continue
                }

                val oldMetrics = mlModelService.getMetrics(ticker)
                val result = mlModelService.train(ticker, candles)

                if (result.success) {
                    log.info(
                        "Retrained model for {}: acc={:.1f}% (was {:.1f}%)",
                        ticker,
                        (result.metrics?.accuracy ?: 0.0) * 100,
                        (oldMetrics?.accuracy ?: 0.0) * 100,
                    )
                } else {
                    log.warn("Failed to retrain model for {}: {}", ticker, result.error)
                }
            } catch (e: Exception) {
                log.error("Error retraining model for {}: {}", ticker, e.message)
            }
        }

        log.info("Scheduled ML model retraining complete")
    }
}
