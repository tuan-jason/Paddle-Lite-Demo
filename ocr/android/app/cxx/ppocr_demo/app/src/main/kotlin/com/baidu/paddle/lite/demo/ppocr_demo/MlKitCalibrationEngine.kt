package com.baidu.paddle.lite.demo.ppocr_demo

import kotlin.math.roundToInt

internal enum class MlKitCalibrationStatus {
    FULL,
    PARTIAL,
    GEOMETRY_ONLY,
    MINIMAL
}

internal data class MlKitCalibrationProfile(
    val highConfThreshold: Float = 0.75f,
    val gapLargeMultiplier: Float = 1.4f,
    val gapVeryLargeMultiplier: Float = 2.8f,
    val marginBinSizePx: Float = 6f,
    val anchorMinCount: Int = 3,
    val fallbackGapDivisorLarge: Int = 40,
    val fallbackGapDivisorVeryLarge: Int = 20,
    val winsorizeLowPercentile: Float = 0.10f,
    val winsorizeHighPercentile: Float = 0.90f
)

internal data class MlKitCalibrationData(
    val status: MlKitCalibrationStatus,
    val medianLineHeight: Float,
    val modalLeftMargin: Float,
    val gapLarge: Float,
    val gapVeryLarge: Float,
    val imageWidth: Float,
    val imageHeight: Float,
    val highConfidenceLineCount: Int,
    val anchorCandidateCount: Int
)

internal object MlKitCalibrationEngine {
    val DEFAULT_PROFILE = MlKitCalibrationProfile()

    fun calibrate(
        lines: List<MlKitLineGeom>,
        imageWidth: Float,
        imageHeight: Float,
        profile: MlKitCalibrationProfile = DEFAULT_PROFILE
    ): MlKitCalibrationData {
        if (lines.size < 2 || imageHeight <= 0f || imageWidth <= 0f) {
            return minimalFallback(imageWidth, imageHeight, profile)
        }

        val heights = lines.map { it.lineHeightPx }.filter { it > 0f }
        if (heights.isEmpty()) return minimalFallback(imageWidth, imageHeight, profile)

        val robustHeights = winsorize(
            heights,
            lowPercentile = profile.winsorizeLowPercentile,
            highPercentile = profile.winsorizeHighPercentile
        )
        val medianHeight = median(robustHeights)
        if (medianHeight <= 0f) return minimalFallback(imageWidth, imageHeight, profile)

        val modalMargin = computeModalLeftMargin(lines.map { it.rect.left }, profile.marginBinSizePx)
        val gapLarge = profile.gapLargeMultiplier * medianHeight
        val gapVeryLarge = profile.gapVeryLargeMultiplier * medianHeight

        val scoreAvailable = lines.filter { !it.normalized.score.isNaN() }
        val highConfidence = scoreAvailable.filter { it.normalized.score >= profile.highConfThreshold }
        val anchorCandidates = highConfidence.filter {
            SignalFusionQuestionDetector.ANY_NUMBERED_PATTERN.containsMatchIn(it.normalized.text.trim())
        }

        val status = when {
            highConfidence.isEmpty() -> MlKitCalibrationStatus.GEOMETRY_ONLY
            anchorCandidates.size < profile.anchorMinCount -> MlKitCalibrationStatus.PARTIAL
            else -> MlKitCalibrationStatus.FULL
        }

        return MlKitCalibrationData(
            status = status,
            medianLineHeight = medianHeight,
            modalLeftMargin = modalMargin,
            gapLarge = gapLarge,
            gapVeryLarge = gapVeryLarge,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            highConfidenceLineCount = highConfidence.size,
            anchorCandidateCount = anchorCandidates.size
        )
    }

    private fun minimalFallback(
        imageWidth: Float,
        imageHeight: Float,
        profile: MlKitCalibrationProfile
    ): MlKitCalibrationData {
        val safeImageWidth = imageWidth.coerceAtLeast(1f)
        val safeImageHeight = imageHeight.coerceAtLeast(1f)
        return MlKitCalibrationData(
            status = MlKitCalibrationStatus.MINIMAL,
            medianLineHeight = safeImageHeight / profile.fallbackGapDivisorLarge.toFloat(),
            modalLeftMargin = 0f,
            gapLarge = safeImageHeight / profile.fallbackGapDivisorLarge.toFloat(),
            gapVeryLarge = safeImageHeight / profile.fallbackGapDivisorVeryLarge.toFloat(),
            imageWidth = safeImageWidth,
            imageHeight = safeImageHeight,
            highConfidenceLineCount = 0,
            anchorCandidateCount = 0
        )
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    private fun computeModalLeftMargin(values: List<Float>, binSize: Float): Float {
        if (values.isEmpty() || binSize <= 0f) return 0f
        return values
            .groupBy { (it / binSize).roundToInt() }
            .maxByOrNull { it.value.size }
            ?.let { (bin, _) -> bin * binSize }
            ?: 0f
    }

    private fun winsorize(values: List<Float>, lowPercentile: Float, highPercentile: Float): List<Float> {
        if (values.size < 3) return values
        val sorted = values.sorted()
        val low = percentile(sorted, lowPercentile.coerceIn(0f, 1f))
        val high = percentile(sorted, highPercentile.coerceIn(0f, 1f))
        return sorted.map { it.coerceIn(low, high) }
    }

    private fun percentile(sorted: List<Float>, p: Float): Float {
        if (sorted.isEmpty()) return 0f
        if (sorted.size == 1) return sorted[0]
        val pos = p * (sorted.size - 1)
        val left = pos.toInt().coerceIn(0, sorted.size - 1)
        val right = (left + 1).coerceAtMost(sorted.size - 1)
        if (left == right) return sorted[left]
        val weight = pos - left
        return sorted[left] * (1f - weight) + sorted[right] * weight
    }
}
