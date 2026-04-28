# Algorithm Design

---

## Phase 1 — OCR Pipeline

**Implemented in:** `pipeline.cc` (`Pipeline::RunOcrOnBitmap`)

**Input:** Raw RGBA pixel buffer, width, height

**Output:** JSON array of `{ box, text, score }` per detected text line

### Steps

1. **Colour conversion**
   Android `Bitmap.ARGB_8888` bytes arrive as BGRA in little-endian native
   memory. Convert `cv::COLOR_BGRA2BGR` to produce a standard 3-channel BGR
   `cv::Mat`.

2. **Detection (`DetPredictor::Predict`)**
   Runs the DB (Differentiable Binarization) text-detection model.
   Internally resizes proportionally to `max_side_len = 960` (from
   `config.txt`), rounding to multiples of 32, to preserve aspect ratio.
   Returns `boxes`: a `vector<vector<vector<int>>>` of 4-point polygons
   (each point is `[x, y]`; order is TL → TR → BR → BL).

3. **Direction classification (`ClsPredictor::Predict`, optional)**
   When `use_direction_classify = 1` in config, rotates upside-down crops
   before recognition.

4. **Crop and warp (`GetRotateCropImage`)**
   For each detected box, extracts a perspective-corrected rectangular crop
   of the text line using `cv::getPerspectiveTransform` + `cv::warpPerspective`.

5. **Recognition (`RecPredictor::Predict`)**
   Runs the CRNN text-recognition model on each crop. Returns `(text, score)`
   where `score` is the mean CTC character confidence across the line.

6. **Serialization**
   Iterates boxes in reverse (matching the detection order) and emits a JSON
   array. Box coordinates are in the internally-resized image space.

7. **Optional visualization**
   If `savedImagePath` is non-empty, `Visualization()` draws green polygon
   outlines around each line box and writes a BGR JPEG to disk.

---

## Phase 2 v1 — GeometricQuestionDetector

**Implemented in:** `GeometricQuestionDetector.kt` (Kotlin, JVM)

**Input:** `Array<OcrResult>` (Phase 1 output), original `Bitmap`

**Output:** `Array<QuestionRegion>`

### Steps

#### Step 1 — Compute axis-aligned bounds per line

For each `OcrResult`, derive an axis-aligned `RectF` from its 4-point
polygon:

```
left   = min(box[0][0], box[1][0], box[2][0], box[3][0])
top    = min(box[0][1], box[1][1], box[2][1], box[3][1])
right  = max(box[0][0], box[1][0], box[2][0], box[3][0])
bottom = max(box[0][1], box[1][1], box[2][1], box[3][1])
```

Attach this `RectF` to the line as its working `bounds`.

#### Step 2 — Sort lines top-to-bottom

Sort the enriched line list by `bounds.top` ascending. This establishes
left-to-right, top-to-bottom reading order suitable for vertical gap
analysis.

#### Step 3 — Compute baseline metrics

```
avgLineH = mean(bounds.height())  over all lines
```

Used as the normalizing unit for gap thresholds. Computed after sort so
outlier detection (future) can operate on a stable set.

#### Step 4 — Cluster consecutive lines into blocks

```
gapThreshold = gapMultiplier × avgLineH   (default gapMultiplier = 1.5)
```

Iterate lines in sorted order. Start a new block whenever:

```
lines[i].bounds.top − lines[i−1].bounds.bottom > gapThreshold
```

Each block is a contiguous list of lines separated by gaps smaller than
the threshold. A new page section, blank line, or paragraph break between
questions typically produces a gap larger than 1.5 × avgLineH.

#### Step 5 — Detect question starts within each block

For each line, assign `isQuestionStart: Boolean`:

**High-confidence path** (`score > handwritingScoreThreshold`, default 0.80):
Apply the following regex patterns against `text` (trimmed). Any single match
→ `isQuestionStart = true`.

**Structural markers (language-independent):**

| Pattern | Matches | Example |
|---|---|---|
| `^\d{1,3}[.)]\s+` | Numbered | "1. ", "2) ", "10. " |
| `^[A-Za-z][.)]\s+` | Lettered | "a. ", "B) " |
| `(?i)^question\b` | Keyword prefix | "Question 3:", "question 1" |
| `\?\s*$` | Trailing question mark | "Why does this happen?" |
| `(?i)^(What|Which)\b` | Interrogative pronoun | "What is the value of x?", "Which of the following is correct?" |
| `(?i)^How\s+(many\|much)\b` | Interrogative quantifier | "How many solutions exist?", "How much work is done?" |

`Where` is intentionally excluded from the interrogative patterns: in mathematical text it frequently starts definition clauses (`"Where x ∈ ℝ, f(x) = ..."`, `"Where the coefficient is positive, ..."`) that are continuations, not question starts. The `^` anchor does not eliminate this false-positive risk.

`How` alone is intentionally excluded: at line start it commonly introduces instruction headers (`"How to calculate..."`) and section titles (`"How the algorithm works"`), which are not question starts. Only the quantifier forms `How many` and `How much` are matched; other `How` forms (`How does`, `How can`, `How long`, `How far`) carry higher false-positive risk and are deferred until real exam data confirms they are needed.

**Pattern A — Imperative verb at line start:**

```
(?i)^(IMPERATIVE_VERB)\b
```

Anchored to `^`. Matches lines where the question instruction begins the line
directly. The `\b` word boundary prevents partial matches (e.g. "Solver" or
"Findings" will not match).

| Group | Keywords |
|---|---|
| Computation | `Calculate`, `Compute`, `Evaluate`, `Simplify`, `Estimate`, `Approximate` |
| Algebra / Analysis | `Solve`, `Find`, `Determine`, `Derive`, `Expand`, `Factorise`, `Factorize`, `Differentiate`, `Integrate`, `Express`, `Convert` |
| Proof / Verification | `Prove`, `Show`, `Verify`, `Justify` |
| Visual / Construction | `Sketch`, `Plot`, `Draw`, `Graph`, `Construct`, `Label` |
| Reasoning | `Explain`, `Describe`, `Discuss`, `Analyse`, `Analyze`, `Investigate`, `Predict`, `Compare`, `Contrast` |
| Knowledge recall | `Define`, `State`, `Identify`, `List`, `Name` |

Keywords intentionally excluded (high mid-sentence occurrence rate):
`Write`, `Complete`, `Balance`, `Measure`.

Examples matched: `"Find the roots of f(x)"`, `"Prove that the sum is even"`,
`"Sketch the curve for x ∈ [0, 2π]"`.

**Pattern B — Conditional-prefix + mid-line imperative verb:**

```
(?i)^(CONDITIONAL_PREFIX)\b.*\b(IMPERATIVE_VERB)\b
```

Still anchored to `^` — but to a **conditional clause opener** rather than
the imperative verb itself. This targets the "Given X, find Y" sentence
structure common in math and science exams without opening up fully
unanchored matching (which would fire on narrative and explanatory text).

Conditional prefix keywords:

| Prefix | Typical usage |
|---|---|
| `Given` | "Given that f(x) = x², find f′(x)" |
| `If` | "If the radius is 7 cm, calculate the area" |
| `For` | "For x > 0, determine the sign of f′(x)" |
| `When` | "When t = 0, evaluate the velocity" |
| `Assuming` | "Assuming constant acceleration, show that v = u + at" |
| `Let` | "Let A be a 3×3 matrix. Find its determinant" |
| `Suppose` | "Suppose n is a positive integer. Prove that..." |

The imperative verb half of Pattern B shares the same keyword list as
Pattern A.

Examples matched: `"Given that x = 5, find the value of y"`,
`"If the mass is 2 kg, calculate the momentum"`,
`"Let P = (1, 2). Determine the distance to the origin"`.

**Pattern C — Cross-line conditional prefix → imperative verb (split across lines):**

Targets the common MCQ and structured-exam format where the conditional clause
and the imperative instruction appear on separate consecutive lines:

```
Line N:   "Given that the radius is 7 cm,"    ← CONDITIONAL_PREFIX, no verb
Line N+1: "calculate the area of the circle." ← IMPERATIVE_VERB at start
```

Without this pattern, Line N has no verb and `isStart = false`, so it is
either attached to the wrong question or discarded as preamble. Line N+1 fires
Pattern A and starts a new question — but with incomplete bounds (the
conditional preamble is missing).

**Detection rule:** In a one-step look-ahead pre-pass run before the main
classification loop, for each line `i` that matches `CONDITIONAL_PREFIX` at
`^` but contains no `IMPERATIVE_VERB` anywhere on the line:

```
if line[i+1] starts with IMPERATIVE_VERB (Pattern A match):
    mark line[i]   as isQuestionStart = true   (question starts at preamble)
    suppress Pattern A on line[i+1]             (becomes a continuation line)
```

This produces a single question region spanning both lines with correct bounds,
instead of discarding the preamble and starting the region one line too late.

The look-ahead is strictly one line; a conditional preamble separated from its
imperative by two or more lines is not matched (treat as preamble text).

**Why full unanchored detection is excluded:**

Removing `^` from either pattern would match imperative verbs in narrative
lines (`"We need to find the answer"`, `"The formula can be used to
calculate..."`) and preamble instructions (`"Students are asked to solve..."`),
causing heavy over-segmentation. Both Pattern A and Pattern B remain anchored
to `^`; the conditional-prefix anchor of Pattern B is the precise mechanism
that admits mid-line imperative verbs safely.

**False-positive note (Pattern A + B + C):** Multi-part sub-tasks that begin
with these verbs (e.g. `"   Find the area."` as a continuation of question 1)
will be detected as separate question starts, causing over-segmentation into
sub-task regions. For a selectable-region UI this is acceptable. A future
post-processing merge step based on indentation depth or numbering hierarchy
would be needed to collapse sub-tasks back into their parent question.

**Option-line over-segmentation (MCQ format):**

Multiple-choice option lines (`"A. 12 cm²"`, `"B. 15 cm²"`, …) match the
LETTERED structural marker `^[A-Za-z][.)]\s+` and are therefore each detected
as a new question start. This is the primary accuracy concern for MCQ-format
documents — a four-option question produces five regions (one per option plus
the stem) instead of one.

The recommended fix is a **post-processing merge pass** applied after Step 6:

```
For each region R at index i whose lineBoxes contain only a single line
AND whose text matches ^[A-E][.)]\s+ (single uppercase option marker):
    if region[i-1] exists and region[i-1].bounds.bottom is within
    2 × avgLineH of R.bounds.top:
        merge R into region[i-1]  (extend bounds, append lineBoxes)
        remove R from the output list
```

This keeps the LETTERED marker intact for top-level lettered questions
(common in some exam formats) while collapsing MCQ option lines back into
their parent question region.

Intentionally excluded alternative: constraining LETTERED to lowercase-only
(`^[a-z][.)]\s+`) would suppress uppercase option lines but would also miss
exams that label top-level questions with uppercase letters (A, B, C, …).

**Low-confidence path** (`score ≤ handwritingScoreThreshold`, handwriting fallback):
Mark the **first line of a block** as `isQuestionStart = true` if the
vertical gap preceding that block is `> 2.0 × avgLineH`. This heuristic
treats a larger-than-normal visual break as a question boundary when text
recognition is unreliable.

Lines that match neither path remain `isQuestionStart = false` (treated as
continuation lines).

#### Step 6 — Group lines into QuestionRegions

Iterate through all lines in top-to-bottom order:

- On `isQuestionStart = true`: close the previous group (emit a
  `QuestionRegion` if non-empty), open a new group, assign the next
  sequential `index`.
- On `isQuestionStart = false`: append the line to the current open group.

After the last line, close any open group.

**Confidence of a QuestionRegion:**
```
confidence = mean(score) of all contributing lines
```

Lines that precede the first detected question start are discarded (they
belong to headers, instructions, etc.).

#### Step 7 — Render and save output bitmap

1. Create a mutable `Bitmap` copy of `source` (`Bitmap.copy(ARGB_8888, true)`).
2. Create a `Canvas` over the mutable bitmap.
3. Draw each `QuestionRegion.bounds` as a filled-stroke rectangle:
   - Colour: blue (`#2979FF`), stroke width 4 px
4. Draw label `"Q{index+1}"` at `(bounds.left + 4px, bounds.top − 4px)`:
   - Colour: blue, text size 14 sp, bold
5. Compress the bitmap to JPEG (quality 90) and write to `savedImagePath`,
   overwriting the intermediate file produced by Phase 1's `Visualization()`.

The final file at `savedImagePath` contains question-area boxes only
(no Phase 1 line-level green boxes). The original `Bitmap` in memory
(held by the caller) is not modified.

---

## Phase 2 v2 — VisualQuestionDetector (future)

**Replaces:** `GeometricQuestionDetector` at the injection site.
**Interface:** identical — `QuestionDetector.detect(lines, source)`.

### Training Strategy

| Stage | Data | Action |
|---|---|---|
| Initial fine-tune | 60 labeled images | Fine-tune pre-trained PicoDet-S (COCO) on question-region bounding boxes |
| Augmentation | 60 → ~300 effective | Random crop, horizontal flip, brightness ±30%, perspective warp ±5°, elastic distortion |
| Pseudo-labeling | 600 unlabeled | Run initial model; keep predictions with confidence > 0.85; manual spot-check; add to training set |
| Re-train | 60 + verified pseudo-labels | Re-fine-tune on expanded set |

### Inference

1. Resize input `Bitmap` to model input size (e.g., 320 × 320).
2. Run PicoDet inference via PaddleLite (`nativeDetectQuestions` JNI call —
   added alongside, not replacing, `nativeProcessBitmap`).
3. Parse output bounding boxes; apply NMS with IoU threshold 0.5.
4. Map box coordinates back to original bitmap space.
5. Return `Array<QuestionRegion>` (confidence from model score; `lineBoxes`
   populated by intersecting with Phase 1 `OcrResult` bounds).

### Swap Procedure

Replace `GeometricQuestionDetector(...)` with `VisualQuestionDetector(modelPath)`
at the single injection site in `MainActivity`. All downstream code
(`QuestionBoxRenderer`, UI layer) is unaffected.
