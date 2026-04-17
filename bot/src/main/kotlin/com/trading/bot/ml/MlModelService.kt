package com.trading.bot.ml

import com.trading.bot.config.MlProperties
import com.trading.common.domain.Candle
import jakarta.annotation.PostConstruct
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import smile.classification.GradientTreeBoost
import smile.classification.gbm
import smile.data.DataFrame
import smile.data.Tuple
import smile.data.formula.Formula
import smile.data.type.DataTypes
import smile.data.type.StructField
import smile.data.type.StructType
import smile.validation.metric.Accuracy
import smile.validation.metric.Precision
import smile.validation.metric.Recall

data class ModelMetrics(
    val accuracy: Double,
    val precision: Double,
    val recall: Double,
    val trainSize: Int,
    val testSize: Int,
    val positiveRate: Double,
    val featureImportance: Map<String, Double>,
)

data class TrainResult(
    val success: Boolean,
    val metrics: ModelMetrics? = null,
    val error: String? = null,
)

data class PredictionResult(
    val signal: Boolean,
    val probability: Double,
    val features: Map<String, Double>,
)

@Service
class MlModelService(
    private val mlProperties: MlProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val models = ConcurrentHashMap<String, GradientTreeBoost>()
    private val lastMetrics = ConcurrentHashMap<String, ModelMetrics>()
    private val schema by lazy { buildSchema() }

    @PostConstruct
    fun loadModelsOnStartup() {
        if (!mlProperties.autoLoadOnStartup) return
        val dir = File(mlProperties.modelDir)
        if (!dir.exists()) {
            dir.mkdirs()
            return
        }
        dir.listFiles { f -> f.extension == "model" }?.forEach { file ->
            try {
                val ticker = file.nameWithoutExtension
                ObjectInputStream(file.inputStream().buffered()).use { ois ->
                    val model = ois.readObject() as GradientTreeBoost
                    models[ticker] = model
                    log.info("Loaded ML model for {} from disk", ticker)
                }
            } catch (e: Exception) {
                log.warn("Failed to load model {}: {}", file.name, e.message)
            }
        }
    }

    fun saveModel(ticker: String) {
        val model = models[ticker] ?: return
        val dir = File(mlProperties.modelDir)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$ticker.model")
        try {
            ObjectOutputStream(file.outputStream().buffered()).use { oos ->
                oos.writeObject(model)
            }
            log.info("Saved ML model for {} to {}", ticker, file.absolutePath)
        } catch (e: Exception) {
            log.error("Failed to save model for {}: {}", ticker, e.message)
        }
    }

    private fun buildSchema(): StructType {
        val fields = FeatureExtractor.FEATURE_NAMES.map { StructField(it, DataTypes.DoubleType) }
            .plus(StructField("label", DataTypes.IntegerType))
        return StructType(fields)
    }

    private fun featureSchema(): StructType {
        val fields = FeatureExtractor.FEATURE_NAMES.map { StructField(it, DataTypes.DoubleType) }
        return StructType(fields)
    }

    private fun toTuple(features: DoubleArray, label: Int): Tuple {
        val values: Array<Any> = features.toList().map { it as Any }.plus(label as Any).toTypedArray()
        return Tuple.of(values, schema)
    }

    private fun toFeatureTuple(features: DoubleArray): Tuple {
        val values = features.map { it as Any }.toTypedArray()
        return Tuple.of(values, featureSchema())
    }

    fun train(
        ticker: String,
        candles: List<Candle>,
        targetPct: Double = 2.0,
        horizon: Int = 5,
    ): TrainResult {
        try {
            val (features, labels) = FeatureExtractor.createDataset(candles, targetPct, horizon)
                ?: return TrainResult(false, error = "Insufficient data for training")

            val totalSamples = features.size
            val positives = labels.count { it == 1 }
            log.info("Training ML model for {}: {} samples, {} positive ({}%)",
                ticker, totalSamples, positives, "%.1f".format(positives.toDouble() / totalSamples * 100))

            // Train/test split (80/20, chronological)
            val splitIdx = (totalSamples * 0.8).toInt()
            if (splitIdx < 15 || totalSamples - splitIdx < 5) {
                return TrainResult(false, error = "Not enough data: $totalSamples samples")
            }

            // Build DataFrame
            val tuples = features.mapIndexed { i, f -> toTuple(f, labels[i]) }
            val trainTuples = tuples.subList(0, splitIdx)
            val testFeatures = features.sliceArray(splitIdx until totalSamples)
            val testLabels = labels.sliceArray(splitIdx until totalSamples)

            val trainDf = DataFrame.of(trainTuples, schema)

            // Train GBM
            val formula = Formula.lhs("label")
            val model = gbm(
                formula, trainDf,
                ntrees = 100,
                maxDepth = 4,
                shrinkage = 0.1,
                subsample = 0.8,
            )

            // Evaluate on test set
            val predictions = testFeatures.map { f ->
                model.predict(toFeatureTuple(f))
            }.toIntArray()

            val accuracy = Accuracy.of(testLabels, predictions)
            val precision = if (predictions.any { it == 1 }) Precision.of(testLabels, predictions) else 0.0
            val recall = if (testLabels.any { it == 1 }) Recall.of(testLabels, predictions) else 0.0

            // Feature importance
            val importance = model.importance()
            val featureImportance = FeatureExtractor.FEATURE_NAMES.mapIndexed { i, name ->
                name to if (i < importance.size) importance[i] else 0.0
            }.sortedByDescending { it.second }.toMap()

            val metrics = ModelMetrics(
                accuracy = accuracy,
                precision = precision,
                recall = recall,
                trainSize = splitIdx,
                testSize = totalSamples - splitIdx,
                positiveRate = positives.toDouble() / totalSamples,
                featureImportance = featureImportance,
            )

            models[ticker] = model
            lastMetrics[ticker] = metrics
            saveModel(ticker)

            log.info("ML model trained for {}: acc={:.1f}%, prec={:.1f}%, recall={:.1f}%",
                ticker, accuracy * 100, precision * 100, recall * 100)

            return TrainResult(true, metrics)
        } catch (e: Exception) {
            log.error("Failed to train ML model for {}: {}", ticker, e.message, e)
            return TrainResult(false, error = e.message)
        }
    }

    fun trainWithParams(
        ticker: String,
        candles: List<Candle>,
        params: GbmParams,
        targetPct: Double = 2.0,
        horizon: Int = 5,
    ): TrainResult {
        try {
            val (features, labels) = FeatureExtractor.createDataset(candles, targetPct, horizon)
                ?: return TrainResult(false, error = "Insufficient data for training")

            val totalSamples = features.size
            val splitIdx = (totalSamples * 0.8).toInt()
            if (splitIdx < 15 || totalSamples - splitIdx < 5) {
                return TrainResult(false, error = "Not enough data: $totalSamples samples")
            }

            val tuples = features.mapIndexed { i, f -> toTuple(f, labels[i]) }
            val trainTuples = tuples.subList(0, splitIdx)
            val testFeatures = features.sliceArray(splitIdx until totalSamples)
            val testLabels = labels.sliceArray(splitIdx until totalSamples)

            val trainDf = DataFrame.of(trainTuples, schema)
            val formula = Formula.lhs("label")
            val model = gbm(
                formula, trainDf,
                ntrees = params.ntrees,
                maxDepth = params.maxDepth,
                shrinkage = params.shrinkage,
                subsample = params.subsample,
            )

            val predictions = testFeatures.map { f -> model.predict(toFeatureTuple(f)) }.toIntArray()
            val accuracy = Accuracy.of(testLabels, predictions)
            val precision = if (predictions.any { it == 1 }) Precision.of(testLabels, predictions) else 0.0
            val recall = if (testLabels.any { it == 1 }) Recall.of(testLabels, predictions) else 0.0

            val importance = model.importance()
            val featureImportance = FeatureExtractor.FEATURE_NAMES.mapIndexed { i, name ->
                name to if (i < importance.size) importance[i] else 0.0
            }.sortedByDescending { it.second }.toMap()

            val positives = labels.count { it == 1 }
            val metrics = ModelMetrics(
                accuracy = accuracy,
                precision = precision,
                recall = recall,
                trainSize = splitIdx,
                testSize = totalSamples - splitIdx,
                positiveRate = positives.toDouble() / totalSamples,
                featureImportance = featureImportance,
            )

            models[ticker] = model
            lastMetrics[ticker] = metrics
            saveModel(ticker)

            return TrainResult(true, metrics)
        } catch (e: Exception) {
            return TrainResult(false, error = e.message)
        }
    }

    fun predict(ticker: String, candles: List<Candle>): PredictionResult? {
        val model = models[ticker] ?: return null
        val features = FeatureExtractor.extract(candles) ?: return null

        val tuple = toFeatureTuple(features)
        val prediction = model.predict(tuple)
        val posteriors = DoubleArray(2)
        model.predict(tuple, posteriors)
        val probability = posteriors[1]

        val featureMap = FeatureExtractor.FEATURE_NAMES.mapIndexed { i, name ->
            name to features[i]
        }.toMap()

        return PredictionResult(
            signal = prediction == 1,
            probability = probability,
            features = featureMap,
        )
    }

    fun hasModel(ticker: String): Boolean = models.containsKey(ticker)
    fun getMetrics(ticker: String): ModelMetrics? = lastMetrics[ticker]
    fun getTrainedTickers(): Set<String> = models.keys.toSet()
}
