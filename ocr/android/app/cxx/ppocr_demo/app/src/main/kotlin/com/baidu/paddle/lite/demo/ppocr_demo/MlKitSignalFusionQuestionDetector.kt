package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlin.math.abs

/**
 * ML Kit tuned signal-fusion detector.
 */
class MlKitSignalFusionQuestionDetector @JvmOverloads constructor(
    private val language: String = ""
) : QuestionDetector {
    private val legacyDetector by lazy { SignalFusionQuestionDetector(language) }

    override fun detect(lines: Array<OcrResult>, source: Bitmap): List<QuestionRegion> {
        if (!OcrFeatureFlags.USE_MLKIT_QUESTION_DETECTOR) {
            debugLog("fallback legacy detector (feature flag disabled)")
            return legacyDetector.detect(lines, source)
        }
        val normalizedGeoms = MlKitLineGeometryNormalizer
            .normalizeLines(lines, source.width, source.height)
        if (normalizedGeoms.isEmpty()) return emptyList()

        val calibration = MlKitCalibrationEngine.calibrate(
            lines = normalizedGeoms,
            imageWidth = source.width.toFloat(),
            imageHeight = source.height.toFloat()
        )
        debugLog("calibration status=${calibration.status} medianH=${calibration.medianLineHeight} gapL=${calibration.gapLarge} gapVL=${calibration.gapVeryLarge}")
        return detectFromGeoms(normalizedGeoms, calibration)
    }

    internal fun detectFromLines(
        lines: Array<OcrResult>,
        imageHeight: Float
    ): List<QuestionRegion> {
        if (!OcrFeatureFlags.USE_MLKIT_QUESTION_DETECTOR) {
            return legacyDetector.detectFromLines(lines, imageHeight)
        }
        if (lines.isEmpty()) return emptyList()
        val inferredWidth = inferImageWidth(lines)
        val geoms = MlKitLineGeometryNormalizer.normalizeLines(
            lines = lines,
            imageWidth = inferredWidth,
            imageHeight = imageHeight.toInt().coerceAtLeast(1)
        )
        if (geoms.isEmpty()) return emptyList()
        val calibration = MlKitCalibrationEngine.calibrate(
            lines = geoms,
            imageWidth = inferredWidth.toFloat(),
            imageHeight = imageHeight
        )
        debugLog("calibration status=${calibration.status} medianH=${calibration.medianLineHeight} gapL=${calibration.gapLarge} gapVL=${calibration.gapVeryLarge}")
        return detectFromGeoms(geoms, calibration)
    }

    internal fun calibrateFromLines(
        lines: Array<OcrResult>,
        imageWidth: Int,
        imageHeight: Int
    ): MlKitCalibrationData {
        val geoms = MlKitLineGeometryNormalizer.normalizeLines(lines, imageWidth, imageHeight)
        return MlKitCalibrationEngine.calibrate(
            lines = geoms,
            imageWidth = imageWidth.toFloat(),
            imageHeight = imageHeight.toFloat()
        )
    }

    private fun detectFromGeoms(
        geoms: List<MlKitLineGeom>,
        calibration: MlKitCalibrationData
    ): List<QuestionRegion> {
        val sorted = geoms.sortedBy { it.rect.top }
        val signals = buildSignals(calibration)

        return sorted
            .mapIndexed { i, item ->
                val prev = sorted.getOrNull(i - 1)
                val ctx = LineContext(
                    line = item.normalized,
                    rect = item.rect,
                    quality = item.quality,
                    index = i,
                    gapAbove = if (prev == null) Float.MAX_VALUE else item.rect.top - prev.rect.bottom,
                    prevLine = prev?.normalized
                )
                MarkedLine(
                    result = item.normalized,
                    rect = item.rect,
                    isQuestionStart = isQuestionStart(ctx, signals, calibration, isFirst = i == 0)
                ).apply {
                    debugLog("line $i is questionStart: $isQuestionStart")
                }
            }
            .groupIntoRegions()
    }

    private fun buildSignals(calibration: MlKitCalibrationData): List<Signal> {
        val signals = mutableListOf<Signal>()

        signals += Signal(name = "gap_very_large", score = 8) { ctx, cal ->
            if (ctx.quality == GeometryQuality.RECT_FALLBACK) {
                ctx.gapAbove > cal.gapVeryLarge * FALLBACK_GAP_VERY_LARGE_MULTIPLIER
            } else {
                ctx.gapAbove > cal.gapVeryLarge
            }
        }
        signals += Signal(name = "gap_large", score = 5) { ctx, cal ->
            if (ctx.quality == GeometryQuality.RECT_FALLBACK) return@Signal false
            ctx.gapAbove in cal.gapLarge..cal.gapVeryLarge
        }
        signals += Signal(name = "gap_small", score = -3) { ctx, cal ->
            ctx.gapAbove != Float.MAX_VALUE && ctx.gapAbove < cal.medianLineHeight * GAP_SMALL_MULTIPLIER
        }
        signals += Signal(name = "margin_reset", score = 4) { ctx, cal ->
            abs(ctx.rect.left - cal.modalLeftMargin) <= MARGIN_TOLERANCE
        }
        signals += Signal(name = "margin_indented", score = -3) { ctx, cal ->
            ctx.rect.left > cal.modalLeftMargin + MARGIN_TOLERANCE
        }

        signals += Signal(name = "numbered_marker", score = 9) { ctx, _ ->
            SignalFusionQuestionDetector.ANY_NUMBERED_PATTERN.containsMatchIn(ctx.line.text.trim())
        }
        signals += Signal(name = "horizontal_rule", score = 10) { ctx, cal ->
            isHorizontalRule(ctx.rect, cal.imageWidth, cal.medianLineHeight)
        }
        signals += Signal(name = "header_footer_zone", score = -8) { ctx, cal ->
            ctx.rect.top < cal.imageHeight * HEADER_FOOTER_FRACTION ||
                ctx.rect.bottom > cal.imageHeight * (1f - HEADER_FOOTER_FRACTION)
        }
        signals += Signal(name = "marks_tag_prev", score = 3) { ctx, _ ->
            ctx.prevLine != null &&
                SignalFusionQuestionDetector.MARKS_TAG_PATTERN.containsMatchIn(ctx.prevLine.text.trim())
        }

        if (calibration.status != MlKitCalibrationStatus.GEOMETRY_ONLY) {
            val lang = language.lowercase()
            signals += Signal(name = "question_keyword", score = 8) { ctx, _ ->
                val t = ctx.line.text.trim()
                when (lang) {
                    "ja" -> JA_KEYWORD.containsMatchIn(t)
                    "en" -> EN_KEYWORD.containsMatchIn(t)
                    else -> EN_KEYWORD.containsMatchIn(t) || JA_KEYWORD.containsMatchIn(t)
                }
            }
            signals += Signal(name = "imperative_verb", score = 4) { ctx, _ ->
                val t = ctx.line.text.trim()
                when (lang) {
                    "ja" -> JA_IMPERATIVE.containsMatchIn(t)
                    "en" -> EN_IMPERATIVE.containsMatchIn(t)
                    else -> EN_IMPERATIVE.containsMatchIn(t) || JA_IMPERATIVE.containsMatchIn(t)
                }
            }
            signals += Signal(name = "interrogative_word", score = 4) { ctx, _ ->
                val t = ctx.line.text.trim()
                when (lang) {
                    "ja" -> JA_INTERROGATIVE.containsMatchIn(t)
                    "en" -> EN_INTERROGATIVE.containsMatchIn(t) || EN_INTERROGATIVE_PHRASE.containsMatchIn(t)
                    else -> EN_INTERROGATIVE.containsMatchIn(t) || EN_INTERROGATIVE_PHRASE.containsMatchIn(t) ||
                        JA_INTERROGATIVE.containsMatchIn(t)
                }
            }
            signals += Signal(name = "conditional_imperative", score = 4) { ctx, _ ->
                val t = ctx.line.text.trim()
                when (lang) {
                    "ja" -> JA_CONDITIONAL.containsMatchIn(t)
                    "en" -> EN_CONDITIONAL.containsMatchIn(t)
                    else -> EN_CONDITIONAL.containsMatchIn(t) || JA_CONDITIONAL.containsMatchIn(t)
                }
            }
            signals += Signal(name = "trailing_question_mark", score = 3) { ctx, _ ->
                val t = ctx.line.text.trim()
                t.endsWith('?') || t.endsWith('？') ||
                    (lang != "en" && JA_QUESTION_ENDING.containsMatchIn(t))
            }
            signals += Signal(name = "very_short_line", score = -2) { ctx, _ ->
                ctx.line.text.trim().length < 3
            }
        }
        return signals
    }

    private fun isQuestionStart(
        ctx: LineContext,
        signals: List<Signal>,
        calibration: MlKitCalibrationData,
        isFirst: Boolean
    ): Boolean {
        val fired = signals.filter { it.evaluate(ctx, calibration) }
        val netScore = fired.sumOf { it.score }
        val maxIndividual = fired.maxOfOrNull { it.score } ?: 0
        val positives = fired.filter { it.score > 0 }
        val hasSubQuestion = SignalFusionQuestionDetector.SUB_QUESTION_PATTERN.containsMatchIn(ctx.line.text.trim())
        debugLog(
            "line=${ctx.index} text=${ctx.line.text.take(40)} quality=${ctx.quality} " +
                "net=$netScore max=$maxIndividual fired=${fired.joinToString { it.name }}"
        )

        return when {
            maxIndividual >= TIER1_THRESHOLD -> true
            hasSubQuestion -> false
            netScore >= TIER2_THRESHOLD && positives.any { it.score >= TIER2_THRESHOLD } -> true
            netScore >= TIER3_THRESHOLD && positives.count { it.score >= TIER3_THRESHOLD } >= TIER3_MIN_COUNT -> true
            isFirst && netScore >= TIER3_THRESHOLD -> true
            else -> false
        }
    }

    private fun isHorizontalRule(rect: RectF, imageWidth: Float, medianLineHeight: Float): Boolean {
        if (imageWidth <= 0f || medianLineHeight <= 0f) return false
        val thickLimit = maxOf(2f, medianLineHeight * RULE_THICKNESS_RATIO)
        return rect.width() > imageWidth * RULE_MIN_WIDTH_FRACTION && rect.height() <= thickLimit
    }

    private fun List<MarkedLine>.groupIntoRegions(): List<QuestionRegion> {
        val regions = mutableListOf<QuestionRegion>()
        var group = mutableListOf<MarkedLine>()
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
            index = index,
            bounds = RectF(
                boxes.minOf { it.left },
                boxes.minOf { it.top },
                boxes.maxOf { it.right },
                boxes.maxOf { it.bottom }
            ),
            confidence = map { it.result.score }.average().toFloat(),
            lineBoxes = boxes
        )
    }

    private fun inferImageWidth(lines: Array<OcrResult>): Int {
        val maxX = lines
            .flatMap { it.box.asList() }
            .mapNotNull { pt -> pt.getOrNull(0) }
            .maxOrNull() ?: 0
        return (maxX + 1).coerceAtLeast(1)
    }

    private data class MarkedLine(
        val result: OcrResult,
        val rect: RectF,
        val isQuestionStart: Boolean
    )

    private data class LineContext(
        val line: OcrResult,
        val rect: RectF,
        val quality: GeometryQuality,
        val index: Int,
        val gapAbove: Float,
        val prevLine: OcrResult?
    )

    private class Signal(
        val name: String,
        val score: Int,
        val evaluate: (LineContext, MlKitCalibrationData) -> Boolean
    )

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG && OcrFeatureFlags.ENABLE_MLKIT_DETECTOR_DEBUG_LOGS) {
            Log.d(DEBUG_TAG, message)
        }
    }

    companion object {
        private const val DEBUG_TAG = "MlKitQDetector"
        const val TIER1_THRESHOLD = 9
        const val TIER2_THRESHOLD = 5
        const val TIER3_THRESHOLD = 2
        const val TIER3_MIN_COUNT = 2
        const val MARGIN_TOLERANCE = 10f
        const val HEADER_FOOTER_FRACTION = 0.05f
        const val GAP_SMALL_MULTIPLIER = 0.8f
        const val RULE_MIN_WIDTH_FRACTION = 0.4f
        const val RULE_THICKNESS_RATIO = 0.22f
        const val FALLBACK_GAP_VERY_LARGE_MULTIPLIER = 1.4f

        private val EN_KEYWORD = Regex("""(?i)^question\b""")
        private val EN_INTERROGATIVE = Regex("""(?i)^(?:What|Which|Where|Why|When)\b""")
        private val EN_INTERROGATIVE_PHRASE = Regex("""(?i)^How\s+(?:many|much)\b""")

        private val EN_VERBS =
            """Calculate|Compute|Evaluate|Simplify|Estimate|Approximate|""" +
                """Solve|Find|Determine|Derive|Expand|Factorise|Factorize|Differentiate|Integrate|Express|Convert|""" +
                """Prove|Show|Verify|Justify|Sketch|Plot|Draw|Graph|Construct|Label|""" +
                """Explain|Describe|Discuss|Analyse|Analyze|Investigate|Predict|Compare|Contrast|""" +
                """Define|State|Identify|List|Name"""

        private val EN_IMPERATIVE = Regex("""(?i)^($EN_VERBS)\b""")
        private val EN_CONDITIONAL = Regex("""(?i)^(?:Given|If|For|When|Assuming|Let|Suppose)\b.+\b($EN_VERBS)\b""")

        private val JA_KEYWORD = Regex("""^(?:問題|設問|問)\s*\d*""")
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
        private val JA_IMPERATIVE = Regex("""(?:$JA_VERBS)[。]?${'$'}""")
        private val JA_CONDITIONAL = Regex("""(?:のとき|ならば?|とすれば|において|の場合|を仮定して|とする).+?(?:$JA_VERBS)[。]?${'$'}""")
    }
}
