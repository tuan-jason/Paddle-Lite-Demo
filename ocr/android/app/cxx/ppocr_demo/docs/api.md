# OCR Gallery Feature — API Reference

## C++ API

### `Pipeline::RunOcrOnBitmap` (`pipeline.h` / `pipeline.cc`)

```cpp
std::string RunOcrOnBitmap(const uint8_t* pixels, int width, int height,
                           const std::string& savedImagePath);
```

| Parameter | Type | Description |
|---|---|---|
| `pixels` | `const uint8_t*` | RGBA pixel buffer, 8-bit per channel, row-major, no padding. Total size = `width * height * 4` bytes. |
| `width` | `int` | Image width in pixels. |
| `height` | `int` | Image height in pixels. |
| `savedImagePath` | `const std::string&` | Absolute path where the annotated output image (BGR JPEG) is written. Pass `""` to skip saving. |

**Returns:** JSON array string. Returns `""` on failure (null pixel pointer or inference error).

**JSON Schema:**
```json
[
  {
    "box": [[x0,y0], [x1,y1], [x2,y2], [x3,y3]],
    "text": "recognized string",
    "score": 0.95
  }
]
```

**Notes:**
- Box coordinates are in the 448×448 resized image space, not the original image space.
- Box point order: top-left → top-right → bottom-right → bottom-left.
- Results are ordered from last detected box to first (matching the existing `Process_val` iteration order).
- `score` is the average CTC confidence over all characters in the recognized text.

---

## JNI API

### `nativeProcessBitmap` (`Native.cc`)

```
Java signature:
  static native String nativeProcessBitmap(long ctx, byte[] rgbaPixels,
                                           int width, int height,
                                           String savedImagePath)

JNI symbol:
  Java_com_baidu_paddle_lite_demo_ppocr_1demo_Native_nativeProcessBitmap
```

| Parameter | JNI type | Description |
|---|---|---|
| `ctx` | `jlong` | Pipeline context pointer returned by `nativeInit`. |
| `rgbaPixels` | `jbyteArray` | RGBA pixel bytes from `Bitmap.copyPixelsToBuffer`. |
| `width` | `jint` | Bitmap width. |
| `height` | `jint` | Bitmap height. |
| `savedImagePath` | `jstring` | Absolute path for annotated output image. |

**Returns:** `jstring` — JSON array string, or `null` if `ctx == 0`.

---

## Java API

### `Native.nativeProcessBitmap` (`Native.java`)

```java
public static native String nativeProcessBitmap(
    long ctx, byte[] rgbaPixels, int width, int height, String savedImagePath);
```

Raw JNI declaration. Prefer the `processBitmap` wrapper below.

---

### `Native.processBitmap` (`Native.java`)

```java
public String processBitmap(Bitmap bitmap, String savedImagePath)
```

| Parameter | Type | Description |
|---|---|---|
| `bitmap` | `Bitmap` | Any `Bitmap` configuration; internally converted to `ARGB_8888` if needed. |
| `savedImagePath` | `String` | Absolute path for the annotated output image. |

**Returns:** JSON string, or `null` if the native context is not initialized.

---

### `OcrResult` (`OcrResult.java`)

```java
public final class OcrResult {
    public final int[][] box;    // [4][2]: four corner points (x,y)
    public final String text;    // recognized text string
    public final float score;    // confidence score [0.0, 1.0]

    public OcrResult(int[][] box, String text, float score)
}
```

---

### `OcrResultParser.parse` (`OcrResultParser.java`)

```java
public static OcrResult[] parse(String json)
```

| Parameter | Description |
|---|---|
| `json` | JSON string returned by `nativeProcessBitmap` / `processBitmap`. |

**Returns:** Array of `OcrResult`. Never returns `null`; returns an empty array on `null` input, empty JSON, or parse error.

**Contract:** Must not throw under any input.

---

# Question Area Detection — Data Contracts

## `QuestionRegion` (`QuestionRegion.kt`)

```kotlin
data class QuestionRegion(
    val index: Int,               // 0-based question order in reading sequence
    val bounds: RectF,            // axis-aligned envelope in bitmap pixel coordinates
    val confidence: Float,        // 0.0–1.0; composite of contributing line scores
    val lineBoxes: Array<RectF>   // axis-aligned envelopes of contributing OcrResult lines
)
```

**Coordinate space:** original bitmap pixel space. `DetPredictor::Postprocess`
calls `FilterTagDetRes` which divides all box coordinates by the internal
resize ratios (`ratio_h`, `ratio_w`) before returning, mapping them back to
the original image dimensions. No scaling is needed when drawing on the
source `Bitmap`.

**`bounds` derivation:** axis-aligned union of all `lineBoxes`. Each
`lineBox` is the axis-aligned envelope of the corresponding `OcrResult.box`
4-point polygon: `left=min(xN)`, `top=min(yN)`, `right=max(xN)`,
`bottom=max(yN)`.

---

## `QuestionDetector` (`QuestionDetector.kt`)

```kotlin
interface QuestionDetector {
    fun detect(lines: Array<OcrResult>, source: Bitmap): Array<QuestionRegion>
}
```

| Parameter | Description |
|---|---|
| `lines` | `OcrResult[]` from `OcrResultParser.parse()`. May be empty. |
| `source` | The original input `Bitmap` (used by v2 visual detector; v1 ignores it). |

**Returns:** Array of `QuestionRegion`, ordered by `index` (top-to-bottom
reading order). Returns an empty array when no questions are detected.

**Contract:** Must not throw. Must not mutate `lines` or `source`.

---

## `GeometricQuestionDetector` (v1) (`GeometricQuestionDetector.kt`)

```kotlin
class GeometricQuestionDetector(
    val gapMultiplier: Float = 1.5f,       // gap threshold = gapMultiplier × avgLineHeight
    val handwritingScoreThreshold: Float = 0.80f  // scores below this use geometry-only path
) : QuestionDetector
```

No external dependencies. All parameters have defaults suitable for
standard exam-sheet documents.

---

## `VisualQuestionDetector` (v2, future) (`VisualQuestionDetector.kt`)

```kotlin
class VisualQuestionDetector(
    private val modelPath: String,
    private val cpuThreadNum: Int = 2,
    private val cpuPowerMode: String = "LITE_POWER_HIGH"
) : QuestionDetector
```

Backed by a fine-tuned PicoDet model loaded via PaddleLite. Replaces
`GeometricQuestionDetector` at the injection site; interface is identical.

---

## `QuestionBoxRenderer` (`QuestionBoxRenderer.kt`)

```kotlin
object QuestionBoxRenderer {
    fun render(
        source: Bitmap,
        regions: Array<QuestionRegion>,
        savedImagePath: String
    )
}
```

| Parameter | Description |
|---|---|
| `source` | Original uncompressed `Bitmap`. A mutable copy is created internally. |
| `regions` | Question regions to draw. |
| `savedImagePath` | Absolute path; output JPEG is written here, overwriting any existing file. |

Draws each `QuestionRegion.bounds` as a blue rectangle (stroke 4 px) with
a `"Q{index+1}"` label at the top-left corner. Saves as JPEG quality 90.
