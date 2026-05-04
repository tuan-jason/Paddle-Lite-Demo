package com.baidu.paddle.lite.demo.ppocr_demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MlKitSignalFusionQuestionDetectorTask5Test {

    private val detector = MlKitSignalFusionQuestionDetector(language = "en")
    private val pageHeight = 2000f

    @Test
    fun grouping_indexSequenceAndRegionBoundaries_areCorrect() {
        val lines = arrayOf(
            line(10, 100, 100, 130, "1. First question", 0.90f),
            line(20, 140, 120, 170, "continuation one", 0.70f),
            line(12, 300, 110, 330, "2. Second question", 1.00f),
            line(15, 340, 130, 370, "continuation two", 0.80f)
        )

        val regions = detector.detectFromLines(lines, pageHeight)

        assertEquals(2, regions.size)
        assertEquals(0, regions[0].index)
        assertEquals(1, regions[1].index)

        assertBounds(regions[0], 10f, 100f, 120f, 170f)
        assertBounds(regions[1], 12f, 300f, 130f, 370f)
    }

    @Test
    fun grouping_regionConfidence_equalsMeanLineScore() {
        val lines = arrayOf(
            line(10, 100, 100, 130, "1. First question", 0.90f),
            line(20, 140, 120, 170, "continuation one", 0.70f)
        )

        val regions = detector.detectFromLines(lines, pageHeight)

        assertEquals(1, regions.size)
        assertEquals(0.80f, regions[0].confidence, 0.0001f)
    }

    @Test
    fun grouping_outputSchemaCompatibility_forDownstreamConsumers() {
        val lines = arrayOf(
            line(10, 100, 100, 130, "1. First question", 0.90f),
            line(20, 140, 120, 170, "continuation one", 0.70f),
            line(12, 300, 110, 330, "2. Second question", 1.00f)
        )

        val regions = detector.detectFromLines(lines, pageHeight)

        assertFalse(regions.isEmpty())
        for (region in regions) {
            assertTrue(region.index >= 0)
            assertFalse(region.lineBoxes.isEmpty())
            assertTrue(region.bounds.width() > 0f)
            assertTrue(region.bounds.height() > 0f)

            for (lineBox in region.lineBoxes) {
                assertTrue(region.bounds.left <= lineBox.left)
                assertTrue(region.bounds.top <= lineBox.top)
                assertTrue(region.bounds.right >= lineBox.right)
                assertTrue(region.bounds.bottom >= lineBox.bottom)
            }
        }
    }

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

    private fun assertBounds(
        region: QuestionRegion,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        assertEquals(left, region.bounds.left, 0.01f)
        assertEquals(top, region.bounds.top, 0.01f)
        assertEquals(right, region.bounds.right, 0.01f)
        assertEquals(bottom, region.bounds.bottom, 0.01f)
    }
}
