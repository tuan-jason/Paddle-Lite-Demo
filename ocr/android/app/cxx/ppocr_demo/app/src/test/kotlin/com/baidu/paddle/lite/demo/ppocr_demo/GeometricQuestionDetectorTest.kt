package com.baidu.paddle.lite.demo.ppocr_demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeometricQuestionDetectorTest {

    private val detector   = GeometricQuestionDetector()
    private val jaDetector = GeometricQuestionDetector(language = "ja")
    private val enDetector = GeometricQuestionDetector(language = "en")

    // Creates an OcrResult with an axis-aligned box and the given text/score.
    private fun line(
        left: Int, top: Int, right: Int, bottom: Int,
        text: String,
        score: Float = 0.95f
    ) = OcrResult(
        arrayOf(intArrayOf(left, top), intArrayOf(right, top),
                intArrayOf(right, bottom), intArrayOf(left, bottom)),
        text, score
    )

    // ── empty / trivial ──────────────────────────────────────────────────────

    @Test fun emptyInput_returnsEmpty() {
        assertTrue(detector.detectFromLines(emptyArray()).isEmpty())
    }

    @Test fun singleLineWithoutMarker_returnsEmpty() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "No marker here")))
        assertTrue(result.isEmpty())
    }

    // ── printed path: text-marker detection ─────────────────────────────────

    @Test fun numberedDot_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "1. What is this?")))
        assertEquals(1, result.size)
    }

    @Test fun numberedParen_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "2) Explain the process.")))
        assertEquals(1, result.size)
    }

    @Test fun letteredMarker_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "a. Define the term.")))
        assertEquals(1, result.size)
    }

    @Test fun trailingQuestionMark_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Why does this happen?")))
        assertEquals(1, result.size)
    }

    @Test fun keywordQuestion_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Question 3: Describe the stages.")))
        assertEquals(1, result.size)
    }

    @Test fun keywordQuestion_caseInsensitive() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "question 1: What is X?")))
        assertEquals(1, result.size)
    }

    // ── grouping: continuation & pre-question lines ──────────────────────────

    @Test fun continuationLines_attachedToPrecedingQuestion() {
        val lines = arrayOf(
            line(0, 0,  100, 20, "1. First question"),
            line(0, 25, 100, 45, "   continued on this line")
        )
        val result = detector.detectFromLines(lines)
        assertEquals(1, result.size)
        assertEquals(2, result[0].lineBoxes.size)
    }

    @Test fun linesBeforeFirstMarker_discarded() {
        val lines = arrayOf(
            line(0, 0,  100, 20, "Instructions: answer all questions"),
            line(0, 25, 100, 45, "1. First question")
        )
        val result = detector.detectFromLines(lines)
        assertEquals(1, result.size)
        // only the marked line is in the region
        assertEquals(1, result[0].lineBoxes.size)
    }

    @Test fun multipleQuestions_allGroupedCorrectly() {
        val lines = arrayOf(
            line(0, 0,   100, 20, "Preamble text"),          // discarded
            line(0, 25,  100, 45, "1. Question one"),         // Q0 start
            line(0, 50,  100, 70, "   continuation of Q1"),   // Q0 continuation
            line(0, 75,  100, 95, "2. Question two"),         // Q1 start
            line(0, 100, 100, 120, "3. Question three")       // Q2 start
        )
        val result = detector.detectFromLines(lines)
        assertEquals(3, result.size)
        assertEquals(2, result[0].lineBoxes.size) // Q0 has 2 lines
        assertEquals(1, result[1].lineBoxes.size)
        assertEquals(1, result[2].lineBoxes.size)
    }

    @Test fun indices_areZeroBasedAndSequential() {
        val lines = arrayOf(
            line(0, 0,   100, 20, "1. First"),
            line(0, 25,  100, 45, "2. Second"),
            line(0, 50,  100, 70, "3. Third")
        )
        val result = detector.detectFromLines(lines)
        assertEquals(3, result.size)
        assertEquals(0, result[0].index)
        assertEquals(1, result[1].index)
        assertEquals(2, result[2].index)
    }

    // ── bounds & confidence ──────────────────────────────────────────────────

    @Test fun bounds_isUnionOfAllLineBoxes() {
        val lines = arrayOf(
            line(10,  0,  100, 20, "1. Question one"),
            line(15, 25,   90, 45, "   continuation")
        )
        val bounds = detector.detectFromLines(lines)[0].bounds
        assertEquals(10f,  bounds.left,   0.01f)
        assertEquals(0f,   bounds.top,    0.01f)
        assertEquals(100f, bounds.right,  0.01f)
        assertEquals(45f,  bounds.bottom, 0.01f)
    }

    @Test fun confidence_isAverageOfLineScores() {
        val lines = arrayOf(
            line(0, 0,  100, 20, "1. Question", score = 0.9f),
            line(0, 25, 100, 45, "continuation", score = 0.7f)
        )
        val confidence = detector.detectFromLines(lines)[0].confidence
        assertEquals(0.8f, confidence, 0.001f)
    }

    // ── handwriting path ─────────────────────────────────────────────────────

    @Test fun handwriting_firstLine_alwaysStartsQuestion() {
        // score below threshold → geometric path; first line gap = MAX_VALUE → start
        val result = detector.detectFromLines(arrayOf(
            line(0, 0, 100, 20, "illegible text", score = 0.5f)
        ))
        assertEquals(1, result.size)
    }

    @Test fun handwriting_largeGap_startsNewQuestion() {
        // avgLineH = 20, gapThreshold = 1.5 * 20 = 30
        // gap between line2.bottom(45) and line3.top(200) = 155 > 30 → new question
        val lines = arrayOf(
            line(0,   0, 100,  20, "handwritten q1 line 1", score = 0.5f),
            line(0,  25, 100,  45, "handwritten q1 line 2", score = 0.5f),
            line(0, 200, 100, 220, "handwritten q2 line 1", score = 0.5f)
        )
        val result = detector.detectFromLines(lines)
        assertEquals(2, result.size)
        assertEquals(2, result[0].lineBoxes.size)
        assertEquals(1, result[1].lineBoxes.size)
    }

    @Test fun handwriting_smallGap_continuationNotNewQuestion() {
        // gap = 5 < gapThreshold (30) → not a new question start
        val lines = arrayOf(
            line(0,  0, 100, 20, "handwritten line 1", score = 0.5f),
            line(0, 25, 100, 45, "handwritten line 2", score = 0.5f)
        )
        val result = detector.detectFromLines(lines)
        assertEquals(1, result.size)
        assertEquals(2, result[0].lineBoxes.size)
    }

    @Test fun handwriting_highConfidenceLinesUsePrintedPath() {
        // score > threshold → text markers required; no marker → not a question start
        val lines = arrayOf(
            line(0,   0, 100,  20, "no marker high conf", score = 0.95f),
            line(0, 200, 100, 220, "also no marker",      score = 0.95f)
        )
        // despite large gap, high-confidence lines without markers are not detected
        assertTrue(detector.detectFromLines(lines).isEmpty())
    }

    // ── Pattern A: imperative verb at line start ─────────────────────────────

    @Test fun patternA_solveAtLineStart_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Solve the equation x² + 3x + 2 = 0")))
        assertEquals(1, result.size)
    }

    @Test fun patternA_calculateAtLineStart_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Calculate the area of the circle.")))
        assertEquals(1, result.size)
    }

    @Test fun patternA_proveAtLineStart_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Prove that the sum of angles in a triangle is 180°.")))
        assertEquals(1, result.size)
    }

    @Test fun patternA_sketchAtLineStart_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Sketch the curve y = sin(x) for x ∈ [0, 2π].")))
        assertEquals(1, result.size)
    }

    @Test fun patternA_caseInsensitive() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "find the derivative of f(x) = x³.")))
        assertEquals(1, result.size)
    }

    @Test fun patternA_verbMidLine_notDetected() {
        // "find" is mid-sentence, not at line start → must not fire
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "We need to find the value of x.")))
        assertTrue(result.isEmpty())
    }

    @Test fun patternA_partialWordNotMatched() {
        // "Findings" starts with "Find" but the \b boundary must prevent a match
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Findings suggest that x > 0.")))
        assertTrue(result.isEmpty())
    }

    @Test fun patternA_multipleVerbs_groupedCorrectly() {
        val lines = arrayOf(
            line(0,  0, 100, 20, "Solve the quadratic equation."),
            line(0, 25, 100, 45, "Show all working steps."),
            line(0, 50, 100, 70, "Verify your answer by substitution.")
        )
        val result = detector.detectFromLines(lines)
        assertEquals(3, result.size)
    }

    // ── Pattern B: conditional prefix + mid-line imperative verb ─────────────

    @Test fun patternB_givenThat_find_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Given that x = 5, find the value of y.")))
        assertEquals(1, result.size)
    }

    @Test fun patternB_if_calculate_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "If the radius is 7 cm, calculate the area.")))
        assertEquals(1, result.size)
    }

    @Test fun patternB_for_determine_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "For x > 0, determine the sign of f′(x).")))
        assertEquals(1, result.size)
    }

    @Test fun patternB_let_find_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Let A be a 3×3 matrix. Find its determinant.")))
        assertEquals(1, result.size)
    }

    @Test fun patternB_suppose_prove_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Suppose n is a positive integer. Prove that n² + n is even.")))
        assertEquals(1, result.size)
    }

    @Test fun patternB_caseInsensitive() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "given a circle of radius r, calculate the circumference.")))
        assertEquals(1, result.size)
    }

    @Test fun patternB_conditionalPrefixWithoutVerb_notDetected() {
        // "Given" prefix present but no imperative verb follows → no match
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Given the above information, it is clear that x = 5.")))
        assertTrue(result.isEmpty())
    }

    @Test fun patternB_verbWithoutConditionalPrefix_handledByPatternA() {
        // "Find" alone at start is caught by Pattern A, not Pattern B
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Find the value of x.")))
        assertEquals(1, result.size)
    }

    // ── Interrogative pronoun at line start ──────────────────────────────────

    @Test fun interrogative_what_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "What is the value of x?")))
        assertEquals(1, result.size)
    }

    @Test fun interrogative_which_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Which of the following satisfies f(x) = 0?")))
        assertEquals(1, result.size)
    }

    @Test fun interrogative_caseInsensitive() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "what are the roots of the equation?")))
        assertEquals(1, result.size)
    }

    @Test fun interrogative_midLine_notDetected() {
        // "what" mid-sentence must not fire
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Explain what the derivative represents.")))
        assertTrue(result.isEmpty())
    }

    @Test fun interrogative_where_notDetected() {
        // "Where" is intentionally excluded — definition-clause false-positive risk
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Where x ∈ ℝ, f(x) = x².")))
        assertTrue(result.isEmpty())
    }

    // ── Interrogative quantifier phrase at line start ────────────────────────

    @Test fun interrogativePhrase_howMany_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "How many solutions does the equation have?")))
        assertEquals(1, result.size)
    }

    @Test fun interrogativePhrase_howMuch_detected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "How much work is done by the force over 5 m?")))
        assertEquals(1, result.size)
    }

    @Test fun interrogativePhrase_caseInsensitive() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "how many distinct roots does f(x) possess?")))
        assertEquals(1, result.size)
    }

    @Test fun interrogativePhrase_howTo_notDetected() {
        // "How to" is an instruction header, not a question
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "How to calculate the derivative.")))
        assertTrue(result.isEmpty())
    }

    @Test fun interrogativePhrase_howDoes_notDetected() {
        // "How does" is excluded — deferred until exam data confirms need
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "How does the process work?")))
        assertTrue(result.isEmpty())
    }

    @Test fun interrogativePhrase_midLine_notDetected() {
        val result = detector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Explain how many roots the equation has.")))
        assertTrue(result.isEmpty())
    }

    // ── Pattern C: cross-line conditional prefix → imperative verb ───────────

    @Test fun patternC_conditionalPreamble_groupedWithImperative() {
        // "Given that..." (no verb) + "find..." → 1 question, 2 line boxes
        val lines = arrayOf(
            line(0,  0, 100, 20, "Given that x = 5,"),
            line(0, 25, 100, 45, "find the value of y.")
        )
        val result = detector.detectFromLines(lines)
        assertEquals(1, result.size)
        assertEquals(2, result[0].lineBoxes.size)
    }

    @Test fun patternC_bounds_startAtConditionalLine() {
        // bounds.top must be the preamble line's top, not the imperative line's top
        val lines = arrayOf(
            line(0,  0, 100, 20, "Given that x = 5,"),
            line(0, 25, 100, 45, "find the value of y.")
        )
        val bounds = detector.detectFromLines(lines)[0].bounds
        assertEquals(0f,  bounds.top,    0.01f)
        assertEquals(45f, bounds.bottom, 0.01f)
    }

    @Test fun patternC_conditionalWithVerbOnSameLine_usesPatternBNotC() {
        // Single-line "Given..., find..." is Pattern B → 1 question, 1 line box
        val result = detector.detectFromLines(arrayOf(
            line(0, 0, 100, 20, "Given that x = 5, find the value of y.")
        ))
        assertEquals(1, result.size)
        assertEquals(1, result[0].lineBoxes.size)
    }

    @Test fun patternC_conditionalNotImmediatelyBeforeImperative_notGrouped() {
        // "Given..." + unrelated line + "find..." → look-ahead only covers 1 line;
        // "Given..." is discarded as preamble; "find..." starts its own question.
        val lines = arrayOf(
            line(0,  0, 100, 20, "Given that x = 5,"),
            line(0, 25, 100, 45, "where y is unknown,"),
            line(0, 50, 100, 70, "find the value of y.")
        )
        val result = detector.detectFromLines(lines)
        assertEquals(1, result.size)
        assertEquals(1, result[0].lineBoxes.size)
    }

    @Test fun patternC_multiplePatternC_eachGroupedCorrectly() {
        val lines = arrayOf(
            line(0,  0,  100, 20, "Given that x = 5,"),
            line(0, 25,  100, 45, "find the value of y."),
            line(0, 50,  100, 70, "If the radius is 7 cm,"),
            line(0, 75,  100, 95, "calculate the area.")
        )
        val result = detector.detectFromLines(lines)
        assertEquals(2, result.size)
        assertEquals(2, result[0].lineBoxes.size)
        assertEquals(2, result[1].lineBoxes.size)
    }

    // ── Japanese: numbered markers ───────────────────────────────────────────

    @Test fun jaNumbered_circledNumber_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "①面積を求めよ。")))
        assertEquals(1, result.size)
    }

    @Test fun jaNumbered_fullWidthParen_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "（1）x の値を求めよ。")))
        assertEquals(1, result.size)
    }

    @Test fun jaNumbered_halfWidthWithFullWidthParen_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "1）次の式を計算しなさい。")))
        assertEquals(1, result.size)
    }

    // ── Japanese: keyword marker ─────────────────────────────────────────────

    @Test fun jaKeyword_mondai_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "問題1 x を求めよ。")))
        assertEquals(1, result.size)
    }

    @Test fun jaKeyword_setsumon_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "設問 y の値を求めよ。")))
        assertEquals(1, result.size)
    }

    // ── Japanese: interrogative pronoun ─────────────────────────────────────

    @Test fun jaInterrogative_nani_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "何が正しいですか。")))
        assertEquals(1, result.size)
    }

    @Test fun jaInterrogative_naze_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "なぜ x = 0 なのですか。")))
        assertEquals(1, result.size)
    }

    @Test fun jaInterrogative_midLine_notDetected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "次に何を求めるかを考えよ。")))
        // 何 is mid-sentence; no other marker fires
        assertTrue(result.isEmpty())
    }

    // ── Japanese: question-ending particle ──────────────────────────────────

    @Test fun jaQuestionEnding_desuKa_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "答えはいくつですか。")))
        assertEquals(1, result.size)
    }

    @Test fun jaQuestionEnding_masuKa_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "x はどこに存在しますか？")))
        assertEquals(1, result.size)
    }

    @Test fun jaQuestionEnding_fullWidthQuestionMark_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "これは正しいか？")))
        assertEquals(1, result.size)
    }

    // ── Japanese Pattern A: imperative at line end ───────────────────────────

    @Test fun jaPatternA_motomeyo_ichidan_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "x の値を求めよ。")))
        assertEquals(1, result.size)
    }

    @Test fun jaPatternA_keisanShinasai_suru_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "面積を計算しなさい。")))
        assertEquals(1, result.size)
    }

    @Test fun jaPatternA_shoumeiSeyo_suru_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "△ABCの面積を証明せよ。")))
        assertEquals(1, result.size)
    }

    @Test fun jaPatternA_tokinasai_godan_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "この方程式を解きなさい。")))
        assertEquals(1, result.size)
    }

    @Test fun jaPatternA_shimeseGodan_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "y が正であることを示せ。")))
        assertEquals(1, result.size)
    }

    @Test fun jaPatternA_noPeriod_detected() {
        // period is optional
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "x の値を求めよ")))
        assertEquals(1, result.size)
    }

    @Test fun jaPatternA_verbMidLine_notDetected() {
        // imperative form not at end of line
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "求めよという課題がある")))
        assertTrue(result.isEmpty())
    }

    // ── Japanese Pattern B: conditional + imperative at end ──────────────────

    @Test fun jaPatternB_noKitoni_motomeyo_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "x = 5のとき、yを求めよ。")))
        assertEquals(1, result.size)
    }

    @Test fun jaPatternB_naraba_keisanShinasai_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "x > 0 ならば、面積を計算しなさい。")))
        assertEquals(1, result.size)
    }

    @Test fun jaPatternB_niOite_motomenasai_detected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "x が正の数において最大値を求めなさい。")))
        assertEquals(1, result.size)
    }

    @Test fun jaPatternB_conditionalWithoutImperative_notDetected() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "x = 5のとき、y は正の数である。")))
        assertTrue(result.isEmpty())
    }

    // ── Language tag filtering ────────────────────────────────────────────────

    @Test fun languageTag_en_detectsEnglish() {
        val result = enDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Solve the equation.")))
        assertEquals(1, result.size)
    }

    @Test fun languageTag_en_doesNotDetectJapaneseOnly() {
        // Pure Japanese imperative; no English rule fires
        val result = enDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "面積を計算しなさい。")))
        assertTrue(result.isEmpty())
    }

    @Test fun languageTag_ja_detectsJapanese() {
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "x の値を求めよ。")))
        assertEquals(1, result.size)
    }

    @Test fun languageTag_ja_doesNotDetectEnglishOnly() {
        // English imperative verb; no Japanese rule fires
        val result = jaDetector.detectFromLines(arrayOf(line(0, 0, 100, 20, "Solve the equation.")))
        assertTrue(result.isEmpty())
    }

    @Test fun languageTag_empty_combinesRuleSets() {
        val enLine = line(0,  0, 100, 20, "Solve the equation.")
        val jaLine = line(0, 25, 100, 45, "x の値を求めよ。")
        val result = detector.detectFromLines(arrayOf(enLine, jaLine))
        assertEquals(2, result.size)
    }
}
