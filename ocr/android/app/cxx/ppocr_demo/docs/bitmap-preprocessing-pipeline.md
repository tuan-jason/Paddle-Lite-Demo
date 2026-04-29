# Bitmap Pre-processing Pipeline — Camera Angle Correction

## Problem

The gallery OCR path in `MainActivity.java` (`onActivityResult`) receives a raw bitmap that
may be degraded by:

- **Perspective distortion** — camera not held directly overhead; paper appears trapezoidal.
- **Document skew** — paper placed at a slight angle on a flat surface.
- **Uneven lighting** — shadow or window glare across part of the sheet.
- **Aspect-ratio distortion** — `compressToMax(1024, 1024)` squashes portrait A4 content into a square.

These degrade PaddleOCR recognition scores and, more critically, destroy the geometric
invariants that `SignalFusionQuestionDetector` depends on: `modalLeftMargin`, inter-line gap
distances, and `AnchorProfile.leftMarginRange` are calibrated from bounding-box coordinates
that are only meaningful on a flat, axis-aligned document image.

### Latency context

Pre-processing is **not** the pipeline bottleneck. Typical times on a mid-range 2023 device:

| Stage | Time |
|---|---|
| Pre-processing (this pipeline) | ~60–100 ms |
| `predictor.processBitmap` (PaddleLite OCR) | ~2,000–5,000 ms |
| `SignalFusionQuestionDetector.detect` | ~5–15 ms |

Optimising pre-processing beyond ~100 ms yields less than 5% end-to-end improvement.
If total latency is the target, the OCR model inference is the leverage point.

---

## Design: Hybrid OpenCV + Android Hardware Canvas

OpenCV is used **only for the analysis steps** (corner detection, angle measurement) where
pixel-level math has no hardware-accelerated alternative. All actual pixel transforms use
Android's hardware-accelerated `Canvas`, which runs on the device GPU and is 3–5× faster
than OpenCV's CPU-bound `Mat` operations for warping and resizing.

| Step | Library | Est. time | Role |
|---|---|---|---|
| 1. Corner detection | OpenCV | ~30–50 ms | Analysis — finds document boundary |
| 2. Perspective correction | Android `Matrix` + hardware `Canvas` | ~5–10 ms | GPU transform — replaces `warpPerspective` |
| 3. Skew correction | OpenCV (angle only) + Android `Matrix` + hardware `Canvas` | ~15–25 ms | OpenCV measures angle; GPU rotates |
| 4. Contrast enhancement | Android `ColorMatrixColorFilter` + hardware `Canvas` | ~3–5 ms | GPU — normalises lighting |
| 5. Resolution normalisation | `Bitmap.createScaledBitmap` | ~3–5 ms | Hardware-accelerated resize |
| **Total** | | **~55–95 ms** | |

### Fallback contract

If any step cannot produce a reliable result (e.g. no document quadrilateral found), that
step is **skipped** and the bitmap from the previous step is passed through unchanged.
The pipeline never throws; it always returns the best-corrected bitmap it could produce.

---

## Integration Point

The corrected bitmap replaces the raw bitmap **before** `predictor.processBitmap` is called.
Nothing else in the OCR or detection pipeline changes.

```java
// MainActivity.java — onActivityResult
// Current line 367:
final Bitmap raw = BitmapUtils.decodeBitmapWithOrientation(getContentResolver(), uri);
if (raw == null) return;

// Add inside the executor.execute block, before predictor.processBitmap:
final Bitmap bitmap = BitmapPreprocessor.process(raw);
if (bitmap != raw) raw.recycle();

// Existing code continues unchanged:
long startTs = SystemClock.elapsedRealtime();
String json = predictor.processBitmap(bitmap, outPath);
```

`BitmapPreprocessor.process(Bitmap): Bitmap` is the single public entry point. It is a
pure function — given a bitmap it returns a corrected bitmap (possibly the same object
if no correction was needed or possible).

---

## Step Details

### Step 1 — Document corner detection

**Goal:** Locate the four corners of the exam paper within the camera frame.

**Library:** OpenCV (`Imgproc.GaussianBlur`, `Imgproc.Canny`, `Imgproc.findContours`,
`Imgproc.approxPolyDP`, `Imgproc.contourArea`)

**Algorithm:**
1. Convert bitmap to greyscale `Mat`.
2. `GaussianBlur` (5×5 kernel) — suppress noise before edge detection.
3. `Canny` (threshold1=75, threshold2=200) — detect strong edges.
4. `findContours` (`RETR_EXTERNAL`, `CHAIN_APPROX_SIMPLE`) — find outer boundaries.
5. Sort contours by area descending; take the largest candidate.
6. `approxPolyDP` (epsilon = 2% of arc length, closed = true) — approximate polygon.
7. Accept only if result has exactly 4 vertices **and** area > 20% of image area.

**Output:** `MatOfPoint2f` — 4 corner points (TL, TR, BR, BL, clockwise); or `null` if
no suitable quadrilateral is found.

**Fallback:** `null` result → Steps 2–3 are skipped.

---

### Step 2 — Perspective correction

**Goal:** Warp the trapezoidal paper view to a flat, top-down rectangle.

**Library:** `android.graphics.Matrix.setPolyToPoly()` + hardware-accelerated `Canvas`

**Why not `warpPerspective`:** Android's `Matrix` supports 4-point perspective transforms
natively. Drawing via a hardware-accelerated `Canvas` pushes the transform to the GPU,
making it 3–5× faster than OpenCV's CPU-bound `warpPerspective` for this use case.

**Algorithm:**
1. Order the 4 corners into TL/TR/BR/BL (by coordinate sum/diff — see `orderCorners()`).
2. Compute target width = max(top edge length, bottom edge length); height = max(left, right).
   This preserves the document's natural proportions without hard-coding an A4 size.
3. Build `srcPoints` (float[8]) and `dstPoints` (float[8] for the target rectangle).
4. `matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)`.
5. Create a new `Bitmap` of the target size; draw the source bitmap through the matrix on a
   hardware `Canvas`.

```kotlin
val matrix = android.graphics.Matrix()
matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)
val corrected = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
Canvas(corrected).drawBitmap(source, matrix, null)
```

**Output:** Flat-view bitmap. After this step, all question-start lines share the same true
left x-coordinate — which `modalLeftMargin` and `AnchorProfile.leftMarginRange` require.

**Fallback:** Step 1 returned `null` → this step is skipped entirely.

---

### Step 3 — Skew correction

**Goal:** Correct residual rotation after perspective correction.

**Library:** OpenCV `Imgproc.HoughLinesP` (angle detection only) +
`android.graphics.Matrix` + hardware `Canvas` (the actual rotation)

**Algorithm:**
1. Run `Canny` on the perspective-corrected bitmap (converted to `Mat`).
2. `HoughLinesP` (`minLineLength` = 30% of image width, `maxLineGap` = 5% of width).
3. Filter to near-horizontal lines (|angle| < 30°).
4. Compute the median angle of the filtered lines.
5. **Skip rotation if |median angle| < 0.5°** (within acceptable tolerance).
6. Build an `android.graphics.Matrix` rotation around the bitmap centre.
7. Apply via `Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)` — hardware-accelerated.

**Output:** Bitmap with text lines axis-aligned. `gapAbove` values in the detector now
reflect true vertical document distances; `gap_very_large` / `gap_large` / `gap_small`
become reliable.

**Fallback:** Fewer than 5 near-horizontal lines detected → skip rotation; pass bitmap through.

---

### Step 4 — Contrast enhancement

**Goal:** Normalise uneven lighting so OCR confidence scores are consistent across the page.

**Library:** `android.graphics.ColorMatrixColorFilter` + hardware `Canvas`

**Algorithm:**
1. Compute a brightness/contrast `ColorMatrix` that stretches the histogram toward the
   target range (auto-contrast: sample min/max luminance from a downsized thumbnail, then
   scale the full image).
2. Create a `Paint` with the `ColorMatrixColorFilter` applied.
3. Draw the bitmap onto a hardware `Canvas` using that paint.

**Note:** This is simpler than CLAHE (which applies tiling) and runs on the GPU. For typical
exam-paper photos (fairly uniform lighting with localised shadow), auto-contrast via
`ColorMatrixColorFilter` is sufficient and significantly faster. If CLAHE-level local
adaptation is needed in the future, it can be added as an OpenCV fallback for images where
the luminance range exceeds a threshold.

**Output:** Bitmap with normalised contrast. Raises average `OcrResult.score` on pages with
shadows or gradients, keeping more lines above `HIGH_CONF_THRESHOLD = 0.85`.

---

### Step 5 — Resolution normalisation

**Goal:** Replace the current `compressToMax(1024, 1024)` square crop with a
aspect-ratio-preserving resize.

**Library:** `Bitmap.createScaledBitmap` (hardware-accelerated)

**Algorithm:**
1. Target width: `1240 px` (≈ A4 at 105 DPI — sufficient for PaddleLite, low memory cost).
2. Target height: `(targetWidth / bitmap.width.toFloat()) × bitmap.height` — preserves
   aspect ratio.
3. `Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)`.

**Output:** Bitmap at 1240 px wide, proportional height. Eliminates character squashing on
portrait A4 documents that currently lowers recognition confidence.

**Note:** `compressToMax(1024, 1024)` in `takePicture` is **not** changed here — the
camera capture flow is out of scope.

---

## New Class

```
BitmapPreprocessor.kt
├── fun process(source: Bitmap): Bitmap          ← public entry point
│       Bitmap → all steps with per-step fallback → Bitmap
│
├── private fun detectDocumentCorners(bitmap: Bitmap): MatOfPoint2f?
│       OpenCV: Gaussian → Canny → findContours → approxPolyDP
│
├── private fun orderCorners(pts: MatOfPoint2f): MatOfPoint2f
│       Sort 4 points into TL / TR / BR / BL order
│
├── private fun correctPerspective(bitmap: Bitmap, corners: MatOfPoint2f): Bitmap
│       Matrix.setPolyToPoly + hardware Canvas
│
├── private fun measureSkewAngle(bitmap: Bitmap): Float
│       OpenCV: Canny → HoughLinesP → median angle; returns 0f if unreliable
│
├── private fun correctSkew(bitmap: Bitmap, angleDeg: Float): Bitmap
│       android.graphics.Matrix rotation + Bitmap.createBitmap
│
├── private fun enhanceContrast(bitmap: Bitmap): Bitmap
│       ColorMatrixColorFilter auto-contrast on hardware Canvas
│
└── private fun normaliseResolution(bitmap: Bitmap): Bitmap
        Bitmap.createScaledBitmap to 1240 px wide
```

Located at:
`app/src/main/kotlin/com/baidu/paddle/lite/demo/ppocr_demo/BitmapPreprocessor.kt`

---

## Task List

### Infrastructure

- [ ] **T-0** Verify OpenCV initialisation is complete before the background thread reaches
  `BitmapPreprocessor.process()`. Check whether the project uses `OpenCVLoader.initDebug()`
  or the async `OpenCVLoader.initAsync()` path, and confirm the flag is set before any
  `Mat` operations run.

### Implementation

- [ ] **T-1** Create `BitmapPreprocessor.kt` with the `process()` entry point: Bitmap input,
  run all steps in sequence with per-step try/catch fallback, return corrected Bitmap.
  No OpenCV calls yet — just the skeleton and pass-through.

- [ ] **T-2** Implement `detectDocumentCorners()`: Bitmap → greyscale Mat → GaussianBlur →
  Canny → findContours → approxPolyDP → return `MatOfPoint2f?`.

- [ ] **T-3** Implement `orderCorners()`: sort 4 arbitrary points into TL/TR/BR/BL using
  coordinate sum (TL = min sum, BR = max sum) and coordinate diff (TR = min diff, BL = max diff).

- [ ] **T-4** Implement `correctPerspective()`: compute target size from corner distances,
  build `srcPoints` / `dstPoints` float arrays, `Matrix.setPolyToPoly`, draw on hardware Canvas.

- [ ] **T-5** Implement `measureSkewAngle()`: Canny on corrected bitmap → HoughLinesP →
  filter near-horizontal lines → median angle; return `0f` if fewer than 5 lines found.

- [ ] **T-6** Implement `correctSkew()`: `android.graphics.Matrix` rotation +
  `Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)`; skip if `|angleDeg| < 0.5f`.

- [ ] **T-7** Implement `enhanceContrast()`: sample min/max luminance from a 64×64
  thumbnail, build `ColorMatrix` auto-contrast, apply via `ColorMatrixColorFilter` on a
  hardware Canvas.

- [ ] **T-8** Implement `normaliseResolution()`: compute target height preserving aspect
  ratio, `Bitmap.createScaledBitmap` to 1240 px width.

- [ ] **T-9** Wire all steps inside `process()` with intermediate bitmap recycling — recycle
  each intermediate result once the next step has consumed it to avoid memory spikes.

### Integration

- [ ] **T-10** Move `BitmapPreprocessor.process(raw)` call into `onActivityResult` inside
  the `executor.execute` block, after `decodeBitmapWithOrientation` and before
  `predictor.processBitmap`. Recycle `raw` if a new bitmap was returned.

### Testing

- [ ] **T-11** Unit test `orderCorners()` with 4 points in each of the 24 possible orderings;
  verify TL/TR/BR/BL assignment is correct in all cases.

- [ ] **T-12** Unit test `detectDocumentCorners()` on a synthetic `Bitmap` with a known
  white rectangle on a dark background; verify returned corners are within 5 px of the
  true corners.

- [ ] **T-13** Unit test `correctPerspective()` with a known trapezoidal transform; verify
  a straight vertical line in the source appears straight in the output (< 2 px deviation).

- [ ] **T-14** Unit test `measureSkewAngle()`: rotate a test bitmap by a known angle (e.g.
  4.0°), run the step, verify returned angle is within 0.5° of 4.0°.

- [ ] **T-15** Unit test `normaliseResolution()`: verify output width is exactly 1240 px and
  aspect ratio is preserved within 1% tolerance.

- [ ] **T-16** Integration smoke test: run `BitmapPreprocessor.process()` on a real exam
  photo taken at ~20° angle; confirm `SignalFusionQuestionDetector` returns ≥ the region
  count produced on the same image without pre-processing.
