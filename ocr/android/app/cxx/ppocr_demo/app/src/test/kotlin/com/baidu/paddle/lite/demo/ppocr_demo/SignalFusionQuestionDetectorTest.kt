package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.RectF
import com.baidu.paddle.lite.demo.ppocr_demo.SignalFusionQuestionDetector.AnchorProfile
import com.baidu.paddle.lite.demo.ppocr_demo.SignalFusionQuestionDetector.LineContext
import com.baidu.paddle.lite.demo.ppocr_demo.SignalFusionQuestionDetector.NumberedMarkerStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SignalFusionQuestionDetectorTest {

    private val detector   = SignalFusionQuestionDetector()
    private val jaDetector = SignalFusionQuestionDetector(language = "ja")
    private val enDetector = SignalFusionQuestionDetector(language = "en")

    // Image height used in most tests. Header zone: top < 100; footer zone: bottom > 1900.
    private val PAGE = 2000f

    private fun line(
        left: Int, top: Int, right: Int, bottom: Int,
        text: String,
        score: Float = 0.95f
    ) = OcrResult(
        arrayOf(intArrayOf(left, top), intArrayOf(right, top),
                intArrayOf(right, bottom), intArrayOf(left, bottom)),
        text, score
    )

    // Builds a LineContext for matchesAnchorProfile tests.
    private fun ctx(left: Float, text: String): LineContext {
        val l = left.toInt()
        val r = l + 200
        return LineContext(
            line     = OcrResult(
                arrayOf(intArrayOf(l, 100), intArrayOf(r, 100),
                        intArrayOf(r, 120), intArrayOf(l, 120)),
                text, 0.95f),
            rect     = RectF(left, 100f, left + 200f, 120f),
            index    = 0,
            gapAbove = Float.MAX_VALUE,
            prevLine = null
        )
    }

    private fun profile(
        leftMin: Float = 40f,
        leftMax: Float = 50f,
        style: NumberedMarkerStyle = NumberedMarkerStyle.NUMERIC_DOT,
        observed: List<Int> = listOf(1, 2, 3),
        confidence: Float = 1.0f
    ) = AnchorProfile(leftMin..leftMax, style, observed, confidence)

    // Runs a single-line detect and checks the region count.
    private fun assertQuestionCount(expected: Int, text: String, det: SignalFusionQuestionDetector = detector) {
        val result = det.detectFromLines(arrayOf(line(0, 0, 300, 20, text)), PAGE)
        assertEquals("Expected $expected region(s) for: \"$text\"", expected, result.size)
    }

    // ── empty / trivial ──────────────────────────────────────────────────────

    @Test fun emptyInput_returnsEmpty() {
        assertTrue(detector.detectFromLines(emptyArray(), PAGE).isEmpty())
    }

    // ── Tier 1: numbered marker styles ──────────────────────────────────────
    // numbered_marker signal score = 9 ≥ TIER1_THRESHOLD → fires unconditionally.

    @Test fun tier1_numericDot() {
        assertQuestionCount(1, "1. Find the derivative of f(x).")
    }

    @Test fun tier1_numericParen() {
        assertQuestionCount(1, "2) Calculate the area.")
    }

    @Test fun tier1_enclosedParen() {
        assertQuestionCount(1, "(3) Explain the process.")
    }

    @Test fun tier1_fullwidthParenTrailing() {
        assertQuestionCount(1, "4） Solve for y.")
    }

    @Test fun tier1_fullwidthParenLeading() {
        assertQuestionCount(1, "（5） Determine the roots.")
    }

    @Test fun tier1_circledNumber() {
        assertQuestionCount(1, "③ Find the area of the circle.")
    }

    @Test fun tier1_qPrefix() {
        assertQuestionCount(1, "Q3 Sketch the graph.")
    }

    @Test fun tier1_qPrefixDot() {
        assertQuestionCount(1, "Q.4 State the theorem.")
    }

    @Test fun tier1_jpMondai() {
        assertQuestionCount(1, "問題2 x の値を求めよ。")
    }

    @Test fun tier1_jpSetsumon() {
        assertQuestionCount(1, "設問1 次の式を計算しなさい。")
    }

    @Test fun tier1_jpMon() {
        assertQuestionCount(1, "問3 面積を求めよ。")
    }

    @Test fun tier1_daiMon() {
        assertQuestionCount(1, "第2問 次の問いに答えよ。")
    }

    // ── Tier 1: horizontal rule ──────────────────────────────────────────────
    // horizontal_rule score = 10 ≥ TIER1_THRESHOLD; fires on wide (>40% page) + thin (<5px) boxes.

    @Test fun tier1_horizontalRule() {
        // width=850 > PAGE*0.4=800; height=2 < 5 → horizontal_rule fires
        val result = detector.detectFromLines(arrayOf(line(0, 200, 850, 202, "———")), PAGE)
        assertEquals(1, result.size)
    }

    @Test fun thinButNarrowBox_doesNotFireHorizontalRule() {
        // width=200 < PAGE*0.4=800 → not a horizontal rule; relies on gap + semantics
        // Single plain-text line with no semantic markers → gap_very_large fires, starts 1 region
        // (this test just ensures the narrow box isn't mis-classified as a rule)
        val result = detector.detectFromLines(arrayOf(line(0, 0, 200, 2, "—")), PAGE)
        assertEquals(1, result.size) // gap_very_large still fires on first line
    }

    // ── Sub-question veto ────────────────────────────────────────────────────
    // a.  b)  (i)  (ii) etc. must NOT split a question — they receive a hard veto at Tier 2/3.
    // Contrast with GeometricQuestionDetector where LETTERED was a positive signal.

    @Test fun subQuestion_letter_doesNotSplitFromParent() {
        val lines = arrayOf(
            line(0,  0, 200, 20, "1. Find the value of x."),
            line(0, 25, 200, 45, "a. Show all working steps."),
            line(0, 50, 200, 70, "b. Verify your answer.")
        )
        val result = detector.detectFromLines(lines, PAGE)
        assertEquals(1, result.size)
        assertEquals(3, result[0].lineBoxes.size)
    }

    @Test fun subQuestion_romanNumeral_doesNotSplitFromParent() {
        val lines = arrayOf(
            line(0,  0, 200, 20, "2. Describe the process."),
            line(0, 25, 200, 45, "(i) First stage."),
            line(0, 50, 200, 70, "(ii) Second stage.")
        )
        val result = detector.detectFromLines(lines, PAGE)
        assertEquals(1, result.size)
        assertEquals(3, result[0].lineBoxes.size)
    }

    @Test fun numberedMarker_tier1_notAffectedBySubQuestionLogic() {
        // numbered_marker fires at Tier 1 (score=9), which is evaluated BEFORE the sub-question
        // veto — so two consecutive numbered questions each start their own region.
        val lines = arrayOf(
            line(0,  0, 200, 20, "1. First question."),
            line(0, 25, 200, 45, "2. Second question.")
        )
        val result = detector.detectFromLines(lines, PAGE)
        assertEquals(2, result.size)
    }

    // ── Grouping ─────────────────────────────────────────────────────────────

    @Test fun continuationLines_attachToPrecedingQuestion() {
        val lines = arrayOf(
            line(0,  0, 200, 20, "1. Question one — a long question that"),
            line(0, 25, 200, 45, "   continues on this second line.")
        )
        val result = detector.detectFromLines(lines, PAGE)
        assertEquals(1, result.size)
        assertEquals(2, result[0].lineBoxes.size)
    }

    @Test fun multipleQuestions_allGroupedCorrectly() {
        val lines = arrayOf(
            line(0,  0, 200,  20, "1. Question one"),
            line(0, 25, 200,  45, "   continuation of Q1"),
            line(0, 50, 200,  70, "2. Question two"),
            line(0, 75, 200,  95, "3. Question three")
        )
        val result = detector.detectFromLines(lines, PAGE)
        assertEquals(3, result.size)
        assertEquals(2, result[0].lineBoxes.size)
        assertEquals(1, result[1].lineBoxes.size)
        assertEquals(1, result[2].lineBoxes.size)
    }

    @Test fun indices_areZeroBasedAndSequential() {
        val lines = arrayOf(
            line(0,  0, 200, 20, "1. First"),
            line(0, 25, 200, 45, "2. Second"),
            line(0, 50, 200, 70, "3. Third")
        )
        val result = detector.detectFromLines(lines, PAGE)
        assertEquals(3, result.size)
        assertEquals(0, result[0].index)
        assertEquals(1, result[1].index)
        assertEquals(2, result[2].index)
    }

    @Test fun bounds_isUnionOfAllLineBoxes() {
        val lines = arrayOf(
            line(10,  0, 100, 20, "1. Question one"),
            line(15, 25,  90, 45, "   continuation")
        )
        val bounds = detector.detectFromLines(lines, PAGE)[0].bounds
        assertEquals(10f,  bounds.left,   0.01f)
        assertEquals( 0f,  bounds.top,    0.01f)
        assertEquals(100f, bounds.right,  0.01f)
        assertEquals(45f,  bounds.bottom, 0.01f)
    }

    @Test fun confidence_isAverageOfLineScores() {
        val lines = arrayOf(
            line(0,  0, 200, 20, "1. Question", score = 0.9f),
            line(0, 25, 200, 45, "continuation", score = 0.7f)
        )
        val confidence = detector.detectFromLines(lines, PAGE)[0].confidence
        assertEquals(0.8f, confidence, 0.001f)
    }

    // ── Geometric gap signal ─────────────────────────────────────────────────

    @Test fun largeGap_separatesIntoDistinctRegions() {
        // medianH=20, gapVeryLarge=60; gap between groups = 255 >> 60 → gap_very_large fires
        val lines = arrayOf(
            line(0,   0, 200,  20, "First block, line one."),
            line(0,  25, 200,  45, "First block, line two."),
            line(0, 300, 200, 320, "Second block — large gap above."),
            line(0, 325, 200, 345, "Second block, line two.")
        )
        val result = detector.detectFromLines(lines, PAGE)
        assertEquals(2, result.size)
        assertEquals(2, result[0].lineBoxes.size)
        assertEquals(2, result[1].lineBoxes.size)
    }

    // ── Semantic signals ─────────────────────────────────────────────────────

    @Test fun semantic_questionKeyword_detected() {
        // question_keyword score=8; combined with gap_very_large(+8)/gap_small(-3) → Tier 2 fires
        val lines = arrayOf(
            line(0,  0, 200, 20, "Question 1: Find the value."),
            line(0, 25, 200, 45, "Question 2: Solve for x.")
        )
        val result = detector.detectFromLines(lines, PAGE)
        assertEquals(2, result.size)
    }

    @Test fun semantic_imperativeVerb_startsQuestion() {
        val lines = arrayOf(
            line(0,  0, 200, 20, "Find the derivative of f(x)."),
            line(0, 25, 200, 45, "Calculate the area of the shape.")
        )
        val result = detector.detectFromLines(lines, PAGE)
        assertEquals(2, result.size)
    }

    @Test fun semantic_trailingQuestionMark_startsQuestion() {
        val lines = arrayOf(
            line(0,  0, 200, 20, "What is the value of x?"),
            line(0, 25, 200, 45, "Why does the equation have two roots?")
        )
        val result = detector.detectFromLines(lines, PAGE)
        assertEquals(2, result.size)
    }

    // ── Japanese semantic ────────────────────────────────────────────────────

    @Test fun ja_imperativeAtLineEnd_detected() {
        val lines = arrayOf(
            line(0,  0, 200, 20, "x の値を求めよ。"),
            line(0, 25, 200, 45, "面積を計算しなさい。")
        )
        assertEquals(2, jaDetector.detectFromLines(lines, PAGE).size)
    }

    @Test fun ja_questionEnding_detectedWithLangJa() {
        // gap_small(-3) + margin_reset(+4) + JA_QUESTION_ENDING via trailing_q(+3) = net 4
        // Tier 3: two positives (margin_reset≥2, trailing_q≥2) → fires
        val lines = arrayOf(
            line(0,  0, 200, 20, "答えはいくつですか。"),
            line(0, 22, 200, 42, "x はどこに存在しますか。")
        )
        assertEquals(2, jaDetector.detectFromLines(lines, PAGE).size)
    }

    @Test fun ja_questionEnding_notDetectedWithLangEn() {
        // lang="en" excludes JA_QUESTION_ENDING from trailing_q signal
        // second line: gap_small(-3) + margin_reset(+4) = net 1 → no tier fires → no split
        val lines = arrayOf(
            line(0,  0, 200, 20, "答えはいくつですか。"),
            line(0, 22, 200, 42, "x はどこに存在しますか。")
        )
        val result = enDetector.detectFromLines(lines, PAGE)
        assertEquals(1, result.size)    // first line starts a region; second continues it
        assertEquals(2, result[0].lineBoxes.size)
    }

    // ── Language dispatch ────────────────────────────────────────────────────

    @Test fun langEn_detectsEnglishImperative() {
        assertQuestionCount(1, "Solve the equation x² + 1 = 0.", enDetector)
    }

    @Test fun langEn_doesNotSplitOnJapaneseImperativeAlone() {
        // Second line: lang="en" → JA_IMPERATIVE not evaluated; gap_small(-3) + margin_reset(+4)
        // = net 1 → no split; both lines stay in one region.
        val lines = arrayOf(
            line(0,  0, 200, 20, "Some introductory text."),
            line(0, 22, 200, 42, "面積を計算しなさい。")
        )
        val result = enDetector.detectFromLines(lines, PAGE)
        assertEquals(1, result.size)
        assertEquals(2, result[0].lineBoxes.size)
    }

    @Test fun langJa_detectsJapaneseImperative() {
        assertQuestionCount(1, "x の値を求めよ。", jaDetector)
    }

    @Test fun langJa_doesNotSplitOnEnglishImperativeAlone() {
        // lang="ja" → EN_IMPERATIVE not evaluated; gap_small(-3) + margin_reset(+4) = net 1 → no split
        val lines = arrayOf(
            line(0,  0, 200, 20, "Some introductory text."),
            line(0, 22, 200, 42, "Solve the equation.")
        )
        val result = jaDetector.detectFromLines(lines, PAGE)
        assertEquals(1, result.size)
        assertEquals(2, result[0].lineBoxes.size)
    }

    @Test fun langEmpty_detectsBothEnglishAndJapanese() {
        val lines = arrayOf(
            line(0,  0, 200, 20, "Solve the equation."),     // EN imperative
            line(0, 25, 200, 45, "x の値を求めよ。")          // JA imperative
        )
        assertEquals(2, detector.detectFromLines(lines, PAGE).size)
    }

    // ── extractInteger ───────────────────────────────────────────────────────

    @Test fun extractInteger_numericDot() {
        assertEquals(3, detector.extractInteger("3. Find the roots.", NumberedMarkerStyle.NUMERIC_DOT))
    }

    @Test fun extractInteger_numericParen() {
        assertEquals(7, detector.extractInteger("7) Explain.", NumberedMarkerStyle.NUMERIC_PAREN))
    }

    @Test fun extractInteger_enclosedParen() {
        assertEquals(2, detector.extractInteger("(2) Calculate.", NumberedMarkerStyle.NUMERIC_ENCLOSED_PAREN))
    }

    @Test fun extractInteger_fullwidthParen_leadingBracket() {
        assertEquals(4, detector.extractInteger("（4） Determine.", NumberedMarkerStyle.NUMERIC_FULLWIDTH_PAREN))
    }

    @Test fun extractInteger_fullwidthParen_trailingBracket() {
        assertEquals(5, detector.extractInteger("5） Evaluate.", NumberedMarkerStyle.NUMERIC_FULLWIDTH_PAREN))
    }

    @Test fun extractInteger_circledNumber_first() {
        assertEquals(1, detector.extractInteger("①問題", NumberedMarkerStyle.CIRCLED_NUMBER))
    }

    @Test fun extractInteger_circledNumber_last() {
        assertEquals(20, detector.extractInteger("⑳問題", NumberedMarkerStyle.CIRCLED_NUMBER))
    }

    @Test fun extractInteger_qPrefix_noSeparator() {
        assertEquals(8, detector.extractInteger("Q8 Prove that.", NumberedMarkerStyle.Q_PREFIX))
    }

    @Test fun extractInteger_qPrefix_dotSeparator() {
        assertEquals(12, detector.extractInteger("Q.12 Sketch.", NumberedMarkerStyle.Q_PREFIX))
    }

    @Test fun extractInteger_jpMondai() {
        assertEquals(3, detector.extractInteger("問題3 求めよ。", NumberedMarkerStyle.JP_MONDAI))
    }

    @Test fun extractInteger_jpSetsumon() {
        assertEquals(2, detector.extractInteger("設問2 計算しなさい。", NumberedMarkerStyle.JP_SETSUMON))
    }

    @Test fun extractInteger_jpMon() {
        assertEquals(5, detector.extractInteger("問5 証明せよ。", NumberedMarkerStyle.JP_MON))
    }

    @Test fun extractInteger_daiMon() {
        assertEquals(3, detector.extractInteger("第3問 次の問いに答えよ。", NumberedMarkerStyle.DAI_MON))
    }

    @Test fun extractInteger_rejectsZero() {
        assertNull(detector.extractInteger("0. Some text.", NumberedMarkerStyle.NUMERIC_DOT))
    }

    @Test fun extractInteger_rejectsAbove200() {
        assertNull(detector.extractInteger("201. Some text.", NumberedMarkerStyle.NUMERIC_DOT))
    }

    // ── matchesAnchorProfile ─────────────────────────────────────────────────

    @Test fun matchesAnchorProfile_passesAllThreeCriteria() {
        // style matches, x in range, integer extends sequence 1→2→3→4
        val p = profile(leftMin = 40f, leftMax = 50f,
                        style = NumberedMarkerStyle.NUMERIC_DOT, observed = listOf(1, 2, 3))
        assertTrue(detector.matchesAnchorProfile(ctx(45f, "4. Solve for x."), p))
    }

    @Test fun matchesAnchorProfile_failsOnStyleMismatch() {
        // profile expects NUMERIC_DOT, candidate has NUMERIC_PAREN
        val p = profile(style = NumberedMarkerStyle.NUMERIC_DOT)
        assertFalse(detector.matchesAnchorProfile(ctx(45f, "4) Solve for x."), p))
    }

    @Test fun matchesAnchorProfile_failsOnXPositionMismatch() {
        // profile margin range 40–50; candidate at x=200 (beyond 50 + MARGIN_TOLERANCE=10)
        val p = profile(leftMin = 40f, leftMax = 50f)
        assertFalse(detector.matchesAnchorProfile(ctx(200f, "4. Solve for x."), p))
    }

    @Test fun matchesAnchorProfile_failsOnDuplicateNumber() {
        // 3 already in observedNumbers → duplicate → sequence criterion fails
        val p = profile(observed = listOf(1, 2, 3))
        assertFalse(detector.matchesAnchorProfile(ctx(45f, "3. Solve for x."), p))
    }

    @Test fun matchesAnchorProfile_failsOnBackwardJump() {
        // max(observed)=3; candidate n=2 → not > max → sequence criterion fails
        val p = profile(observed = listOf(1, 2, 3))
        assertFalse(detector.matchesAnchorProfile(ctx(45f, "2. Solve for x."), p))
    }

    @Test fun matchesAnchorProfile_failsOnForwardJumpTooLarge() {
        // max=3; candidate n=6 → 6 > 3+2=5 → sequence criterion fails
        val p = profile(observed = listOf(1, 2, 3))
        assertFalse(detector.matchesAnchorProfile(ctx(45f, "6. Solve for x."), p))
    }

    @Test fun matchesAnchorProfile_passesWithOneMissedQuestionTolerance() {
        // max=3; candidate n=5 → 5 ≤ 3+2=5 → within the one-missed-question allowance
        val p = profile(observed = listOf(1, 2, 3))
        assertTrue(detector.matchesAnchorProfile(ctx(45f, "5. Solve for x."), p))
    }
}
