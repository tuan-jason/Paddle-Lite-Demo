# Hybrid Approach: PaddleLite Detection + ML Kit Recognition

## ⚠️ Non-Invasive Design Principle

> **Do not modify existing functions.** Prioritize adding new functions/implementations so that this experiment does not cause side effects to current results. Design the code to be least invasive to existing code at the integration point.

All new code is additive. Existing `RunOcrOnBitmap`, `nativeProcessBitmap`, `processBitmap`, `GalleryOcrRouter`, `SignalFusionQuestionDetector`, and `MlKitSignalFusionQuestionDetectorV2` remain untouched.

---

## Problem

| Method | OCR Engine | Detector | Accuracy | Latency |
|---|---|---|---|---|
| 1 (current) | PaddleLite (det→cls→rec) | `SignalFusionQuestionDetector` | ★★★★★ | 2–3s |
| 2 (current) | ML Kit Text Recognition v2 | `MlKitSignalFusionQuestionDetectorV2` | ★★★½ | < 1s |

Method 1's bottleneck is the full det→cls→rec C++ pipeline (~2–5s). Detection alone (`DetPredictor::Predict`) runs in ~200–400ms. Method 2's accuracy gap comes from ML Kit's different box geometry breaking `SignalFusionQuestionDetector`'s calibration assumptions.

## Solution

Use PaddleLite's DB text detection model **only** (no cls/rec), then use ML Kit for text recognition on the detected box crops. This gives PaddleLite's superior box geometry (which `SignalFusionQuestionDetector` was tuned for) with ML Kit's fast recognition.

## Estimated Latency Budget

| Stage | Time | Notes |
|---|---|---|
| Bitmap preprocessing | ~60–100ms | Existing `BitmapPreprocessor.process()` |
| PaddleLite detection only | ~200–400ms | `DetPredictor::Predict` — no cls/rec |
| Crop + ML Kit recognition | ~100–200ms | ML Kit on pre-cropped regions |
| Signal fusion detection | ~5–15ms | Existing `SignalFusionQuestionDetector` |
| **Total** | **~400–700ms** | Target: < 1s on mid-level device |

## Architecture

```
Input Bitmap (from gallery)
        │
        ▼
BitmapPreprocessor.process()          [existing, unchanged]
        │
        ▼
┌───────────────────────────────────────────────────────────────┐
│  NEW: HybridOcrEngine                                         │
│                                                               │
│  Step 1: PaddleLite Detection Only                            │
│  Native.detectBoxes(bitmap)                                   │
│  └─► nativeDetectBoxes [JNI]                                  │
│       └─► Pipeline::RunDetectionOnly [C++, NEW]               │
│            DetPredictor::Predict → boxes JSON                 │
│  Returns: box coordinates (4-point polygons) in bitmap space  │
│                                                               │
│  Step 2: ML Kit Recognition on Crops                          │
│  For each detected box:                                       │
│    crop bitmap region → ML Kit TextRecognizer → text + score  │
│                                                               │
│  Step 3: Assemble OcrResult[]                                 │
│  Combine PaddleLite boxes + ML Kit text/scores                │
│  → OcrResult[] with identical format to existing pipeline     │
└───────────────────────────┬───────────────────────────────────┘
                            │ OcrResult[]
                            ▼
SignalFusionQuestionDetector.detect()  [existing, unchanged]
        │
        ▼
QuestionBoxRenderer.render()          [existing, unchanged]
```

## Detailed Design

### Layer 1: C++ — New Detection-Only Entry Point

**File**: `pipeline.h` — add new method declaration (do not modify existing methods)

```cpp
// NEW — detection only, no cls/rec
std::string RunDetectionOnly(const uint8_t *pixels, int width, int height);
```

**File**: `pipeline.cc` — add new method implementation

```cpp
std::string Pipeline::RunDetectionOnly(const uint8_t *pixels, int width, int height) {
    if (!pixels) return "";

    cv::Mat rgbaImage(height, width, CV_8UC4, const_cast<uint8_t *>(pixels));
    cv::Mat bgrImage;
    cv::cvtColor(rgbaImage, bgrImage, cv::COLOR_BGRA2BGR);

    cv::Mat srcimg;
    bgrImage.copyTo(srcimg);

    // Detection only — reuses existing DetPredictor::Predict unchanged
    auto boxes = detPredictor_->Predict(srcimg, Config_, nullptr, nullptr, nullptr);

    // Serialize boxes to JSON (no text/score — just geometry)
    std::ostringstream json;
    json << "[";
    for (int i = (int)boxes.size() - 1; i >= 0; i--) {
        if (i < (int)boxes.size() - 1) json << ",";
        json << "[[" << boxes[i][0][0] << "," << boxes[i][0][1] << "],"
             << "[" << boxes[i][1][0] << "," << boxes[i][1][1] << "],"
             << "[" << boxes[i][2][0] << "," << boxes[i][2][1] << "],"
             << "[" << boxes[i][3][0] << "," << boxes[i][3][1] << "]]";
    }
    json << "]";
    return json.str();
}
```

**JSON output format**: Array of 4-point polygons, each `[[x0,y0],[x1,y1],[x2,y2],[x3,y3]]` in TL→TR→BR→BL order. Same coordinate space as existing `RunOcrOnBitmap` boxes.

### Layer 2: JNI Bridge — New Function

**File**: `Native.cc` — add new JNI function (do not modify existing functions)

```cpp
JNIEXPORT jstring JNICALL
Java_com_baidu_paddle_lite_demo_ppocr_1demo_Native_nativeDetectBoxes(
    JNIEnv *env, jclass thiz, jlong ctx, jbyteArray jPixels,
    jint width, jint height) {
  if (ctx == 0) return nullptr;
  jbyte *pixelBytes = env->GetByteArrayElements(jPixels, nullptr);
  Pipeline *pipeline = reinterpret_cast<Pipeline *>(ctx);
  std::string result = pipeline->RunDetectionOnly(
      reinterpret_cast<const uint8_t *>(pixelBytes), width, height);
  env->ReleaseByteArrayElements(jPixels, pixelBytes, JNI_ABORT);
  if (result.empty()) return nullptr;
  return cpp_string_to_jstring(env, result);
}
```

### Layer 3: Java Bridge — New Method in Native.java

**File**: `Native.java` — add new methods (do not modify existing methods)

```java
/**
 * Runs PaddleLite text detection only (no classification or recognition).
 * Returns JSON array of 4-point polygon boxes in bitmap coordinate space.
 */
public String detectBoxes(Bitmap bitmap) {
    if (ctx == 0) return null;
    Bitmap rgba = bitmap.copy(Bitmap.Config.ARGB_8888, false);
    ByteBuffer buffer = ByteBuffer.allocate(rgba.getByteCount());
    rgba.copyPixelsToBuffer(buffer);
    return nativeDetectBoxes(ctx, buffer.array(), rgba.getWidth(), rgba.getHeight());
}

public static native String nativeDetectBoxes(long ctx, byte[] rgbaPixels, int width, int height);
```

### Layer 4: Kotlin — HybridOcrEngine (New File)

**File**: `app/src/main/kotlin/com/baidu/paddle/lite/demo/ppocr_demo/HybridOcrEngine.kt`

This is the core orchestrator. It:
1. Calls `Native.detectBoxes()` to get PaddleLite box geometry.
2. Parses the box JSON.
3. For each box, crops the bitmap region and runs ML Kit recognition.
4. Assembles `OcrResult[]` with PaddleLite boxes + ML Kit text/scores.

```kotlin
package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Hybrid OCR engine: PaddleLite detection + ML Kit recognition.
 *
 * Produces OcrResult[] with PaddleLite box geometry (identical to the
 * existing full-pipeline boxes) and ML Kit text/scores.
 *
 * This class does NOT modify any existing code. It is a new, additive
 * implementation that can be swapped in at the GalleryOcrRouter level.
 */
class HybridOcrEngine {

    private val recognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )

    /**
     * @param predictor  Existing Native instance (already initialized).
     * @param bitmap     Pre-processed bitmap (after BitmapPreprocessor).
     * @return OcrResult[] with PaddleLite boxes + ML Kit text/scores.
     */
    @Throws(Exception::class)
    fun process(predictor: Native, bitmap: Bitmap): Array<OcrResult> {
        // Step 1: PaddleLite detection only
        val boxesJson = predictor.detectBoxes(bitmap)
            ?: return emptyArray()
        val boxes = parseBoxes(boxesJson)
        if (boxes.isEmpty()) return emptyArray()

        // Step 2: For each box, crop and recognize with ML Kit
        val results = mutableListOf<OcrResult>()
        for (box in boxes) {
            val crop = cropRotatedRegion(bitmap, box) ?: continue
            val (text, score) = recognizeCrop(crop)
            if (crop != bitmap) crop.recycle()
            if (text.isBlank()) continue
            results.add(OcrResult(box, text, score))
        }
        return results.toTypedArray()
    }

    fun close() {
        recognizer.close()
    }

    // ── Box JSON parsing ──────────────────────────────────────────────────

    /**
     * Parses the JSON from RunDetectionOnly.
     * Format: [ [[x0,y0],[x1,y1],[x2,y2],[x3,y3]], ... ]
     * Returns: List of int[4][2] boxes (TL, TR, BR, BL).
     */
    private fun parseBoxes(json: String): List<Array<IntArray>> {
        val boxes = mutableListOf<Array<IntArray>>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val boxArr = arr.getJSONArray(i)
            if (boxArr.length() < 4) continue
            val box = Array(4) { p ->
                val pt = boxArr.getJSONArray(p)
                intArrayOf(pt.getInt(0), pt.getInt(1))
            }
            boxes.add(box)
        }
        return boxes
    }

    // ── Crop extraction ───────────────────────────────────────────────────

    /**
     * Crops the bitmap region defined by the 4-point polygon box.
     * Uses axis-aligned bounding rect for simplicity — the perspective
     * correction is not needed for ML Kit recognition (it handles mild skew).
     */
    private fun cropRotatedRegion(bitmap: Bitmap, box: Array<IntArray>): Bitmap? {
        val xs = box.map { it[0] }
        val ys = box.map { it[1] }
        val left = max(0, xs.min())
        val top = max(0, ys.min())
        val right = min(bitmap.width, xs.max())
        val bottom = min(bitmap.height, ys.max())
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }

    // ── ML Kit recognition ────────────────────────────────────────────────

    /**
     * Runs ML Kit text recognition on a single cropped bitmap.
     * Blocks until result is available (called from background thread).
     * Returns (text, confidence) pair.
     */
    private fun recognizeCrop(crop: Bitmap): Pair<String, Float> {
        val latch = CountDownLatch(1)
        val textRef = AtomicReference("")
        val scoreRef = AtomicReference(0.0f)

        val image = InputImage.fromBitmap(crop, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val allText = result.text
                // Average confidence across all lines
                val lines = result.textBlocks.flatMap { it.lines }
                val avgConf = if (lines.isNotEmpty()) {
                    lines.mapNotNull { it.confidence }
                        .takeIf { it.isNotEmpty() }
                        ?.average()?.toFloat() ?: 0.0f
                } else 0.0f
                textRef.set(allText)
                scoreRef.set(avgConf.coerceIn(0.0f, 1.0f))
                latch.countDown()
            }
            .addOnFailureListener {
                latch.countDown()
            }

        latch.await()
        return Pair(textRef.get(), scoreRef.get())
    }

    companion object {
        private const val TAG = "HybridOcrEngine"
    }
}
```

### Layer 5: Integration — New Feature Flag + Router Method

**File**: `OcrFeatureFlags.kt` — add new flag (do not modify existing flags)

```kotlin
@JvmField
var USE_HYBRID_OCR: Boolean = false
```

**File**: `GalleryOcrRouter.kt` — add new branch (do not modify existing branches)

The integration is a single new `if` branch at the top of `processBitmap`, checked before the existing ML Kit and Paddle branches. When `USE_HYBRID_OCR` is false (default), behavior is identical to current code.

```kotlin
object GalleryOcrRouter {
    @JvmStatic
    @Throws(Exception::class)
    fun processBitmap(
        paddle: Native,
        bitmap: Bitmap,
        outPath: String
    ): String {
        // NEW: Hybrid path — PaddleLite detection + ML Kit recognition
        if (OcrFeatureFlags.USE_HYBRID_OCR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val engine = HybridOcrEngine()
            try {
                val results = engine.process(paddle, bitmap)
                return OcrResultJsonAdapter.toJson(results)
            } finally {
                engine.close()
            }
        }

        // EXISTING: ML Kit full path (unchanged)
        if (OcrFeatureFlags.USE_MLKIT_OCR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // ... existing code unchanged ...
        }

        // EXISTING: Paddle full path (unchanged)
        return paddle.processBitmap(bitmap, outPath)
    }
}
```

**MainActivity.java** — **No changes needed.** The existing `onActivityResult` already calls `GalleryOcrRouter.processBitmap()` and feeds the result into `SignalFusionQuestionDetector`. The hybrid path produces identical `OcrResult[]` format, so the entire downstream pipeline (detector → renderer → display) works unchanged.

### Detector Selection

When `USE_HYBRID_OCR = true`, the `OcrResult[]` will have PaddleLite box geometry. This means the existing `SignalFusionQuestionDetector` (not the ML Kit variant) should be used for question detection, since its calibration thresholds are tuned for PaddleLite boxes.

The current `MainActivity.onActivityResult` line ~393 already uses `SignalFusionQuestionDetector`:
```java
List<QuestionRegion> regions =
    new SignalFusionQuestionDetector().detect(results, bitmap);
```

This is correct for the hybrid path. No change needed.

---

## File Change Summary

| File | Change Type | Language | Invasiveness |
|---|---|---|---|
| `pipeline.h` | Add `RunDetectionOnly` declaration | C++ | Additive only |
| `pipeline.cc` | Add `RunDetectionOnly` implementation | C++ | Additive only |
| `Native.cc` | Add `nativeDetectBoxes` JNI function | C++ | Additive only |
| `Native.java` | Add `detectBoxes()` + native declaration | Java | Additive only |
| `HybridOcrEngine.kt` | **New file** | Kotlin | New file |
| `OcrFeatureFlags.kt` | Add `USE_HYBRID_OCR` flag | Kotlin | Additive only |
| `GalleryOcrRouter.kt` | Add hybrid branch at top of `processBitmap` | Kotlin | Minimal — one new `if` block |

**No existing functions are modified.** All changes are new declarations, new implementations, or new files.

---

## Rollback

Set `OcrFeatureFlags.USE_HYBRID_OCR = false` (the default). The hybrid path is never entered. All existing behavior is preserved.

---

## Task List

### C++ Layer

- [ ] **T1** Add `RunDetectionOnly` declaration to `pipeline.h`
- [ ] **T2** Implement `RunDetectionOnly` in `pipeline.cc`
- [ ] **T3** Add `nativeDetectBoxes` JNI function in `Native.cc`

### Java Bridge

- [ ] **T4** Add `detectBoxes()` method + `nativeDetectBoxes` native declaration in `Native.java`

### Kotlin Engine

- [ ] **T5** Create `HybridOcrEngine.kt`

### Integration

- [ ] **T6** Add `USE_HYBRID_OCR` flag to `OcrFeatureFlags.kt`
- [ ] **T7** Add hybrid branch to `GalleryOcrRouter.processBitmap()`

### Verification

- [ ] **T8** Build verification — `./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac`
- [ ] **T9** Functional test — set `USE_HYBRID_OCR = true`, run gallery OCR on test images, verify question regions match Method 1 output
- [ ] **T10** Latency benchmark — compare hybrid path vs Method 1 (full Paddle) and Method 2 (full ML Kit) on same image corpus

---

## Open Questions

| Question | Notes |
|---|---|
| Crop strategy | Current design uses axis-aligned bounding rect. If ML Kit struggles with skewed crops, switch to perspective-corrected crops using `GetRotateCropImage` logic ported to Kotlin/Android `Matrix`. |
| ML Kit recognizer reuse | Current design creates a new `TextRecognizer` per call. If latency is sensitive, consider a singleton recognizer. |
| Batch vs sequential recognition | Current design recognizes crops sequentially. ML Kit's `process()` is async — could batch multiple crops in parallel for further speedup. |
