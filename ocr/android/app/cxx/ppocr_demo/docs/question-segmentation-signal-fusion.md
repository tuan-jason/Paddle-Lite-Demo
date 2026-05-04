# Question Segmentation — Adaptive Signal Fusion

## Problem

Given a set of OCR lines (text + bounding box + confidence score) from an exam paper image,
group lines that belong to the same question into a single rectangular region.

Exam papers vary widely in format: some consistently number every question, others use only
whitespace, imperative text, or sub-question letters as structural cues. No single signal is
reliably present across all formats, so a weighted, multi-signal approach is required.

See `algorithm-design.md §Phase 2` for the current rule-based baseline this approach supersedes.

---

## Implementation Note

The algorithm described here **must be implemented in a new, dedicated detector class** —
not inside `GeometricQuestionDetector`. The existing class is left untouched so that the
current baseline remains available and testable.

The new class must implement the `QuestionDetector` interface, making it a **drop-in replacement**
at the call site in `MainActivity.java` (line 384):

```java
// Before
List<QuestionRegion> regions = new GeometricQuestionDetector().detect(results, bitmap);

// After — only this line changes
List<QuestionRegion> regions = new SignalFusionQuestionDetector().detect(results, bitmap);
```

No other code in `MainActivity`, `QuestionDetector`, `OcrResult`, or `QuestionRegion` changes.

---

## Approach: Document-Adaptive Calibration + Tiered Signal Fusion

The algorithm runs in two sequential passes over the sorted (top-to-bottom) line list.

### Pass 1 — Document Calibration

Derive per-document thresholds before any question-start decisions are made.

| Output | How derived |
|---|---|
| `medianLineHeight` | Median of all `line.rect.height()` values |
| `modalLeftMargin` | Most frequent `line.rect.left` value (binned to nearest 5 px) |
| `gapLarge` | 1.5 × `medianLineHeight` |
| `gapVeryLarge` | 3.0 × `medianLineHeight` |
| `anchorProfile` | x-range and regex style of the first 3+ confidently detected numbered markers, if any |

**Calibration failure fallback**: if fewer than 2 lines are available, or `medianLineHeight ≤ 0`,
fall back to the gap-only geometric path from the existing `GeometricQuestionDetector`.

---

### Pass 2 — Tiered Signal Scoring

For each line (in top-to-bottom order), compute a `questionStartScore` from all available signals.
Signals are evaluated in three tiers; a sufficiently strong tier fires immediately without
consulting lower tiers.

#### Signal inventory

**Geometric signals**

| Signal | Direction | Weight |
|---|---|---|
| Gap above > `gapVeryLarge` (3× median height) | + | 8 |
| Gap above in range `gapLarge`–`gapVeryLarge` | + | 5 |
| Gap above < 0.8× median height | − | 3 |
| Left edge resets to `modalLeftMargin` (± tolerance) | + | 4 |
| Left edge indented beyond `modalLeftMargin` | − | 3 |

**Structural signals**

| Signal | Direction | Weight |
|---|---|---|
| Numbered marker at line start: `1.` `2)` `Q3` `問題1` | + | 9 |
| Horizontal rule detected above line (wide, thin bounding box) | + | 10 |
| Sub-question letter at line start: `a.` `b)` `(i)` `(ii)` | **−** | 6 |
| Marks/score tag at end of previous line: `[3 marks]` `(5点)` | + | 3 |
| Line is in top/bottom 5% of page height (header/footer zone) | − | 8 |

**Semantic signals**

| Signal | Direction | Weight |
|---|---|---|
| Question keyword at start: `Question` `問題` `設問` | + | 8 |
| Imperative verb at start: `Find` `Prove` `Calculate` `求めよ` | + | 4 |
| Interrogative word at start: `What` `Why` `なぜ` `How many` | + | 4 |
| Conditional + imperative pattern: `Given…, find…` | + | 4 |
| Trailing `?` or `？` | + | 3 |
| Line text is very short (< 3 printable characters) | − | 2 |

#### Tier thresholds

```
Tier 1  score ≥ 9   →  immediate question start (no further tiers consulted)
Tier 2  score 5–8   →  question start if at least 1 Tier-2 signal present
Tier 3  score 2–4   →  question start if at least 2 Tier-3 signals agree
```

Negative signals reduce the score at every tier and can veto a Tier-2 or Tier-3 decision.
They cannot veto a Tier-1 decision (a numbered marker or horizontal rule is treated as
authoritative).

#### Anchor profile boost

If the calibration pass produced an `anchorProfile` (consistent numbered markers were found),
numbered markers whose style matches the profile receive a +2 bonus; those that do not match
(e.g. a formula `f(x) = 1.5`) receive a −3 penalty. This suppresses OCR-misread false positives.

---

## Grouping

After scoring, lines are grouped identically to the existing approach: consecutive lines are
accumulated into the current question until a line with `isQuestionStart = true` is encountered,
at which point the current group is closed and a new one begins.
The bounding region is the union rectangle of all line boxes in the group.

### `QuestionRegion.confidence` is not a detection confidence score

`QuestionRegion.confidence` is the **mean OCR recognition score** of the lines in the group
(average of `OcrResult.score`). It measures OCR text quality, not how confidently the detector
decided those lines form a question. The tier-scoring decision is binary and is not stored in
the region; every `QuestionRegion` that exists was already judged to be a valid question start.

Do not use this field as a threshold for accepting or rejecting detected regions. If you need
to filter by OCR quality (e.g. to avoid sending garbled text downstream), use the following
bands as a guide:

| `confidence` band | Interpretation | Suggested action |
|---|---|---|
| ≥ 0.85 | Matches `HIGH_CONF_THRESHOLD` — semantic signals were evaluated with reliable text | Accept |
| 0.75–0.84 | OCR readable; structural/geometric signals sound; some semantic signals may have been skipped | Accept with caution |
| 0.60–0.74 | Text partially garbled; detection relied on geometry only | Flag for review |
| < 0.60 | OCR quality too low to trust text-based boundaries | Discard |

`HIGH_CONF_THRESHOLD = 0.85` is defined in `SignalFusionQuestionDetector` and is the cut-off
used internally when evaluating semantic signals during calibration.

---

## Key Design Decisions

**Sub-question letters are negative signals.**
`a.` `b)` `(i)` indicate a part of the current question, not a new one.
The current codebase's `LETTERED` regex treats them as positive — this is a known source of
false splits that this approach corrects.

**Calibration is per-document, not global.**
A dense A4 Physics paper and a large-font primary-school paper produce different median line
heights and left margins. Hard-coded geometric constants fail across these; calibrated values
adapt automatically.

**Negative signals veto weak positives but not strong ones.**
A sub-question letter overrides an imperative verb (Tier 3). It does not override a numbered
question marker (Tier 1). This prevents `a. Find the area.` from being split while still
correctly splitting `1. Find the area.`.

**The first line is always a question start if it scores ≥ Tier 3.**
With no prior group, there is no "continuation" to attach to, so the threshold is relaxed for
the very first scored line.

---

## Known Limitations

| Limitation | Notes |
|---|---|
| Multi-column layouts | Geometric gap comparison across columns is meaningless; column detection must precede this pass |
| Images/diagrams within questions | Diagram boxes have no text; they are currently transparent to the scorer and may cause incorrect gap signals |
| Calibration poisoning | If the first few lines are headers/instructions with numbered items, `anchorProfile` may be mis-calibrated |
| MCQ option lines | Lines `A.` `B.` `C.` `D.` are structurally identical to lettered sub-questions; context (proximity, indentation) is needed to distinguish |

---

## Relationship to Existing Code

| Component | Status |
|---|---|
| `GeometricQuestionDetector` | Existing baseline — untouched, remains available |
| `SignalFusionQuestionDetector` | New class to be created; implements `QuestionDetector` |
| `QuestionDetector` interface | Unchanged — the new class satisfies the same contract |
| `OcrResult` / `QuestionRegion` | Unchanged input/output types |
| `MainActivity.java` line 384 | Only line that changes — swap class name, nothing else |
| Language tag (`language` field) | Semantic signals remain language-dispatched as in `GeometricQuestionDetector` |
