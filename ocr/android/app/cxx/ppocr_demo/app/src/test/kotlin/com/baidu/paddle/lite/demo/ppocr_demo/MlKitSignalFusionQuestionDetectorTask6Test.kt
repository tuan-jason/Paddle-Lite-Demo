package com.baidu.paddle.lite.demo.ppocr_demo

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MlKitSignalFusionQuestionDetectorTask6Test {

    private val pageHeight = 2000f
    private val detector = MlKitSignalFusionQuestionDetector(language = "en")
    private val legacy = SignalFusionQuestionDetector(language = "en")

    @Test
    fun integration_mlKitDetectorPathInvoked_whenFeatureEnabled() {
        val before = OcrFeatureFlags.USE_MLKIT_QUESTION_DETECTOR
        try {
            OcrFeatureFlags.USE_MLKIT_QUESTION_DETECTOR = true

            val lines = arrayOf(
                axisLine(20, 100, 980, 140, "1. Intro", 0.95f),
                axisLine(20, 220, 980, 260, "continuation text", 0.95f)
            )

            val regions = detector.detectFromLines(lines, pageHeight)
            assertEquals(1, regions.size)
        } finally {
            OcrFeatureFlags.USE_MLKIT_QUESTION_DETECTOR = before
        }
    }

    @Test
    fun integration_legacyFallbackPathRemainsUnchanged_whenFeatureDisabled() {
        val before = OcrFeatureFlags.USE_MLKIT_QUESTION_DETECTOR
        try {
            OcrFeatureFlags.USE_MLKIT_QUESTION_DETECTOR = false

            val lines = arrayOf(
                axisLine(20, 100, 980, 140, "1. Intro", 0.95f),
                axisLine(20, 220, 980, 260, "continuation text", 0.95f)
            )

            val routed = detector.detectFromLines(lines, pageHeight)
            val expectedLegacy = legacy.detectFromLines(lines, pageHeight)

            assertEquals(expectedLegacy, routed)
        } finally {
            OcrFeatureFlags.USE_MLKIT_QUESTION_DETECTOR = before
        }
    }

    private fun axisLine(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        text: String,
        score: Float
    ): OcrResult {
        return OcrResult(
            arrayOf(
                intArrayOf(left, top),
                intArrayOf(right, top),
                intArrayOf(right, bottom),
                intArrayOf(left, bottom)
            ),
            text,
            score
        )
    }
}
