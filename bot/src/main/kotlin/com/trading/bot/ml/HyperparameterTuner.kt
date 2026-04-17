package com.trading.bot.ml

import com.trading.common.domain.Candle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import smile.classification.gbm
import smile.data.DataFrame
import smile.data.Tuple
import smile.data.formula.Formula
import smile.data.type.DataTypes
import smile.data.type.StructField
import smile.data.type.StructType
import smile.validation.metric.Precision
import smile.validation.metric.Recall

data class GbmParams(
    val ntrees: Int = 100,
    val maxDepth: Int = 4,
    val shrinkage: Double = 0.1,
    val subsample: Double = 0.8,
)

data class TuneResult(
    val bestParams: GbmParams,
    val bestF1: Double,
    val allResults: List<Map<String, Any>>,
)

@Component
class HyperparameterTuner {
    private val log = LoggerFactory.getLogger(javaClass)

    private val schema by lazy {
        val fields = FeatureExtractor.FEATURE_NAMES.map { StructField(it, DataTypes.DoubleType) }
            .plus(StructField("label", DataTypes.IntegerType))
        StructType(fields)
    }

    fun tune(
        candles: List<Candle>,
        targetPct: Double = 2.0,
        horizon: Int = 5,
    ): TuneResult? {
        val (features, labels) = FeatureExtractor.createDataset(candles, targetPct, horizon)
            ?: return null

        if (features.size < 60) return null

        val ntreeOptions = listOf(50, 100, 200)
        val depthOptions = listOf(3, 4, 6)
        val shrinkageOptions = listOf(0.05, 0.1, 0.2)

        var bestF1 = -1.0
        var bestParams = GbmParams()
        val allResults = mutableListOf<Map<String, Any>>()

        // Walk-forward cross-validation (3 folds)
        val foldSize = features.size / 4

        for (ntrees in ntreeOptions) {
            for (depth in depthOptions) {
                for (shrinkage in shrinkageOptions) {
                    val f1Scores = mutableListOf<Double>()

                    for (fold in 0..2) {
                        val trainEnd = foldSize * (fold + 1)
                        val valEnd = foldSize * (fold + 2)
                        if (valEnd > features.size) break

                        val trainFeatures = features.sliceArray(0 until trainEnd)
                        val trainLabels = labels.sliceArray(0 until trainEnd)
                        val valFeatures = features.sliceArray(trainEnd until valEnd)
                        val valLabels = labels.sliceArray(trainEnd until valEnd)

                        if (trainFeatures.size < 20 || valFeatures.size < 5) continue

                        try {
                            val tuples = trainFeatures.mapIndexed { i, f -> toTuple(f, trainLabels[i]) }
                            val df = DataFrame.of(tuples, schema)
                            val formula = Formula.lhs("label")
                            val model = gbm(formula, df, ntrees = ntrees, maxDepth = depth, shrinkage = shrinkage, subsample = 0.8)

                            val predictions = valFeatures.map { f ->
                                model.predict(toFeatureTuple(f))
                            }.toIntArray()

                            val precision = if (predictions.any { it == 1 }) Precision.of(valLabels, predictions) else 0.0
                            val recall = if (valLabels.any { it == 1 }) Recall.of(valLabels, predictions) else 0.0
                            val f1 = if (precision + recall > 0) 2 * precision * recall / (precision + recall) else 0.0
                            f1Scores.add(f1)
                        } catch (e: Exception) {
                            log.debug("Tuning failed for ntrees={}, depth={}, shrinkage={}: {}", ntrees, depth, shrinkage, e.message)
                        }
                    }

                    if (f1Scores.isNotEmpty()) {
                        val avgF1 = f1Scores.average()
                        allResults.add(
                            mapOf(
                                "ntrees" to ntrees,
                                "maxDepth" to depth,
                                "shrinkage" to shrinkage,
                                "avg_f1" to avgF1,
                                "folds" to f1Scores.size,
                            )
                        )

                        if (avgF1 > bestF1) {
                            bestF1 = avgF1
                            bestParams = GbmParams(ntrees, depth, shrinkage)
                        }
                    }
                }
            }
        }

        if (bestF1 < 0) return null

        log.info("Hyperparameter tuning complete. Best F1={:.4f}, params={}", bestF1, bestParams)
        return TuneResult(
            bestParams = bestParams,
            bestF1 = bestF1,
            allResults = allResults.sortedByDescending { it["avg_f1"] as Double },
        )
    }

    private fun toTuple(features: DoubleArray, label: Int): Tuple {
        val values: Array<Any> = features.toList().map { it as Any }.plus(label as Any).toTypedArray()
        return Tuple.of(values, schema)
    }

    private fun toFeatureTuple(features: DoubleArray): Tuple {
        val featureSchema = StructType(FeatureExtractor.FEATURE_NAMES.map { StructField(it, DataTypes.DoubleType) })
        val values = features.map { it as Any }.toTypedArray()
        return Tuple.of(values, featureSchema)
    }
}
