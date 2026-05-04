package com.baidu.paddle.lite.demo.ppocr_demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MlKitLineGeometryNormalizerTest {

    @Test
    fun orderTlTrBrBl_isStableWithShuffledCorners() {
        val shuffled = arrayOf(
            intArrayOf(118, 54), // BR
            intArrayOf(10, 52),  // BL
            intArrayOf(120, 20), // TR
            intArrayOf(12, 18)   // TL
        )

        val ordered = MlKitLineGeometryNormalizer.orderTlTrBrBl(shuffled)

        assertPoint(ordered[0], 12, 18)   // TL
        assertPoint(ordered[1], 120, 20)  // TR
        assertPoint(ordered[2], 118, 54)  // BR
        assertPoint(ordered[3], 10, 52)   // BL
    }

    @Test
    fun normalizeLine_clampsAllCoordinatesWithinImageBounds() {
        val line = line(
            -20, -5,
            130, -5,
            140, 90,
            -10, 85
        )

        val normalized = MlKitLineGeometryNormalizer.normalizeLine(line, 100, 80)
        assertNotNull(normalized)
        val box = requireNotNull(normalized).normalized.box

        for (pt in box) {
            assertTrue(pt[0] in 0..99)
            assertTrue(pt[1] in 0..79)
        }
    }

    @Test
    fun normalizeLine_degenerateBoxesAreRejectedOrDowngradedConsistently() {
        val fullyDegenerate = line(
            10, 10,
            10, 10,
            10, 10,
            10, 10
        )
        assertNull(MlKitLineGeometryNormalizer.normalizeLine(fullyDegenerate, 200, 200))

        val duplicateCornerButRecoverable = line(
            0, 0,
            60, 0,
            60, 30,
            60, 30
        )
        val downgraded = MlKitLineGeometryNormalizer.normalizeLine(duplicateCornerButRecoverable, 200, 200)
        assertNotNull(downgraded)
        assertEquals(GeometryQuality.RECT_FALLBACK, requireNotNull(downgraded).quality)
    }

    private fun line(
        x0: Int, y0: Int,
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        x3: Int, y3: Int
    ): OcrResult {
        return OcrResult(
            arrayOf(
                intArrayOf(x0, y0),
                intArrayOf(x1, y1),
                intArrayOf(x2, y2),
                intArrayOf(x3, y3)
            ),
            "text",
            0.9f
        )
    }

    private fun assertPoint(actual: IntArray, x: Int, y: Int) {
        assertEquals(x, actual[0])
        assertEquals(y, actual[1])
    }
}
