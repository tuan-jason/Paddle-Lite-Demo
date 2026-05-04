package com.baidu.paddle.lite.demo.ppocr_demo

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MlKitSignalFusionQuestionDetectorTask4Test {

    private val detector = MlKitSignalFusionQuestionDetector(language = "en")
    private val pageHeight = 2000f

    @Test
    fun tierDecisions_tier1Tier2Tier3_matchSyntheticCases() {
        val tier1Lines = arrayOf(
            skewLine(40, 100, 900, 140, "1. First", 0.95f),
            skewLine(40, 160, 900, 200, "2. Second", 0.95f)
        )
        assertEquals(2, detector.detectFromLines(tier1Lines, pageHeight).size)

        val tier2Lines = arrayOf(
            skewLine(40, 100, 900, 140, "1. First", 0.95f),
            skewLine(40, 158, 900, 198, "Question 2: Find x", 0.95f)
        )
        assertEquals(2, detector.detectFromLines(tier2Lines, pageHeight).size)

        val tier3Lines = arrayOf(
            skewLine(40, 100, 900, 140, "1. First", 0.95f),
            skewLine(40, 158, 900, 198, "Where is x?", 0.95f)
        )
        assertEquals(2, detector.detectFromLines(tier3Lines, pageHeight).size)
    }

    @Test
    fun horizontalRule_usesRelativeThicknessAndWidthFraction() {
        val wideThinRule = arrayOf(
            skewLine(0, 100, 1000, 140, "1. First", 0.95f),
            axisLine(0, 160, 900, 164, "----", 0.95f)
        )
        assertEquals(2, detector.detectFromLines(wideThinRule, pageHeight).size)

        val narrowThinRule = arrayOf(
            skewLine(0, 100, 1000, 140, "1. First", 0.95f),
            axisLine(0, 160, 250, 164, "----", 0.95f)
        )
        assertEquals(1, detector.detectFromLines(narrowThinRule, pageHeight).size)
    }

    @Test
    fun rectFallbackLines_doNotOverTriggerGeometryOnlySplit() {
        val lines = arrayOf(
            axisLine(20, 100, 980, 140, "1. Intro", 0.95f),
            axisLine(20, 220, 980, 260, "continuation text", 0.95f)
        )

        val regions = detector.detectFromLines(lines, pageHeight)
        assertEquals(1, regions.size)
        assertEquals(2, regions[0].lineBoxes.size)
    }

    private fun skewLine(
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
                intArrayOf(right, top + 1),
                intArrayOf(right - 1, bottom),
                intArrayOf(left + 1, bottom - 1)
            ),
            text,
            score
        )
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
