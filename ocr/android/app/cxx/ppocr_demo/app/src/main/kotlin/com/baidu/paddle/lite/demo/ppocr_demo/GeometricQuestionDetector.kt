package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

class GeometricQuestionDetector @JvmOverloads constructor(
    private val gapMultiplier: Float = 1.2f,
    private val handwritingScoreThreshold: Float = 0.80f,
    private val language: String = ""
) : QuestionDetector {

    private val TAG = "GeoQuestionDetector"

    override fun detect(lines: Array<OcrResult>, source: Bitmap): List<QuestionRegion> =
        detectFromLines(lines)

    internal fun detectFromLines(lines: Array<OcrResult>): List<QuestionRegion> {
        if (lines.isEmpty()) return emptyList()

        val sorted = lines
            .map { Line(it, it.toAlignedRect()) }
            .sortedBy { it.rect.top }

        val avgLineH = sorted.map { it.rect.height() }.average().toFloat()
        if (avgLineH <= 0f) return emptyList()

        val gapThreshold = gapMultiplier * avgLineH

        // Pattern C pre-pass: conditional-preamble line immediately followed by an imperative line.
        val patternCStarts     = mutableSetOf<Int>()
        val patternCSuppressed = mutableSetOf<Int>()
        for (i in 0 until sorted.size - 1) {
            val curr = sorted[i]
            val next = sorted[i + 1]
            if (curr.result.score > handwritingScoreThreshold &&
                next.result.score > handwritingScoreThreshold) {
                val currText = curr.result.text.trim()
                val nextText = next.result.text.trim()
                if (CONDITIONAL_START.containsMatchIn(currText) &&
                    !IMPERATIVE.containsMatchIn(currText) &&
                    IMPERATIVE.containsMatchIn(nextText)) {
                    patternCStarts     += i
                    patternCSuppressed += i + 1
                }
            }
        }

        return sorted
            .mapIndexed { i, line ->
                val gap = if (i == 0) Float.MAX_VALUE
                          else line.rect.top - sorted[i - 1].rect.bottom
                val isQuestionStart = when {
                    line.result.score <= handwritingScoreThreshold -> gap > gapThreshold
                    i in patternCStarts     -> true
                    i in patternCSuppressed -> false
                    else -> isQuestionByText(line.result.text, language)
                }
                Log.v(TAG, "isQuestionStart: $isQuestionStart Line: ${line.result.text} score: ${line.result.score} height: ${line.rect.height()}")
                MarkedLine(line.result, line.rect, isQuestionStart)
            }
            .groupIntoRegions()
    }

    private fun isQuestionByText(text: String, language: String = ""): Boolean {
        val t = text.trim()
        Log.d(TAG, "isQuestionByText: $t")
        return when (language.lowercase()) {
            "ja" -> isJapaneseQuestion(t)
            "en" -> isEnglishQuestion(t)
            else -> isEnglishQuestion(t)/* || isJapaneseQuestion(t)*/
        }
    }

    private fun isEnglishQuestion(t: String): Boolean =
        NUMBERED.containsMatchIn(t)
            || LETTERED.containsMatchIn(t)
            || KEYWORD.containsMatchIn(t)
            || INTERROGATIVE.containsMatchIn(t)
            || INTERROGATIVE_PHRASE.containsMatchIn(t)
            || IMPERATIVE.containsMatchIn(t)
            || CONDITIONAL.containsMatchIn(t)
            || t.endsWith('?')

    private fun isJapaneseQuestion(t: String): Boolean =
        JA_NUMBERED.containsMatchIn(t)
            || JA_KEYWORD.containsMatchIn(t)
            || JA_INTERROGATIVE.containsMatchIn(t)
            || JA_QUESTION_ENDING.containsMatchIn(t)
            || JA_IMPERATIVE.containsMatchIn(t)
            || JA_CONDITIONAL.containsMatchIn(t)
            || t.endsWith('？')

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
        val bounds = RectF(
            boxes.minOf { it.left },
            boxes.minOf { it.top },
            boxes.maxOf { it.right },
            boxes.maxOf { it.bottom }
        )
        return QuestionRegion(
            index = index,
            bounds = bounds,
            confidence = map { it.result.score }.average().toFloat(),
            lineBoxes = boxes
        )
    }

    private data class Line(val result: OcrResult, val rect: RectF)
    private data class MarkedLine(val result: OcrResult, val rect: RectF, val isQuestionStart: Boolean)

    companion object {
        private val NUMBERED = Regex("""^\d{1,3}[.)]\s+""")
        private val LETTERED = Regex("""^[A-Za-z][.)]\s+""")
        private val KEYWORD              = Regex("""(?i)^question\b""")
        private val INTERROGATIVE       = Regex("""(?i)^(What|Which)\b""")
        private val INTERROGATIVE_PHRASE = Regex("""(?i)^How\s+(many|much)\b""")

        // Shared verb list used by both Pattern A and Pattern B.
        private val VERBS =
            """Calculate|Compute|Evaluate|Simplify|Estimate|Approximate|""" +
            """Solve|Find|Determine|Derive|Expand|Factorise|Factorize|Differentiate|Integrate|Express|Convert|""" +
            """Prove|Show|Verify|Justify|Sketch|Plot|Draw|Graph|Construct|Label|""" +
            """Explain|Describe|Discuss|Analyse|Analyze|Investigate|Predict|Compare|Contrast|""" +
            """Define|State|Identify|List|Name"""

        // Pattern A: imperative verb starts the line — "Find the roots", "Prove that..."
        private val IMPERATIVE  = Regex("""(?i)^($VERBS)\b""")

        // Pattern B: conditional prefix + mid-line verb — "Given x=5, find y"
        private val CONDITIONAL = Regex("""(?i)^(Given|If|For|When|Assuming|Let|Suppose)\b.+\b($VERBS)\b""")

        // Pattern C pre-pass: conditional prefix at line start with no verb on the same line.
        private val CONDITIONAL_START = Regex("""(?i)^(Given|If|For|When|Assuming|Let|Suppose)\b""")

        // ── Japanese rule set ─────────────────────────────────────────────────

        // JA_NUMBERED: 1. 1) 1） （1） ①②③…⑳
        private val JA_NUMBERED        = Regex("""^(?:[（(]\d{1,3}[)）]|[①-⑳]|\d{1,3}[.)）])\s*""")
        private val JA_KEYWORD         = Regex("""^(問題|設問|問)\s*\d*""")
        private val JA_INTERROGATIVE   = Regex("""^(何|どれ|どの|どう|どのよう|なぜ|どうして|いつ|どこ|だれ|誰|いくつ|いくら)""")

        // Ends with a question-particle construction — covers ですか、ますか、でしょうか、だろうか、かな
        private val JA_QUESTION_ENDING = Regex("""(?:ですか|ますか|でしょうか|だろうか|かな)[。？]?${'$'}""")

        // Complete imperative forms shared by JA Pattern A and JA Pattern B.
        // Covers suru verbs (計算せよ/しなさい), ichidan verbs (求めよ/なさい), godan verbs (解け/解きなさい).
        private val JA_VERBS =
            """求めよ|求めなさい|""" +
            """計算せよ|計算しなさい|証明せよ|証明しなさい|微分せよ|微分しなさい|積分せよ|積分しなさい|""" +
            """展開せよ|展開しなさい|因数分解せよ|因数分解しなさい|簡略化せよ|簡略化しなさい|""" +
            """近似せよ|近似しなさい|評価せよ|評価しなさい|説明せよ|説明しなさい|""" +
            """比較せよ|比較しなさい|対比せよ|対比しなさい|定義せよ|定義しなさい|""" +
            """変換せよ|変換しなさい|予測せよ|予測しなさい|""" +
            """述べよ|述べなさい|調べよ|調べなさい|確かめよ|確かめなさい|""" +
            """解け|解きなさい|描け|描きなさい|示せ|示しなさい"""

        // JA Pattern A: imperative form at line end — 求めよ、計算しなさい (Japanese SOV: verb last)
        private val JA_IMPERATIVE      = Regex("""(?:$JA_VERBS)[。]?${'$'}""")

        // JA Pattern B: conditional clause + imperative at end — x=5のとき、yを求めよ
        private val JA_CONDITIONAL     = Regex("""(?:のとき|ならば?|とすれば|において|の場合|を仮定して|とする).+?(?:$JA_VERBS)[。]?${'$'}""")

        // JA Pattern C pre-pass: conditional preamble at line start with no verb on same line.
        private val JA_CONDITIONAL_START = Regex("""^(?:次の|以下の|与えられた|もし)""")
    }
}

private fun OcrResult.toAlignedRect(): RectF {
    val xs = (0..3).map { box[it][0].toFloat() }
    val ys = (0..3).map { box[it][1].toFloat() }
    return RectF(xs.min(), ys.min(), xs.max(), ys.max())
}
