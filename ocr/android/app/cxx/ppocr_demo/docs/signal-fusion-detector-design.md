# SignalFusionQuestionDetector — Implementation Design

Phase 2 v2 detector. Approach rationale and signal inventory are in
`question-segmentation-signal-fusion.md`. This document specifies the data
types, algorithm pseudocode, and class structure needed to implement that
approach.

---

## Data Types

### CalibrationStatus
```
enum class CalibrationStatus {
    FULL,           // all outputs available: geometry + anchorProfile + semantics
    PARTIAL,        // geometry available, anchorProfile absent
    GEOMETRY_ONLY,  // geometry available, OCR scores too low for semantic signals
    MINIMAL         // geometry unavailable; hardcoded safe defaults used
}
```

### NumberedMarkerStyle
```
enum class NumberedMarkerStyle {
    NUMERIC_DOT,             // 1.   2.   3.
    NUMERIC_PAREN,           // 1)   2)   3)
    NUMERIC_ENCLOSED_PAREN,  // (1)  (2)  (3)   — half-width ASCII parens both sides
    NUMERIC_FULLWIDTH_PAREN, // 1）  2）  （1）  （2） — full-width parens
    CIRCLED_NUMBER,          // ①   ②   ③  …  ⑳
    Q_PREFIX,                // Q1   Q2   Q3   Q.1   Q.2
    JP_MONDAI,               // 問題1  問題2
    JP_MON,                  // 問1   問2
    JP_SETSUMON,             // 設問1  設問2
    DAI_MON                  // 第1問  第2問  (university entrance format)
}
```

`NUMERIC_ENCLOSED_PAREN` is distinct from `NUMERIC_FULLWIDTH_PAREN`: a document
uses one convention consistently, so they must not be conflated in the anchor
profile. `DAI_MON` (`第N問`) is the standard format for Japanese university
entrance exams and differs structurally — the ordinal prefix precedes the number
and `問` follows it. `NUMERIC_COLON` (`1:`) and `ROMAN_NUMERAL` (`I.`) are
intentionally excluded: both carry unacceptably high false-positive rates.

### AnchorProfile
```
data class AnchorProfile(
    val leftMarginRange : ClosedFloatingPointRange<Float>,  // x-range of confirmed markers
    val style           : NumberedMarkerStyle,              // numbering convention in this doc
    val observedNumbers : List<Int>,                        // sorted integer sequence seen: [1,2,3]
    val confidence      : Float                             // 0.0–1.0; saturates at 5 anchors
)
```

`observedNumbers` is used to reject OCR noise: a candidate matching `1.5` or
`2a` is disqualified because it does not extend the integer sequence.

`confidence` attenuates the anchor-match bonus/penalty when the profile was
built from only 1–2 anchors.

### QuestionRegion.confidence — OCR quality proxy, not detection confidence

`QuestionRegion.confidence` is the mean `OcrResult.score` across all lines in the group. It
reflects OCR text quality, **not** how confidently the detector identified those lines as a
question. The tier-scoring decision is binary; no signal score is stored on the region.

Do not threshold on this field to decide whether a region "really" is a question. For filtering
by OCR quality use these bands:

| `confidence` band | Interpretation | Suggested action |
|---|---|---|
| ≥ 0.85 | Matches `HIGH_CONF_THRESHOLD` — semantic signals were evaluated with reliable text | Accept |
| 0.75–0.84 | OCR readable; structural/geometric signals sound; some semantic signals may have been skipped | Accept with caution |
| 0.60–0.74 | Text partially garbled; detection relied on geometry only | Flag for review |
| < 0.60 | OCR quality too low to trust text-based boundaries | Discard |

---

### CalibrationData
```
data class CalibrationData(
    val status          : CalibrationStatus,
    val medianLineHeight: Float,
    val modalLeftMargin : Float,
    val gapLarge        : Float,   // 1.5 × medianLineHeight
    val gapVeryLarge    : Float,   // 3.0 × medianLineHeight
    val anchorProfile   : AnchorProfile?,
    val imageHeight     : Float
)
```

### Signal
```
class Signal(
    val name    : String,
    val score   : Int,             // positive = supports question start
                                   // negative = suppresses question start
    val evaluate: (LineContext, CalibrationData) -> Boolean
)
```

`score` encodes both direction and magnitude. No separate `Direction` field.
The list of active signals is built once per document (after calibration) and
reused for every line.

### LineContext
```
data class LineContext(
    val line    : OcrResult,
    val rect    : RectF,
    val index   : Int,
    val gapAbove: Float,    // rect.top − prevRect.bottom; Float.MAX_VALUE for first line
    val prevLine: OcrResult?
)
```

`prevLine` is required for the marks-tag signal, which checks whether the
*preceding* line ends with a score annotation (`[3 marks]`, `(5点)`).

---

## Constants

These are the primary tuning parameters. Collect them in a companion object.

```
TIER1_THRESHOLD        = 9      // minimum individual signal score for Tier-1 fire
TIER2_THRESHOLD        = 5      // net score + individual score for Tier-2 fire
TIER3_THRESHOLD        = 2      // net score + count ≥ 2 for Tier-3 fire
TIER3_MIN_COUNT        = 2      // minimum number of Tier-3 positive signals required

HIGH_CONF_THRESHOLD    = 0.85f  // minimum OCR score for semantic signal evaluation
MARGIN_TOLERANCE       = 10f    // px: left-edge tolerance for modalLeftMargin match
ANCHOR_MIN_COUNT       = 3      // minimum confirmed anchors to build AnchorProfile
ANCHOR_CONFIDENCE_FULL = 5      // anchor count at which confidence saturates to 1.0
HEADER_FOOTER_FRACTION = 0.05f  // top/bottom fraction of page treated as header/footer zone

// Fallback values used when CalibrationStatus = MINIMAL
FALLBACK_GAP_DIVISOR_LARGE      = 40   // gapLarge     = imageHeight / 40
FALLBACK_GAP_DIVISOR_VERY_LARGE = 20   // gapVeryLarge = imageHeight / 20
```

---

## Pass 1 — Calibration

```
fun calibrate(sorted: List<LineWithRect>, imageHeight: Float): CalibrationData

    // ── Guard ────────────────────────────────────────────────────────────────
    if sorted.size < 2:
        return minimalFallback(imageHeight)

    // ── Step 1: median line height ───────────────────────────────────────────
    medianLineHeight = median(sorted.map { it.rect.height() })
    if medianLineHeight <= 0:
        return minimalFallback(imageHeight)

    gapLarge     = 1.5f * medianLineHeight
    gapVeryLarge = 3.0f * medianLineHeight

    // ── Step 2: modal left margin ────────────────────────────────────────────
    modalLeftMargin = mode(sorted.map { it.rect.left }, binSize = 5f)

    // ── Step 3: anchor profile ───────────────────────────────────────────────
    highConfLines = sorted.filter { it.result.score >= HIGH_CONF_THRESHOLD }

    if highConfLines.isEmpty():
        // OCR quality too low for semantic or anchor signals
        return CalibrationData(GEOMETRY_ONLY, medianLineHeight, modalLeftMargin,
                               gapLarge, gapVeryLarge, anchorProfile = null, imageHeight)

    candidates = highConfLines.filter { ANY_NUMBERED_PATTERN matches it.result.text.trim() }

    if candidates.size < ANCHOR_MIN_COUNT:
        return CalibrationData(PARTIAL, medianLineHeight, modalLeftMargin,
                               gapLarge, gapVeryLarge, anchorProfile = null, imageHeight)

    // ── Step 4: build AnchorProfile ──────────────────────────────────────────
    style           = detectStyle(candidates.first().result.text)
    observedNumbers = candidates.mapNotNull { extractInteger(it.result.text) }.sorted().distinct()
    leftMarginRange = candidates.minOf { it.rect.left } .. candidates.maxOf { it.rect.left }
    confidence      = min(1.0f, candidates.size.toFloat() / ANCHOR_CONFIDENCE_FULL)

    anchorProfile = AnchorProfile(leftMarginRange, style, observedNumbers, confidence)

    return CalibrationData(FULL, medianLineHeight, modalLeftMargin,
                           gapLarge, gapVeryLarge, anchorProfile, imageHeight)


fun minimalFallback(imageHeight: Float): CalibrationData =
    CalibrationData(
        status          = MINIMAL,
        medianLineHeight = imageHeight / FALLBACK_GAP_DIVISOR_LARGE,
        modalLeftMargin  = 0f,
        gapLarge         = imageHeight / FALLBACK_GAP_DIVISOR_LARGE,
        gapVeryLarge     = imageHeight / FALLBACK_GAP_DIVISOR_VERY_LARGE,
        anchorProfile    = null,
        imageHeight      = imageHeight
    )
```

---

## Pass 2 — Scoring

### Signal list construction

Build once after calibration; reuse for all lines.

```
fun buildSignals(calibration: CalibrationData): List<Signal>

    signals = mutableListOf<Signal>()

    // ── Geometric (skipped only for MINIMAL, but safe defaults cover it) ─────
    signals += Signal("gap_very_large",  score =  8) { ctx, cal -> ctx.gapAbove > cal.gapVeryLarge }
    signals += Signal("gap_large",       score =  5) { ctx, cal -> ctx.gapAbove in cal.gapLarge..cal.gapVeryLarge }
    signals += Signal("gap_small",       score = -3) { ctx, cal -> ctx.gapAbove < cal.medianLineHeight * 0.8f
                                                                     && ctx.gapAbove != Float.MAX_VALUE }
    signals += Signal("margin_reset",    score =  4) { ctx, cal -> abs(ctx.rect.left - cal.modalLeftMargin) <= MARGIN_TOLERANCE }
    signals += Signal("margin_indented", score = -3) { ctx, cal -> ctx.rect.left > cal.modalLeftMargin + MARGIN_TOLERANCE }

    // ── Structural ────────────────────────────────────────────────────────────
    signals += Signal("numbered_marker",    score =  9) { ctx, _ -> NUMBERED_PATTERN matches ctx.line.text.trim() }
    signals += Signal("horizontal_rule",    score = 10) { ctx, cal -> isHorizontalRule(ctx.rect, cal.imageHeight) }
    signals += Signal("header_footer_zone", score = -8) { ctx, cal -> ctx.rect.top  < cal.imageHeight * HEADER_FOOTER_FRACTION
                                                                       || ctx.rect.bottom > cal.imageHeight * (1f - HEADER_FOOTER_FRACTION) }
    signals += Signal("marks_tag_prev",     score =  3) { ctx, _ -> ctx.prevLine != null
                                                                      && MARKS_TAG_PATTERN matches ctx.prevLine.text.trim() }

    // ── Anchor profile boost/penalty (FULL only) ──────────────────────────────
    if calibration.status == FULL && calibration.anchorProfile != null:
        val boost   = max(1, (2f * calibration.anchorProfile.confidence).roundToInt())
        val penalty = -3
        signals += Signal("anchor_match",    score = boost)   { ctx, cal -> matchesAnchorProfile(ctx, cal.anchorProfile!!) }
        signals += Signal("anchor_mismatch", score = penalty)  { ctx, cal -> looksLikeNumberedButMismatch(ctx, cal.anchorProfile!!) }

    // ── Semantic (skipped for GEOMETRY_ONLY) ──────────────────────────────────
    if calibration.status != GEOMETRY_ONLY:
        signals += Signal("question_keyword",         score =  8) { ctx, _ -> KEYWORD_PATTERN      matches ctx.line.text.trim() }
        signals += Signal("imperative_verb",          score =  4) { ctx, _ -> IMPERATIVE_PATTERN   matches ctx.line.text.trim() }
        signals += Signal("interrogative_word",       score =  4) { ctx, _ -> INTERROGATIVE_PATTERN matches ctx.line.text.trim() }
        signals += Signal("conditional_imperative",   score =  4) { ctx, _ -> CONDITIONAL_PATTERN  matches ctx.line.text.trim() }
        signals += Signal("trailing_question_mark",   score =  3) { ctx, _ -> ctx.line.text.trim().endsWith('?')
                                                                               || ctx.line.text.trim().endsWith('？') }
        signals += Signal("very_short_line",          score = -2) { ctx, _ -> ctx.line.text.trim().length < 3 }

    return signals
```

### Per-line scoring

```
fun isQuestionStart(
    ctx       : LineContext,
    signals   : List<Signal>,
    calibration: CalibrationData,
    isFirst   : Boolean
): Boolean

    val fired         = signals.filter { it.evaluate(ctx, calibration) }
    val netScore      = fired.sumOf { it.score }
    val maxIndividual = fired.maxOfOrNull { it.score } ?: 0
    val positives     = fired.filter { it.score > 0 }

    // Sub-question marker hard veto (Option B decision)
    val hasSubQuestion = SUB_QUESTION_PATTERN matches ctx.line.text.trim()

    return when {
        // Tier 1: one authoritative signal — cannot be vetoed
        maxIndividual >= TIER1_THRESHOLD -> true

        // Hard contextual veto applies to Tier 2 and below
        hasSubQuestion -> false

        // Tier 2: net score qualifies AND at least one medium-weight positive fired
        // (prevents many Tier-3 signals from inflating past this threshold)
        netScore >= TIER2_THRESHOLD
            && positives.any { it.score >= TIER2_THRESHOLD } -> true

        // Tier 3: net score qualifies AND at least two independent weak positives agree
        netScore >= TIER3_THRESHOLD
            && positives.count { it.score >= TIER3_THRESHOLD } >= TIER3_MIN_COUNT -> true

        // First line relaxation: no prior group to attach to
        isFirst && netScore >= TIER3_THRESHOLD -> true

        else -> false
    }
```

---

## Full Algorithm Flow

```
fun detectFromLines(lines: Array<OcrResult>, imageHeight: Float): List<QuestionRegion>

    if lines.isEmpty(): return emptyList()

    // Sort top-to-bottom
    val sorted = lines.map { LineWithRect(it, it.toAlignedRect()) }.sortedBy { it.rect.top }

    // Pass 1
    val calibration = calibrate(sorted, imageHeight)

    // Build signal list once
    val signals = buildSignals(calibration)

    // Pass 2
    val marked = sorted.mapIndexed { i, line ->
        val ctx = LineContext(
            line     = line.result,
            rect     = line.rect,
            index    = i,
            gapAbove = if (i == 0) Float.MAX_VALUE else line.rect.top - sorted[i-1].rect.bottom,
            prevLine = if (i == 0) null else sorted[i-1].result
        )
        val start = isQuestionStart(ctx, signals, calibration, isFirst = (i == 0))
        MarkedLine(line.result, line.rect, isQuestionStart = start)
    }

    return marked.groupIntoRegions()
```

`groupIntoRegions()` is identical to the existing implementation in
`GeometricQuestionDetector` — accumulate lines into the current group until
`isQuestionStart = true`, then close and open a new group.

---

## Horizontal Rule Detection

A horizontal rule in OCR output appears as a very wide, very thin bounding box
with minimal or empty text content.

```
fun isHorizontalRule(rect: RectF, imageHeight: Float): Boolean =
    rect.width() > imageHeight * 0.4f   // spans at least 40% of typical page width
    && rect.height() < 5f               // very thin
```

These thresholds are intentionally generous; false positives here are unlikely
because wide-and-thin boxes rarely appear in normal text.

---

## Class and File Structure

```
SignalFusionQuestionDetector.kt
├── class SignalFusionQuestionDetector @JvmOverloads constructor(
│       language: String = ""
│   ) : QuestionDetector
│
├── override fun detect(lines, source): List<QuestionRegion>
│       → detectFromLines(lines, source.height.toFloat())
│
├── internal fun detectFromLines(lines, imageHeight): List<QuestionRegion>
│       → calibrate() → buildSignals() → score each line → groupIntoRegions()
│
├── private fun calibrate(sorted, imageHeight): CalibrationData
├── private fun buildSignals(calibration): List<Signal>
├── private fun isQuestionStart(ctx, signals, calibration, isFirst): Boolean
├── private fun isHorizontalRule(rect, imageHeight): Boolean
│
├── private data class LineWithRect(result: OcrResult, rect: RectF)
├── private data class MarkedLine(result: OcrResult, rect: RectF, isQuestionStart: Boolean)
│
└── companion object
        CalibrationStatus, NumberedMarkerStyle  ← enums
        AnchorProfile, CalibrationData          ← data classes
        Signal, LineContext                     ← types
        ALL constants (TIER1_THRESHOLD, etc.)
        ALL regex patterns (NUMBERED_PATTERN, SUB_QUESTION_PATTERN, etc.)
```

Regex patterns are reused from `GeometricQuestionDetector` where they already
exist (`NUMBERED`, `LETTERED`, `IMPERATIVE`, `CONDITIONAL`, `CONDITIONAL_START`,
`KEYWORD`, `INTERROGATIVE`, `INTERROGATIVE_PHRASE`, `JA_*`). Reference or copy
them into the companion; do not import across private companion objects.

---

## Implementation Details

### ANY_NUMBERED_PATTERN

Union of all 10 `NumberedMarkerStyle` regexes, anchored at `^`. Used in the
calibration pass to identify candidate anchor lines, and in
`looksLikeNumberedButMismatch` to gate the penalty signal.

| Style | Regex |
|---|---|
| `NUMERIC_DOT` | `^\d{1,3}\.\s+` |
| `NUMERIC_PAREN` | `^\d{1,3}\)\s*` |
| `NUMERIC_ENCLOSED_PAREN` | `^\(\d{1,3}\)\s*` |
| `NUMERIC_FULLWIDTH_PAREN` | `^(?:[（(]\d{1,3}[)）]\|\d{1,3}[）])\s*` |
| `CIRCLED_NUMBER` | `^[①-⑳]\s*` |
| `Q_PREFIX` | `^Q\.?\d{1,3}\b` (case-insensitive) |
| `JP_MONDAI` | `^問題\d{1,3}` |
| `JP_MON` | `^問\d{1,3}` |
| `JP_SETSUMON` | `^設問\d{1,3}` |
| `DAI_MON` | `^第\d{1,3}問` |

The `\d{1,3}` bound is applied in every pattern — it both limits match length
and rejects decimals like `1.5` (which would require a digit after the period,
not a space or end-of-token).

---

### extractInteger

Style-specific extraction. Called only on confirmed anchor candidates during
calibration, so the style is already known.

| Style | Extraction rule |
|---|---|
| `NUMERIC_DOT` / `NUMERIC_PAREN` / `NUMERIC_ENCLOSED_PAREN` | First capture group of the style regex → `.toInt()` |
| `NUMERIC_FULLWIDTH_PAREN` | Strip leading `（` or `(`, then first digit run → `.toInt()` |
| `CIRCLED_NUMBER` | `text[0].code - '①'.code + 1` |
| `Q_PREFIX` | Digit run after optional `Q.` or `Q` |
| `JP_MONDAI` | Digit run immediately after `問題` |
| `JP_MON` | Digit run immediately after `問` |
| `JP_SETSUMON` | Digit run immediately after `設問` |
| `DAI_MON` | Digit run between `第` and `問` |

**Post-extraction validation** (applied to every result):
- Parse failure → return `null`; candidate is excluded from `observedNumbers`
  but its `rect.left` still contributes to `leftMarginRange`
- Result is `0` or `> 200` → reject as implausible

---

### matchesAnchorProfile

Three independent criteria; **all three must pass**.

**Criterion 1 — Style pattern match**
The candidate text must match the regex for `profile.style` specifically, not
just `ANY_NUMBERED_PATTERN`. Different styles are not interchangeable within a
profile.

**Criterion 2 — X-position match**
```
candidate.rect.left in
    (profile.leftMarginRange.start - MARGIN_TOLERANCE)
    ..(profile.leftMarginRange.endInclusive + MARGIN_TOLERANCE)
```
Rejects same-style occurrences at a different indent level (e.g. `①` as a
question start at x=50 vs. `①` as a list item inside a question at x=120).

**Criterion 3 — Sequence continuity**
Extract the integer from the candidate. It must satisfy:
- Not already in `profile.observedNumbers` — duplicates indicate repeated
  sub-items or OCR re-reads of the same line
- Greater than `max(observedNumbers)` — no backwards jump
- ≤ `max(observedNumbers) + 2` — no implausibly large forward jump; `+2` gives
  one step of tolerance for an OCR-missed question

`AnchorProfile.confidence` does **not** affect the pass/fail decision — it only
scales the bonus `score` in `buildSignals`.

---

### looksLikeNumberedButMismatch

```
fun looksLikeNumberedButMismatch(ctx: LineContext, profile: AnchorProfile): Boolean =
    ANY_NUMBERED_PATTERN.containsMatchIn(ctx.line.text.trim())
        && !matchesAnchorProfile(ctx, profile)
```

The two-clause structure is essential:
- **Clause 1** gates the penalty — only lines that structurally resemble a
  numbered marker are eligible. Normal text (`The answer is 42.`) is never
  penalised.
- **Clause 2** fires the penalty only when the resemblance is misleading —
  wrong style, wrong x-position, or sequence discontinuity.

Representative cases:

| Candidate text | Clause 1 | Clause 2 result | Outcome |
|---|---|---|---|
| `(1)` at x=130, profile x-range is 45–55 | matches `NUMERIC_ENCLOSED_PAREN` | fails x-position | penalty fires |
| `第3問` duplicate after already seen | matches `DAI_MON` | fails sequence (already in `observedNumbers`) | penalty fires |
| `f(1.5)` | `^\(\d{1,3}\)` requires no decimal inside → no match | — | neither signal fires |
| ordinary sentence | no style regex matches | — | neither signal fires |

---

## Open Questions

| Question | Notes |
|---|---|
| Multi-column handling | Column detection must run as a pre-pass before this detector; not in scope here |
