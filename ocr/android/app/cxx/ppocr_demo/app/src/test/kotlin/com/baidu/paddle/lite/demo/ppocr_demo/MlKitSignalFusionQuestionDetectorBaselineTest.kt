package com.baidu.paddle.lite.demo.ppocr_demo

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MlKitSignalFusionQuestionDetectorBaselineTest {

    private val detector = MlKitSignalFusionQuestionDetector()
    private val jaDetector = MlKitSignalFusionQuestionDetector(language = "ja")
    private val pageHeight = 2000f

    @Test
    fun baselineFixture_numberedQuestions_splitIntoTwoRegions() {
        val lines = arrayOf(
            line(20, 100, 640, 140, "1. Find x.", 0.95f),
            line(20, 150, 700, 190, "continuation line", 0.90f),
            line(22, 320, 650, 360, "2. Find y.", 0.96f),
            line(22, 370, 690, 410, "continuation line", 0.88f)
        )

        val regions = detector.detectFromLines(lines, pageHeight)

        assertEquals(2, regions.size)
        assertBounds(regions[0], 20f, 100f, 700f, 190f)
        assertBounds(regions[1], 22f, 320f, 690f, 410f)
    }

    @Test
    fun baselineFixture_largeVerticalGap_splitsNarrativeBlocks() {
        val lines = arrayOf(
            line(30, 120, 760, 160, "Intro text.", 0.92f),
            line(30, 170, 760, 210, "More intro text.", 0.91f),
            line(32, 520, 760, 560, "Follow-up section.", 0.93f),
            line(32, 570, 760, 610, "More follow-up text.", 0.90f)
        )

        val regions = detector.detectFromLines(lines, pageHeight)

        assertEquals(2, regions.size)
        assertBounds(regions[0], 30f, 120f, 760f, 210f)
        assertBounds(regions[1], 32f, 520f, 760f, 610f)
    }

    @Test
    fun baselineFixture_japaneseImperativeInJaMode_splitsIntoTwo() {
        val lines = arrayOf(
            line(40, 200, 700, 240, "x の値を求めよ。", 0.95f),
            line(40, 248, 700, 288, "面積を計算しなさい。", 0.94f)
        )

        val regions = jaDetector.detectFromLines(lines, pageHeight)

        assertEquals(2, regions.size)
        assertBounds(regions[0], 40f, 200f, 700f, 240f)
        assertBounds(regions[1], 40f, 248f, 700f, 288f)
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
