# MlKitSignalFusionQuestionDetector - Implementation Task List

Progress legend: `[ ]` not started, `[x]` done.

## 1. Preparation and Baseline

- [x] Confirm current detector entry points and call graph (`Gallery` flow, detector wiring, feature flags).
- [x] Snapshot baseline behavior on representative ML Kit OCR fixtures (store current outputs for regression comparison).
- [x] Define target class/file layout for `MlKitSignalFusionQuestionDetector` and helper geometry components.

Acceptance criteria:

- Clear map of touched files and integration points documented in PR notes.
- Baseline fixture outputs captured and reproducible.

## 2. Geometry Normalization Layer

- [x] Add internal geometry model (`MlKitLineGeom`) with rect/size/quality fields.
- [x] Implement robust corner reordering (`TL, TR, BR, BL`) for ML Kit boxes.
- [x] Implement bounds clamping to image dimensions.
- [x] Implement invalid/degenerate box handling and skip policy.
- [x] Implement geometry quality tagging (`QUAD_STRONG`, `QUAD_WEAK`, `RECT_FALLBACK`).

Tests:

- [x] Unit test: reordered corners are stable under shuffled input.
- [x] Unit test: clamping keeps all points in image bounds.
- [x] Unit test: degenerate boxes are rejected or downgraded consistently.

Acceptance criteria:

- Geometry normalization is deterministic and fully covered by unit tests.

## 3. ML Kit Calibration Profile

- [x] Introduce ML Kit-specific calibration constants (gap multipliers, margin tolerance, confidence gate, rule thresholds).
- [x] Implement robust median/mode/percentile-based calibration on normalized geometry.
- [x] Implement calibration status transitions (`FULL`, `PARTIAL`, `GEOMETRY_ONLY`, `MINIMAL`) for ML Kit score characteristics.

Tests:

- [x] Unit test: correct status transitions for high/low/missing confidence cases.
- [x] Unit test: gap and margin calibration values match expected outputs from fixtures.

Acceptance criteria:

- Calibration outputs are stable and explainable from fixture inputs.

## 4. Signal Scoring Adaptation

- [x] Reuse existing structural/semantic signal families in new detector implementation.
- [x] Adapt geometric signal evaluation to ML Kit profile (gap/rule/margin behavior).
- [x] Add geometry-quality-aware weighting or guards for fragile lines.
- [x] Keep tier logic and sub-question veto semantics consistent with current detector.

Tests:

- [x] Unit test: Tier1/Tier2/Tier3 decisions match expected outcomes on synthetic cases.
- [x] Unit test: horizontal-rule logic uses relative thickness and width fraction.
- [x] Unit test: `RECT_FALLBACK` lines do not over-trigger geometry-only splits.

Acceptance criteria:

- Signal decisions pass deterministic tests and avoid known ML Kit over-split patterns.

## 5. Region Grouping and Contract Compatibility

- [x] Implement grouping from marked lines to `QuestionRegion`.
- [x] Verify `bounds` union and `confidence` averaging behavior parity.
- [x] Ensure output schema compatibility with existing downstream consumers.

Tests:

- [x] Unit test: grouping index sequence and region boundaries.
- [x] Unit test: region confidence equals mean line score.

Acceptance criteria:

- Downstream code can consume output without changes.

## 6. Integration and Routing

- [x] Wire new detector behind a feature flag or explicit ML Kit detector selection path.
- [x] Keep old detector available for rollback/A-B comparison.
- [x] Add runtime logging hooks for calibration status and split reasons (debug only).

Tests:

- [x] Integration test: ML Kit OCR path invokes `MlKitSignalFusionQuestionDetector`.
- [x] Integration test: fallback/legacy path remains unchanged.

Acceptance criteria:

- Switching between old/new detectors requires only config/flag change.

## 7. Regression Suite and Tuning Loop

- [ ] Build fixture set from real ML Kit OCR JSON snapshots (JP/EN, simple/complex layouts).
- [ ] Add regression assertions for expected question region count and bounds.
- [ ] Run iterative threshold tuning based on false split/merge errors.
- [ ] Freeze tuned constants and document rationale.

Tests:

- [ ] Regression suite green on all fixtures.
- [ ] No critical regressions against baseline detector on non-ML Kit paths.

Acceptance criteria:

- Tuned detector meets agreed quality bar on fixture corpus.

## 8. Documentation and Handover

- [ ] Update design docs with final constants and any deviations from initial plan.
- [ ] Document feature flag usage and rollout/rollback instructions.
- [ ] Add short troubleshooting notes for common ML Kit geometry anomalies.

Acceptance criteria:

- Another engineer can run, validate, and tune detector using docs alone.
