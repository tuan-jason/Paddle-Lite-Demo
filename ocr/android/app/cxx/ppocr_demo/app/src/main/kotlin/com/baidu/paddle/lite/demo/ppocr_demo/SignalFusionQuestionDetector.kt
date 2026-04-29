package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

class SignalFusionQuestionDetector @JvmOverloads constructor(
    private val language: String = ""
) : QuestionDetector {

    override fun detect(lines: Array<OcrResult>, source: Bitmap): List<QuestionRegion> =
        detectFromLines(lines, source.height.toFloat())

    internal fun detectFromLines(
        lines: Array<OcrResult>,
        imageHeight: Float
    ): List<QuestionRegion> {
        if (lines.isEmpty()) return emptyList()

        val sorted = lines.map { LineWithRect(it, it.toAlignedRect()) }.sortedBy { it.rect.top }

        val calibration = calibrate(sorted, imageHeight)
        val signals     = buildSignals(calibration)

        return sorted
            .mapIndexed { i, line ->
                val ctx = LineContext(
                    line     = line.result,
                    rect     = line.rect,
                    index    = i,
                    gapAbove = if (i == 0) Float.MAX_VALUE else line.rect.top - sorted[i - 1].rect.bottom,
                    prevLine = if (i == 0) null else sorted[i - 1].result
                )
                MarkedLine(line.result, line.rect, isQuestionStart(ctx, signals, calibration, isFirst = i == 0)).apply {
                    debugLog("line $i is questionStart: $isQuestionStart")
                }
            }
            .groupIntoRegions()
    }

    // ── Pass 1: calibration ───────────────────────────────────────────────────

    private fun calibrate(sorted: List<LineWithRect>, imageHeight: Float): CalibrationData {
        if (sorted.size < 2) return minimalFallback(imageHeight)

        val medianH = median(sorted.map { it.rect.height() })
        if (medianH <= 0f) return minimalFallback(imageHeight)

        val gapLarge     = 1.5f * medianH
        val gapVeryLarge = 3.0f * medianH
        val modalMargin  = computeModalLeftMargin(sorted.map { it.rect.left })

        val highConf = sorted.filter { it.result.score >= HIGH_CONF_THRESHOLD }
        if (highConf.isEmpty()) {
            return CalibrationData(CalibrationStatus.GEOMETRY_ONLY,
                medianH, modalMargin, gapLarge, gapVeryLarge, null, imageHeight)
        }

        val candidates = highConf.filter { ANY_NUMBERED_PATTERN.containsMatchIn(it.result.text.trim()) }
        if (candidates.size < ANCHOR_MIN_COUNT) {
            return CalibrationData(CalibrationStatus.PARTIAL,
                medianH, modalMargin, gapLarge, gapVeryLarge, null, imageHeight)
        }

        val style           = detectStyle(candidates.first().result.text)
        val observedNumbers = candidates
            .mapNotNull { extractInteger(it.result.text, style) }
            .sorted().distinct()
        val leftMin    = candidates.minOf { it.rect.left }
        val leftMax    = candidates.maxOf { it.rect.left }
        val confidence = minOf(1.0f, candidates.size.toFloat() / ANCHOR_CONFIDENCE_FULL)

        val profile = AnchorProfile(leftMin..leftMax, style, observedNumbers, confidence)
        return CalibrationData(CalibrationStatus.FULL,
            medianH, modalMargin, gapLarge, gapVeryLarge, profile, imageHeight)
    }

    private fun minimalFallback(imageHeight: Float) = CalibrationData(
        status           = CalibrationStatus.MINIMAL,
        medianLineHeight = imageHeight / FALLBACK_GAP_DIVISOR_LARGE.toFloat(),
        modalLeftMargin  = 0f,
        gapLarge         = imageHeight / FALLBACK_GAP_DIVISOR_LARGE.toFloat(),
        gapVeryLarge     = imageHeight / FALLBACK_GAP_DIVISOR_VERY_LARGE.toFloat(),
        anchorProfile    = null,
        imageHeight      = imageHeight
    )

    // ── Calibration helpers ───────────────────────────────────────────────────

    private fun detectStyle(text: String): NumberedMarkerStyle =
        STYLE_PATTERNS.entries
            .firstOrNull { (_, regex) -> regex.containsMatchIn(text.trim()) }
            ?.key ?: NumberedMarkerStyle.NUMERIC_DOT

    internal fun extractInteger(text: String, style: NumberedMarkerStyle): Int? {
        val t = text.trim()
        val raw: Int? = when (style) {
            NumberedMarkerStyle.NUMERIC_DOT,
            NumberedMarkerStyle.NUMERIC_PAREN,
            NumberedMarkerStyle.NUMERIC_ENCLOSED_PAREN ->
                Regex("""\d{1,3}""").find(t)?.value?.toIntOrNull()

            NumberedMarkerStyle.NUMERIC_FULLWIDTH_PAREN ->
                Regex("""\d{1,3}""").find(t.trimStart('（', '('))?.value?.toIntOrNull()

            NumberedMarkerStyle.CIRCLED_NUMBER ->
                if (t.isNotEmpty() && t[0] in '①'..'⑳')
                    t[0].code - '①'.code + 1
                else null

            NumberedMarkerStyle.Q_PREFIX ->
                Regex("""(?i)Q\.?(\d{1,3})""").find(t)?.groupValues?.get(1)?.toIntOrNull()

            NumberedMarkerStyle.JP_MONDAI ->
                Regex("""問題(\d{1,3})""").find(t)?.groupValues?.get(1)?.toIntOrNull()

            NumberedMarkerStyle.JP_SETSUMON ->
                Regex("""設問(\d{1,3})""").find(t)?.groupValues?.get(1)?.toIntOrNull()

            NumberedMarkerStyle.JP_MON ->
                Regex("""問(\d{1,3})""").find(t)?.groupValues?.get(1)?.toIntOrNull()

            NumberedMarkerStyle.DAI_MON ->
                Regex("""第(\d{1,3})問""").find(t)?.groupValues?.get(1)?.toIntOrNull()
        }
        return raw?.takeIf { it in 1..200 }
    }

    // ── Math utilities ────────────────────────────────────────────────────────

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    private fun computeModalLeftMargin(values: List<Float>, binSize: Float = 5f): Float {
        if (values.isEmpty()) return 0f
        return values
            .groupBy { (it / binSize).roundToInt() }
            .maxByOrNull { it.value.size }
            ?.let { (bin, _) -> bin * binSize }
            ?: 0f
    }

    // ── Signal evaluation helpers ─────────────────────────────────────────────

    internal fun matchesAnchorProfile(ctx: LineContext, profile: AnchorProfile): Boolean {
        val t = ctx.line.text.trim()

        // Criterion 1: text matches the document's specific style pattern
        val stylePattern = STYLE_PATTERNS[profile.style] ?: return false
        if (!stylePattern.containsMatchIn(t)) return false

        // Criterion 2: left edge is within the profile's x-range (± tolerance)
        val xLow  = profile.leftMarginRange.start          - MARGIN_TOLERANCE
        val xHigh = profile.leftMarginRange.endInclusive   + MARGIN_TOLERANCE
        if (ctx.rect.left !in xLow..xHigh) return false

        // Criterion 3: integer extends the observed sequence
        val n = extractInteger(t, profile.style) ?: return false
        val observed = profile.observedNumbers
        if (observed.isEmpty()) return true          // no sequence yet — criteria 1+2 are sufficient
        val maxSeen = observed.max()
        return n !in observed && n > maxSeen && n <= maxSeen + 2
    }

    // Fires the mismatch penalty: looks like a numbered marker but fails the profile.
    // Normal text that contains no marker token is never penalised (clause 1 gates it).
    internal fun looksLikeNumberedButMismatch(ctx: LineContext, profile: AnchorProfile): Boolean =
        ANY_NUMBERED_PATTERN.containsMatchIn(ctx.line.text.trim())
            && !matchesAnchorProfile(ctx, profile)

    // ── Pass 2: signal list construction ─────────────────────────────────────

    private fun buildSignals(calibration: CalibrationData): List<Signal> {
        val signals = mutableListOf<Signal>()

        // ── Geometric ─────────────────────────────────────────────────────────
        signals += Signal("gap_very_large",  score =  8) { ctx, cal ->
            ctx.gapAbove > cal.gapVeryLarge
        }
        signals += Signal("gap_large",       score =  5) { ctx, cal ->
            ctx.gapAbove in cal.gapLarge..cal.gapVeryLarge
        }
        signals += Signal("gap_small",       score = -3) { ctx, cal ->
            ctx.gapAbove != Float.MAX_VALUE && ctx.gapAbove < cal.medianLineHeight * 0.8f
        }
        signals += Signal("margin_reset",    score =  4) { ctx, cal ->
            abs(ctx.rect.left - cal.modalLeftMargin) <= MARGIN_TOLERANCE
        }
        signals += Signal("margin_indented", score = -3) { ctx, cal ->
            ctx.rect.left > cal.modalLeftMargin + MARGIN_TOLERANCE
        }

        // ── Structural ────────────────────────────────────────────────────────
        signals += Signal("numbered_marker",    score =  9) { ctx, _ ->
            ANY_NUMBERED_PATTERN.containsMatchIn(ctx.line.text.trim())
        }
        signals += Signal("horizontal_rule",    score = 10) { ctx, cal ->
            isHorizontalRule(ctx.rect, cal.imageHeight)
        }
        signals += Signal("header_footer_zone", score = -8) { ctx, cal ->
            ctx.rect.top    < cal.imageHeight * HEADER_FOOTER_FRACTION ||
            ctx.rect.bottom > cal.imageHeight * (1f - HEADER_FOOTER_FRACTION)
        }
        signals += Signal("marks_tag_prev",     score =  3) { ctx, _ ->
            ctx.prevLine != null && MARKS_TAG_PATTERN.containsMatchIn(ctx.prevLine.text.trim())
        }

        // ── Anchor profile boost / penalty (FULL only) ────────────────────────
        if (calibration.status == CalibrationStatus.FULL && calibration.anchorProfile != null) {
            val boost = maxOf(1, (2f * calibration.anchorProfile.confidence).roundToInt())
            signals += Signal("anchor_match",    score =  boost) { ctx, cal ->
                matchesAnchorProfile(ctx, cal.anchorProfile!!)
            }
            signals += Signal("anchor_mismatch", score = -3) { ctx, cal ->
                looksLikeNumberedButMismatch(ctx, cal.anchorProfile!!)
            }
        }

        // ── Semantic (skipped for GEOMETRY_ONLY) ──────────────────────────────
        if (calibration.status != CalibrationStatus.GEOMETRY_ONLY) {
            val lang = language.lowercase()

            signals += Signal("question_keyword",       score = 8) { ctx, _ ->
                val t = ctx.line.text.trim()
                when (lang) {
                    "ja" -> JA_KEYWORD.containsMatchIn(t)
                    "en" -> EN_KEYWORD.containsMatchIn(t)
                    else -> EN_KEYWORD.containsMatchIn(t) || JA_KEYWORD.containsMatchIn(t)
                }
            }
            signals += Signal("imperative_verb",        score = 4) { ctx, _ ->
                val t = ctx.line.text.trim()
                when (lang) {
                    "ja" -> JA_IMPERATIVE.containsMatchIn(t)
                    "en" -> EN_IMPERATIVE.containsMatchIn(t)
                    else -> EN_IMPERATIVE.containsMatchIn(t) || JA_IMPERATIVE.containsMatchIn(t)
                }
            }
            signals += Signal("interrogative_word",     score = 4) { ctx, _ ->
                val t = ctx.line.text.trim()
                when (lang) {
                    "ja" -> JA_INTERROGATIVE.containsMatchIn(t)
                    "en" -> EN_INTERROGATIVE.containsMatchIn(t) || EN_INTERROGATIVE_PHRASE.containsMatchIn(t)
                    else -> EN_INTERROGATIVE.containsMatchIn(t) || EN_INTERROGATIVE_PHRASE.containsMatchIn(t)
                         || JA_INTERROGATIVE.containsMatchIn(t)
                }
            }
            signals += Signal("conditional_imperative", score = 4) { ctx, _ ->
                val t = ctx.line.text.trim()
                when (lang) {
                    "ja" -> JA_CONDITIONAL.containsMatchIn(t)
                    "en" -> EN_CONDITIONAL.containsMatchIn(t)
                    else -> EN_CONDITIONAL.containsMatchIn(t) || JA_CONDITIONAL.containsMatchIn(t)
                }
            }
            // JA_QUESTION_ENDING (ですか/ますか/…) scores identically to a trailing ？;
            // excluded only when language is explicitly "en".
            signals += Signal("trailing_question_mark", score = 3) { ctx, _ ->
                val t = ctx.line.text.trim()
                t.endsWith('?') || t.endsWith('？')
                    || (lang != "en" && JA_QUESTION_ENDING.containsMatchIn(t))
            }
            signals += Signal("very_short_line",        score = -2) { ctx, _ ->
                ctx.line.text.trim().length < 3
            }
        }

        return signals
    }

    // ── Pass 2: per-line tier decision ────────────────────────────────────────

    private fun isQuestionStart(
        ctx: LineContext,
        signals: List<Signal>,
        calibration: CalibrationData,
        isFirst: Boolean
    ): Boolean {
        val fired         = signals.filter { it.evaluate(ctx, calibration) }
        val netScore      = fired.sumOf { it.score }
        val maxIndividual = fired.maxOfOrNull { it.score } ?: 0
        val positives     = fired.filter { it.score > 0 }
        val hasSubQuestion = SUB_QUESTION_PATTERN.containsMatchIn(ctx.line.text.trim())

        debugLog(
            "line=${ctx.index} text=${ctx.line.text.take(40)} gapAbove=${ctx.gapAbove} " +
                    "net=$netScore max=$maxIndividual fired=${fired.joinToString { it.name }} hasSubQuestion: $hasSubQuestion"
        )

        return when {
            // Tier 1: one authoritative signal fires — sub-question veto cannot override
            maxIndividual >= TIER1_THRESHOLD                                                         -> true
            // Hard contextual veto for Tier 2 and below
            hasSubQuestion                                                                            -> false
            // Tier 2: net score qualifies AND at least one medium-weight positive fired
            netScore >= TIER2_THRESHOLD && positives.any { it.score >= TIER2_THRESHOLD }             -> true
            // Tier 3: net score qualifies AND at least two independent weak positives agree
            netScore >= TIER3_THRESHOLD && positives.count { it.score >= TIER3_THRESHOLD } >= TIER3_MIN_COUNT -> true
            // First-line relaxation: no prior group to attach to, so threshold is net ≥ Tier 3 alone
            isFirst && netScore >= TIER3_THRESHOLD                                                   -> true
            else                                                                                     -> false
        }
    }

    private fun isHorizontalRule(rect: RectF, imageHeight: Float): Boolean =
        rect.width() > imageHeight * 0.4f && rect.height() < 5f

    // ── Grouping ──────────────────────────────────────────────────────────────

    private fun List<MarkedLine>.groupIntoRegions(): List<QuestionRegion> {
        val regions = mutableListOf<QuestionRegion>()
        var group   = mutableListOf<MarkedLine>()
        for (line in this) {
            if (line.isQuestionStart) {
                if (group.isNotEmpty()) regions += group.toRegion(regions.size)
                group = mutableListOf(line)
            } else if (group.isNotEmpty()) {
                group += line
            }
        }
        if (group.isNotEmpty()) regions += group.toRegion(regions.size)
        return regions
    }

    private fun List<MarkedLine>.toRegion(index: Int): QuestionRegion {
        val boxes = map { it.rect }
        return QuestionRegion(
            index      = index,
            bounds     = RectF(boxes.minOf { it.left }, boxes.minOf { it.top },
                               boxes.maxOf { it.right }, boxes.maxOf { it.bottom }),
            confidence = map { it.result.score }.average().toFloat(),
            lineBoxes  = boxes
        )
    }

    // ── Private data types ────────────────────────────────────────────────────

    private data class LineWithRect(val result: OcrResult, val rect: RectF)
    private data class MarkedLine(val result: OcrResult, val rect: RectF, val isQuestionStart: Boolean)

    // ── Nested types (direct class members — importable as OuterClass.TypeName) ─

    enum class CalibrationStatus {
        FULL,           // geometry + anchorProfile + semantics
        PARTIAL,        // geometry available, anchorProfile absent
        GEOMETRY_ONLY,  // geometry available; OCR scores too low for semantics
        MINIMAL         // geometry unavailable; hardcoded safe defaults used
    }

    enum class NumberedMarkerStyle {
        NUMERIC_DOT,              // 1.   2.   3.
        NUMERIC_PAREN,            // 1)   2)   3)
        NUMERIC_ENCLOSED_PAREN,   // (1)  (2)  (3)   — ASCII parens both sides
        NUMERIC_FULLWIDTH_PAREN,  // 1）  2）  （1） — full-width parens
        CIRCLED_NUMBER,           // ①   ②  …  ⑳
        Q_PREFIX,                 // Q1   Q2   Q.1   Q.2
        JP_MONDAI,                // 問題1  問題2
        JP_SETSUMON,              // 設問1  設問2
        JP_MON,                   // 問1   問2   (after JP_MONDAI/JP_SETSUMON in alternation)
        DAI_MON                   // 第1問  第2問  (university entrance format)
    }

    data class AnchorProfile(
        val leftMarginRange: ClosedFloatingPointRange<Float>,
        val style: NumberedMarkerStyle,
        val observedNumbers: List<Int>,
        val confidence: Float  // 0.0–1.0; saturates at ANCHOR_CONFIDENCE_FULL
    )

    data class CalibrationData(
        val status: CalibrationStatus,
        val medianLineHeight: Float,
        val modalLeftMargin: Float,
        val gapLarge: Float,       // 1.5 × medianLineHeight
        val gapVeryLarge: Float,   // 3.0 × medianLineHeight
        val anchorProfile: AnchorProfile?,
        val imageHeight: Float
    )

    class Signal(
        val name: String,
        val score: Int,
        val evaluate: (LineContext, CalibrationData) -> Boolean
    )

    data class LineContext(
        val line: OcrResult,
        val rect: RectF,
        val index: Int,
        val gapAbove: Float,   // Float.MAX_VALUE for first line
        val prevLine: OcrResult?
    )

    // ── Companion object: constants and patterns ──────────────────────────────

    companion object {

        // ── Tuning constants ──────────────────────────────────────────────────

        const val TIER1_THRESHOLD        = 9
        const val TIER2_THRESHOLD        = 5
        const val TIER3_THRESHOLD        = 2
        const val TIER3_MIN_COUNT        = 2

        const val HIGH_CONF_THRESHOLD    = 0.85f
        const val MARGIN_TOLERANCE       = 10f
        const val ANCHOR_MIN_COUNT       = 3
        const val ANCHOR_CONFIDENCE_FULL = 5
        const val HEADER_FOOTER_FRACTION = 0.05f

        const val FALLBACK_GAP_DIVISOR_LARGE      = 40
        const val FALLBACK_GAP_DIVISOR_VERY_LARGE = 20

        // ── Numbered marker patterns (one per style, ordered) ─────────────────
        // JP_MONDAI and JP_SETSUMON must precede JP_MON in any alternation because
        // all three start with 問 or 設.

        private val STYLE_NUMERIC_DOT             = Regex("""^\d{1,3}\.\s+""")
        private val STYLE_NUMERIC_PAREN           = Regex("""^\d{1,3}\)\s*""")
        private val STYLE_NUMERIC_ENCLOSED_PAREN  = Regex("""^\(\d{1,3}\)\s*""")
        private val STYLE_NUMERIC_FULLWIDTH_PAREN = Regex("""^(?:[（(]\d{1,3}[)）]|\d{1,3}[）])\s*""")
        private val STYLE_CIRCLED_NUMBER          = Regex("""^[①-⑳]\s*""")
        private val STYLE_Q_PREFIX                = Regex("""(?i)^Q\.?\d{1,3}\b""")
        private val STYLE_JP_MONDAI               = Regex("""^問題\d{1,3}""")
        private val STYLE_JP_SETSUMON             = Regex("""^設問\d{1,3}""")
        private val STYLE_JP_MON                  = Regex("""^問\d{1,3}""")
        private val STYLE_DAI_MON                 = Regex("""^第\d{1,3}問""")

        // Ordered map: used for style detection and per-style matching in calibration.
        // JP_MONDAI and JP_SETSUMON are keyed before JP_MON.
        internal val STYLE_PATTERNS = linkedMapOf(
            NumberedMarkerStyle.NUMERIC_DOT             to STYLE_NUMERIC_DOT,
            NumberedMarkerStyle.NUMERIC_PAREN           to STYLE_NUMERIC_PAREN,
            NumberedMarkerStyle.NUMERIC_ENCLOSED_PAREN  to STYLE_NUMERIC_ENCLOSED_PAREN,
            NumberedMarkerStyle.NUMERIC_FULLWIDTH_PAREN to STYLE_NUMERIC_FULLWIDTH_PAREN,
            NumberedMarkerStyle.CIRCLED_NUMBER          to STYLE_CIRCLED_NUMBER,
            NumberedMarkerStyle.Q_PREFIX                to STYLE_Q_PREFIX,
            NumberedMarkerStyle.JP_MONDAI               to STYLE_JP_MONDAI,
            NumberedMarkerStyle.JP_SETSUMON             to STYLE_JP_SETSUMON,
            NumberedMarkerStyle.JP_MON                  to STYLE_JP_MON,
            NumberedMarkerStyle.DAI_MON                 to STYLE_DAI_MON
        )

        // Union pattern for the calibration pass — matches any numbered marker.
        // JP_MONDAI / JP_SETSUMON alternatives appear before JP_MON.
        internal val ANY_NUMBERED_PATTERN = Regex(
            """^(?:\d{1,3}\.\s+|\d{1,3}\)\s*|\(\d{1,3}\)\s*|""" +
            """[（(]\d{1,3}[)）]\s*|\d{1,3}[）]\s*|[①-⑳]\s*|""" +
            """(?i)Q\.?\d{1,3}\b|問題\d{1,3}|設問\d{1,3}|問\d{1,3}|第\d{1,3}問)"""
        )

        // Sub-question marker — negative structural signal (hard contextual veto at Tier 2/3).
        // Covers: a.  b)  (i)  (ii)  (iii)  (iv)  a）  ア)
        internal val SUB_QUESTION_PATTERN = Regex(
            """^(?:[a-zA-Z][.)]\s|""" +
            """\([ivxlcdmIVXLCDM]{1,4}\)\s*|""" +
            """\([a-zA-Z]\)\s*|""" +
            """[ア-ン][)）]\s*)"""
        )

        // Marks / score tag at end of a line — structural signal on the *previous* line.
        // Covers: [3 marks]  (5 marks)  (5点)  [10点]  (2 pts)
        internal val MARKS_TAG_PATTERN = Regex(
            """(?:\[\d+\s*(?:marks?|pts?|点)\]|\(\d+\s*(?:marks?|pts?|点)\))\s*${'$'}""",
            RegexOption.IGNORE_CASE
        )

        // ── English semantic patterns ─────────────────────────────────────────

        private val EN_KEYWORD = Regex("""(?i)^question\b""")
        private val EN_INTERROGATIVE = Regex("""(?i)^(?:What|Which)\b""")
        private val EN_INTERROGATIVE_PHRASE = Regex("""(?i)^How\s+(?:many|much)\b""")

        private val EN_VERBS =
            """Calculate|Compute|Evaluate|Simplify|Estimate|Approximate|""" +
            """Solve|Find|Determine|Derive|Expand|Factorise|Factorize|Differentiate|Integrate|Express|Convert|""" +
            """Prove|Show|Verify|Justify|Sketch|Plot|Draw|Graph|Construct|Label|""" +
            """Explain|Describe|Discuss|Analyse|Analyze|Investigate|Predict|Compare|Contrast|""" +
            """Define|State|Identify|List|Name"""

        private val EN_IMPERATIVE  = Regex("""(?i)^($EN_VERBS)\b""")
        private val EN_CONDITIONAL = Regex("""(?i)^(?:Given|If|For|When|Assuming|Let|Suppose)\b.+\b($EN_VERBS)\b""")

        // Language-dispatched composite patterns (used in buildSignals).
        // Each returns a Regex appropriate for the active language setting.

        // ── Japanese semantic patterns ────────────────────────────────────────

        private val JA_KEYWORD       = Regex("""^(?:問題|設問|問)\s*\d*""")
        private val JA_INTERROGATIVE = Regex("""^(?:何|どれ|どの|どう|どのよう|なぜ|どうして|いつ|どこ|だれ|誰|いくつ|いくら)""")
        private val JA_QUESTION_ENDING = Regex("""(?:ですか|ますか|でしょうか|だろうか|かな)[。？]?${'$'}""")

        private val JA_VERBS =
            """求めよ|求めなさい|""" +
            """計算せよ|計算しなさい|証明せよ|証明しなさい|微分せよ|微分しなさい|積分せよ|積分しなさい|""" +
            """展開せよ|展開しなさい|因数分解せよ|因数分解しなさい|簡略化せよ|簡略化しなさい|""" +
            """近似せよ|近似しなさい|評価せよ|評価しなさい|説明せよ|説明しなさい|""" +
            """比較せよ|比較しなさい|対比せよ|対比しなさい|定義せよ|定義しなさい|""" +
            """変換せよ|変換しなさい|予測せよ|予測しなさい|""" +
            """述べよ|述べなさい|調べよ|調べなさい|確かめよ|確かめなさい|""" +
            """解け|解きなさい|描け|描きなさい|示せ|示しなさい"""

        // Japanese SOV: imperative verb at line END
        private val JA_IMPERATIVE  = Regex("""(?:$JA_VERBS)[。]?${'$'}""")
        private val JA_CONDITIONAL = Regex("""(?:のとき|ならば?|とすれば|において|の場合|を仮定して|とする).+?(?:$JA_VERBS)[。]?${'$'}""")
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("SignalFusionDetector", message)
        }
    }
}

private fun OcrResult.toAlignedRect(): RectF {
    val xs = (0..3).map { box[it][0].toFloat() }
    val ys = (0..3).map { box[it][1].toFloat() }
    return RectF(xs.min(), ys.min(), xs.max(), ys.max())
}
