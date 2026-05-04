package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Point
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MlKitOcrLineMapperTest {

    @Test
    fun toOcrResult_ordersCornerPointsAsTlTrBrBl() {
        val result = MlKitOcrLineMapper.toOcrResult(
            MlKitRecognizedLine(
                text = "問題1",
                cornerPoints = listOf(
                    Point(120, 20),
                    Point(12, 18),
                    Point(118, 54),
                    Point(10, 52)
                ),
                boundingBox = null,
                confidence = 0.91f
            )
        )

        assertNotNull(result)
        assertBoxEquals(requireNotNull(result).box, 12, 18, 120, 20, 118, 54, 10, 52)
        assertEquals("問題1", result.text)
        assertEquals(0.91f, result.score, 0.0001f)
    }

    @Test
    fun toOcrResult_fallsBackToBoundingBoxWhenCornerPointsMissing() {
        val result = MlKitOcrLineMapper.toOcrResult(
            MlKitRecognizedLine(
                text = "Q2",
                cornerPoints = null,
                boundingBox = Rect(5, 10, 105, 40),
                confidence = 0.8f
            )
        )

        assertNotNull(result)
        assertBoxEquals(requireNotNull(result).box, 5, 10, 105, 10, 105, 40, 5, 40)
    }

    @Test
    fun toOcrResult_clampsConfidenceToScoreRange() {
        val high = MlKitOcrLineMapper.toOcrResult(lineWithConfidence(1.4f))
        val low = MlKitOcrLineMapper.toOcrResult(lineWithConfidence(-0.2f))

        assertEquals(1.0f, requireNotNull(high).score, 0.0001f)
        assertEquals(0.0f, requireNotNull(low).score, 0.0001f)
    }

    @Test
    fun toOcrResult_usesZeroScoreWhenConfidenceUnavailable() {
        val result = MlKitOcrLineMapper.toOcrResult(lineWithConfidence(null))

        assertEquals(0.0f, requireNotNull(result).score, 0.0001f)
    }

    @Test
    fun toOcrResult_returnsNullWhenGeometryUnavailable() {
        val result = MlKitOcrLineMapper.toOcrResult(
            MlKitRecognizedLine(
                text = "問題",
                cornerPoints = null,
                boundingBox = null,
                confidence = 0.7f
            )
        )

        assertNull(result)
    }

    private fun lineWithConfidence(confidence: Float?): MlKitRecognizedLine {
        return MlKitRecognizedLine(
            text = "line",
            cornerPoints = null,
            boundingBox = Rect(0, 0, 10, 10),
            confidence = confidence
        )
    }

    private fun assertBoxEquals(
        box: Array<IntArray>,
        tlX: Int,
        tlY: Int,
        trX: Int,
        trY: Int,
        brX: Int,
        brY: Int,
        blX: Int,
        blY: Int
    ) {
        assertEquals(4, box.size)
        assertEquals(tlX, box[0][0])
        assertEquals(tlY, box[0][1])
        assertEquals(trX, box[1][0])
        assertEquals(trY, box[1][1])
        assertEquals(brX, box[2][0])
        assertEquals(brY, box[2][1])
        assertEquals(blX, box[3][0])
        assertEquals(blY, box[3][1])
    }
}
