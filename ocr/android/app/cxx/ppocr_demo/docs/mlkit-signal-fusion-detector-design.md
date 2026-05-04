# MlKitSignalFusionQuestionDetector - Algorithm Design

This document defines a dedicated question-segmentation detector tuned for ML Kit OCR geometry while preserving the existing `QuestionRegion` output contract.

## Goals

- Use ML Kit line geometry directly without forcing Paddle-equivalent box semantics.
- Keep behavior stable for current numbered-question and semantic patterns.
- Reduce geometry-driven false splits caused by differences between Paddle and ML Kit boxes.
- Keep the detector testable with deterministic, pure-Kotlin logic.

## Scope

In scope:

- A new detector implementation: `MlKitSignalFusionQuestionDetector`.
- ML Kit-specific geometry normalization and calibration logic.
- ML Kit-specific threshold profile for gap/margin/rule signals.

Out of scope:

- Replacing existing `SignalFusionQuestionDetector`.
- Changing OCR engines or the `OcrResult` JSON schema.
- UI/renderer changes.

## Input and Output Contract

Input:

- `Array<OcrResult>` where each `OcrResult` includes:
  - `box: int[4][2]` (`TL, TR, BR, BL`)
  - `text: String`
  - `score: Float`
- Source bitmap dimensions.

Output:

- `List<QuestionRegion>` with:
  - `index`
  - union `bounds`
  - mean OCR `confidence`
  - per-line `lineBoxes`

No output schema change is introduced.

## Key Geometry Differences to Handle

Compared with Paddle boxes, ML Kit boxes may be:

- Tighter around glyph strokes.
- Sometimes axis-aligned fallbacks (`boundingBox`) instead of robust oriented quads.
- Less stable in corner ordering for heavily rotated lines if raw corners are used directly.

These differences mostly affect:

- `lineHeight` distribution,
- inter-line gap magnitude,
- left-margin clustering,
- horizontal-rule detection.

## Algorithm Overview

The detector uses a 3-stage flow:

1. Geometry normalization (ML Kit specific).
2. Document calibration + signal scoring.
3. Region grouping.

### Stage 1: Geometry Normalization

For each `OcrResult`:

1. Validate quad shape (4 points, finite coordinates).
2. Reorder points into clockwise `TL, TR, BR, BL` using a robust corner-order method.
3. Clamp points to `[0, width-1] x [0, height-1]`.
4. Derive normalized features:
   - `alignedRect` (`minX, minY, maxX, maxY`)
   - `lineHeightPx`
   - `lineWidthPx`
   - `aspectRatio`
   - optional `tiltDeg`
5. Attach geometry source quality:
   - `QUAD_STRONG`: robust 4-corner line quad
   - `QUAD_WEAK`: suspicious/degenerate quad repaired
   - `RECT_FALLBACK`: effectively axis-aligned fallback

Notes:

- Downstream scoring still uses aligned rects for consistency, but quality flags influence penalties/thresholds.

### Stage 2: Calibration

Sort lines by `rect.top`, then compute:

- `medianLineHeight` from normalized `lineHeightPx`.
- `gapLarge` and `gapVeryLarge` from ML Kit profile multipliers.
- `modalLeftMargin` via binned mode on `rect.left`.
- `header/footer` boundaries by image height fraction.
- `CalibrationStatus` (`FULL`, `PARTIAL`, `GEOMETRY_ONLY`, `MINIMAL`) based on score availability and anchor count.

ML Kit-specific calibration behavior:

- Use more robust statistics (median, percentile caps) to reduce sensitivity to tight boxes and outliers.
- Lower confidence gate versus Paddle path when ML Kit confidence is sparse/noisy.

### Stage 3: Signal Scoring

Use the same major signal families as current signal-fusion:

- Geometric: gap/margin changes.
- Structural: numbered marker, rule line, header/footer, marks-tag.
- Semantic: language-aware imperative/interrogative/keyword patterns.
- Anchor profile boost/penalty.

ML Kit-specific scoring adaptations:

- Gap thresholds use multipliers tuned for ML Kit line-height distribution.
- Horizontal-rule detection uses relative thickness (`height / medianLineHeight`) and width fraction, not fixed `height < 5`.
- Reduce weight of fragile geometry signals for `RECT_FALLBACK` lines.

Tier decision stays unchanged conceptually:

- Tier 1: dominant single signal.
- Tier 2: medium net score with supporting signal.
- Tier 3: weaker consensus.
- Sub-question veto still applies below Tier 1.

### Stage 4: Grouping

Same grouping strategy:

- Start new region when line is predicted as question start.
- Otherwise append to current region.
- Region bounds are union of member rects.
- Region confidence is average OCR score.

## Proposed Data Additions (Internal Only)

- `MlKitLineGeom`:
  - `rect: RectF`
  - `height: Float`
  - `width: Float`
  - `tiltDeg: Float?`
  - `quality: GeometryQuality`
- `GeometryQuality` enum:
  - `QUAD_STRONG`, `QUAD_WEAK`, `RECT_FALLBACK`
- `MlKitCalibrationProfile`:
  - threshold multipliers and confidence gates specific to ML Kit.

These are implementation details and do not alter public API.

## Failure Handling

- Invalid/empty geometry line: skip line.
- All lines invalid: return empty region list.
- Missing confidence: clamp/default to `0.0` and allow geometry-only path.

## Tuning Strategy

Use a fixed corpus of ML Kit OCR outputs and optimize for:

- Question-start precision/recall,
- over-splitting rate,
- under-splitting rate,
- stability across Japanese and English papers.

Tuning order:

1. Geometry thresholds (gap/margin/rule).
2. Confidence gate and calibration status transitions.
3. Anchor boost/penalty strength.
4. Semantic signal tie-breakers.

## Validation Plan

- Unit tests for normalization and scoring edge cases.
- Regression tests reusing existing detector fixtures where applicable.
- A/B evaluation against current detector on ML Kit OCR snapshots.
- Verify no contract regressions in downstream `QuestionRegion` consumers.

