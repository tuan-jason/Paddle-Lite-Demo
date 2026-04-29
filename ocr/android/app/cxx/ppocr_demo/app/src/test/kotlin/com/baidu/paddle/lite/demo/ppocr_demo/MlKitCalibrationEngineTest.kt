package com.baidu.paddle.lite.demo.ppocr_demo

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MlKitCalibrationEngineTest {

    @Test
    fun calibrate_statusTransitions_forHighLowMissingConfidence() {
        val imageW = 1200
        val imageH = 2000

        val full = detector().calibrateFromLines(
            lines = arrayOf(
                line(40, 100, 500, 140, "1. Alpha", 0.93f),
                line(42, 170, 520, 210, "2. Beta", 0.91f),
                line(41, 240, 530, 280, "3. Gamma", 0.90f)
            ),
            imageWidth = imageW,
            imageHeight = imageH
        )
        assertEquals(MlKitCalibrationStatus.FULL, full.status)

        val partial = detector().calibrateFromLines(
            lines = arrayOf(
                line(40, 100, 500, 140, "Find x.", 0.93f),
                line(42, 170, 520, 210, "Find y.", 0.91f),
                line(41, 240, 530, 280, "Find z.", 0.90f)
            ),
            imageWidth = imageW,
            imageHeight = imageH
        )
        assertEquals(MlKitCalibrationStatus.PARTIAL, partial.status)

        val geometryOnly = detector().calibrateFromLines(
            lines = arrayOf(
                line(40, 100, 500, 140, "1. Alpha", 0.20f),
                line(42, 170, 520, 210, "2. Beta", Float.NaN),
                line(41, 240, 530, 280, "3. Gamma", 0.10f)
            ),
            imageWidth = imageW,
            imageHeight = imageH
        )
        assertEquals(MlKitCalibrationStatus.GEOMETRY_ONLY, geometryOnly.status)

        val minimal = detector().calibrateFromLines(
            lines = arrayOf(
                line(40, 100, 500, 140, "Only one line", 0.95f)
            ),
            imageWidth = imageW,
            imageHeight = imageH
        )
        assertEquals(MlKitCalibrationStatus.MINIMAL, minimal.status)
    }

    @Test
    fun calibrate_gapAndMarginValues_matchFixtureExpectations() {
        val calibration = detector().calibrateFromLines(
            lines = arrayOf(
                line(10, 100, 260, 140, "1. A", 0.95f), // h=40, left=10
                line(12, 170, 262, 210, "2. B", 0.95f), // h=40, left=12
                line(11, 240, 261, 280, "3. C", 0.95f), // h=40, left=11
                line(70, 320, 320, 380, "noise", 0.95f) // h=60, left=70
            ),
            imageWidth = 1000,
            imageHeight = 2000
        )

        // Robust median should stay near dominant line-height mode (40).
        assertEquals(40f, calibration.medianLineHeight, 0.001f)
        assertEquals(56f, calibration.gapLarge, 0.001f)     // 1.4 * 40
        assertEquals(112f, calibration.gapVeryLarge, 0.001f) // 2.8 * 40

        // Modal left margin with bin=6: 10/12/11 all map to bin 2 => 12.
        assertEquals(12f, calibration.modalLeftMargin, 0.001f)
    }

    private fun detector(): MlKitSignalFusionQuestionDetector = MlKitSignalFusionQuestionDetector()

    private fun line(
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
