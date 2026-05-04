# Architecture

## Layer Diagram

```
┌─────────────────────────────────────────────────────────┐
│  MainActivity (UI + orchestration)                      │
│  - btn_gallery click → startActivityForResult           │
│  - onActivityResult → URI → Bitmap → byte[] RGBA        │
│  - call predictor.processBitmap(bitmap, outPath)        │
│  - OcrResultParser.parse(json) → OcrResult[]            │
│  - Log each line; Toast saved path                      │
└────────────────────┬────────────────────────────────────┘
                     │ processBitmap(Bitmap, String) : String
┌────────────────────▼────────────────────────────────────┐
│  Native.java (JNI bridge)                               │
│  - processBitmap(): Bitmap → ByteBuffer → byte[]        │
│  - nativeProcessBitmap(ctx, byte[], w, h, path): String │
└────────────────────┬────────────────────────────────────┘
                     │ JNI call
┌────────────────────▼────────────────────────────────────┐
│  Native.cc (JNI implementation)                         │
│  - jbyteArray → uint8_t* via GetByteArrayElements       │
│  - calls Pipeline::RunOcrOnBitmap(pixels, w, h, path)   │
│  - returns jstring (JSON) via cpp_string_to_jstring     │
└────────────────────┬────────────────────────────────────┘
                     │ C++ call
┌────────────────────▼────────────────────────────────────┐
│  pipeline.cc — Pipeline::RunOcrOnBitmap()               │
│  - cv::Mat from raw RGBA pixels (no GL texture read)    │
│  - cv::COLOR_RGBA2BGR → resize to 448×448               │
│  - DetPredictor::Predict() → boxes                      │
│  - for each box: ClsPredictor → RecPredictor            │
│  - Visualization() writes annotated image to outPath    │
│  - serialize boxes + text + scores to JSON string       │
│  - return JSON string                                   │
└─────────────────────────────────────────────────────────┘
```

## Key Design Decisions

### 1. New pipeline entry point, no GL dependency
`RunOcrOnBitmap` constructs `cv::Mat` directly from the pixel buffer, bypassing
`CreateRGBAImageFromGLFBOTexture` (which requires an active GL context and bound FBO).
This means the gallery OCR path can run on any thread, not just the GL thread.

### 2. JSON as the JNI return contract
`nativeProcessBitmap` returns a `jstring` (JSON array). `org.json` (built-in) parses
it on the Java side. No custom JNI object population needed. The schema is:

```json
[
  {
    "box": [[x0,y0],[x1,y1],[x2,y2],[x3,y3]],
    "text": "recognized string",
    "score": 0.95
  }
]
```

### 3. Bitmap pixel extraction
`Bitmap.copyPixelsToBuffer(ByteBuffer)` gives RGBA bytes directly without a copy
through `getPixels()` (which returns packed ARGB ints requiring a second pass).
The `ByteBuffer` backing array is passed as the `byte[]` to the JNI call.

### 4. Existing camera pipeline untouched
`Process_val`, `nativeProcess`, and `Native.process()` are not modified.
The gallery path is a fully parallel code branch.

## File Change Summary (OCR Gallery)

| File | Change type |
|---|---|
| `src/main/cpp/pipeline.h` | Add `RunOcrOnBitmap` declaration |
| `src/main/cpp/pipeline.cc` | Implement `RunOcrOnBitmap` |
| `src/main/cpp/Native.cc` | Add `nativeProcessBitmap` JNI function |
| `src/main/java/.../Native.java` | Add native decl + `processBitmap` wrapper |
| `src/main/java/.../OcrResult.java` | New file |
| `src/main/java/.../OcrResultParser.java` | New file |
| `src/main/res/drawable/btn_gallery*.xml` | New drawables |
| `src/main/res/layout/activity_main.xml` | Add `btn_gallery` button |
| `src/main/java/.../MainActivity.java` | Wire button + result handling |
| `src/test/java/.../OcrResultParserTest.java` | New unit tests |

---

# Question Area Detection — Architecture

## Overview

A two-phase pipeline built on top of the existing OCR Gallery feature.
**Phase 1** is the existing OCR pipeline (unchanged). **Phase 2** groups
OCR line results into semantically complete question regions and renders
them as selectable bounding boxes.

Phase 2 is implemented in **Kotlin only**. All existing Java code is
untouched. Kotlin–Java interop is seamless on Android; no build flag
changes are needed beyond adding the `kotlin-android` plugin to
`build.gradle`.

Phase 2 ships in two versions:
- **v1 (GeometricQuestionDetector)** — pure Kotlin, runs on the JVM,
  no additional native model required. Ships now.
- **v2 (VisualQuestionDetector)** — fine-tuned PicoDet model via
  PaddleLite. Replaces v1 with no interface change. Ships later.

## Full Layer Diagram

```
Input Bitmap (from gallery or camera)
        │
        ▼
┌───────────────────────────────────────────────────────────────┐
│  PHASE 1 — OCR (existing, unchanged)                          │
│                                                               │
│  MainActivity                                                 │
│  └─► Native.processBitmap(bitmap, savedImagePath)             │
│       └─► nativeProcessBitmap [JNI]                           │
│            └─► Pipeline::RunOcrOnBitmap [C++]                 │
│                 det → cls → rec                               │
│                 Visualization() → writes annotated JPEG       │
│                 serialize → JSON string                       │
│  OcrResultParser.parse(json) → OcrResult[]                    │
│  OcrResult { box:int[4][2], text:String, score:float }        │
└───────────────────────────┬───────────────────────────────────┘
                            │ OcrResult[]  +  savedImagePath
                            ▼
┌───────────────────────────────────────────────────────────────┐
│  PHASE 2 — Question Area Detection (new, Kotlin)              │
│                                                               │
│  QuestionDetector (interface)                                 │
│  └─► detect(lines: Array<OcrResult>, source: Bitmap)          │
│       → Array<QuestionRegion>                                 │
│                                                               │
│  v1: GeometricQuestionDetector  ◄── ships now                 │
│  v2: VisualQuestionDetector     ◄── future (PicoDet model)    │
│                                                               │
│  QuestionRegion {                                             │
│      index:     Int                                           │
│      bounds:    RectF   ← axis-aligned, bitmap coords         │
│      confidence: Float                                        │
│      lineBoxes: Array<RectF>  ← per-line envelopes            │
│  }                                                            │
└───────────────────────┬───────────────────────────────────────┘
                        │ Array<QuestionRegion>
             ┌──────────┴──────────┐
             ▼                     ▼
   ┌──────────────────┐   ┌─────────────────────┐
   │  QuestionBoxRenderer  │   UI layer            │
   │  draws on Bitmap  │   │  tap/select by index  │
   │  saves to         │   │  using bounds: RectF   │
   │  savedImagePath   │   └─────────────────────┘
   └──────────────────┘
```

## Key Design Decisions

### 5. Phase 2 runs entirely on the JVM (v1)
`GeometricQuestionDetector` consumes `OcrResult[]` (already in Java heap)
and performs pure geometry + regex. Zero JNI crossings beyond what Phase 1
already does. The C++ native layer is not touched for v1.

### 6. QuestionDetector is a swappable interface
Replacing v1 with v2 requires a single line change at the call site
(the constructor injected into the orchestrator). All downstream code
(renderer, UI) is unaffected because both versions satisfy the same
`QuestionDetector` interface.

### 7. Axis-aligned RectF for question-area boxes
Individual OCR line boxes are 4-point polygons (needed because text can be
skewed). Question-area boxes are larger merged regions that are naturally
more rectangular; axis-aligned `RectF` is accurate enough and gives native
Android hit-test support (`RectF.contains(x, y)`) at no extra cost.

### 8. Kotlin introduced for Phase 2 only
Phase 2 code is written in Kotlin. All existing Java files remain Java.
This avoids touching the existing build while gaining idiomatic Kotlin for
the new geometric logic (extension functions, collection transforms, data
classes).

### 9. Phase 2 renders the definitive output bitmap
Phase 1's C++ `Visualization()` writes an intermediate annotated JPEG.
Phase 2 draws question-area boxes on the original Java `Bitmap` (which
still holds the uncompressed pixels) and overwrites `savedImagePath` with
the final result. This avoids JPEG re-compression artifacts from
loading-then-saving the Phase 1 file.

## File Change Summary (Question Area Detection v1)

| File | Change type | Language |
|---|---|---|
| `build.gradle` | Add `kotlin-android` plugin + stdlib | — |
| `src/main/kotlin/.../QuestionRegion.kt` | New data class | Kotlin |
| `src/main/kotlin/.../QuestionDetector.kt` | New interface | Kotlin |
| `src/main/kotlin/.../GeometricQuestionDetector.kt` | New class (v1) | Kotlin |
| `src/main/kotlin/.../QuestionBoxRenderer.kt` | New renderer | Kotlin |
| `src/main/java/.../MainActivity.java` | Wire Phase 2 after OCR | Java |
| `src/test/kotlin/.../GeometricQuestionDetectorTest.kt` | Unit tests | Kotlin |

---

# Phase 3 — CameraX Capture + Layout Detection API Upload

## Overview

Phase 3 replaces the legacy `CameraSurfaceView` GL-based preview with Android CameraX and
introduces a capture → compress → upload → render pipeline. A Retrofit networking layer
supports both a real API and a deterministic mock so client development can proceed before
the backend is deployed.

Phase 3 is **backward-compatible** with Phase 1 and 2. The gallery OCR flow
(`onActivityResult` → `predictor.processBitmap` → `GeometricQuestionDetector`) is
**untouched**. The new code is a parallel branch triggered exclusively by `btn_shutter`.

## Architecture Decisions

### AD-1 — Full AndroidX migration
All `android.support.*` replaced at the source level. `android.useAndroidX=true` in
`gradle.properties`. Jetifier (`android.enableJetifier=true`) is enabled to remap any
residual support-library classes in transitive JARs (e.g. the compiled `common` module).

### AD-2 — minSdkVersion 15 → 21
CameraX hard requirement. No user-visible behavior change for the supported device set.

### AD-3 — Live OCR preview removed
`CameraSurfaceView.onTextureChanged` relied on OpenGL texture IDs not exposed by CameraX
`Preview`. Introducing a new C++ entry point to consume `ImageProxy` YUV frames is out of
scope. The live OCR overlay is commented out; `tvStatus` is repurposed for capture/upload
status feedback.

### AD-4 — Activity → AppCompatActivity
Required to implement `LifecycleOwner` for `ProcessCameraProvider.bindToLifecycle`.
Title bar hidden via `getSupportActionBar().hide()` (AppCompat-safe replacement for
`requestWindowFeature(FEATURE_NO_TITLE)`).

### AD-5 — Capture via temp JPEG + compress + PNG
CameraX `ImageCapture` saves a raw full-resolution JPEG to the cache dir. It is loaded
as `Bitmap`, scaled to ≤ 1024 × 1024 (maintaining aspect ratio), and saved as PNG to
DCIM. The temp JPEG is deleted. This avoids manual YUV→RGB conversion from `ImageProxy`.

### AD-6 — Mock-first networking
`ApiClient` contains a `USE_MOCK` constant. When `true`, `MockApiService` is substituted
for the Retrofit-backed real service. Set `USE_MOCK = false` in `ApiClient.kt` once the
API at `http://10.10.10.246:8000/` is live.

### AD-7 — Box drawing in Kotlin, invoked from Java
`BoxRenderer` (Kotlin `object`) draws `bbox` rectangles and class labels from the API
response onto a mutable Bitmap copy, saving the result as `<timestamp>_output.png`.
Invoked from Java via Kotlin–Java interop (`BoxRenderer.INSTANCE.drawAndSave(...)`).

## Capture Path Layer Diagram

```
btn_shutter click
      │
      ▼
ImageCapture.takePicture()  [background thread via cameraExecutor]
      │
      ▼
raw JPEG → cache/<timestamp>_tmp.jpg
      │
      ▼
compressToMax(1024, 1024)  →  PNG  →  DCIM/<timestamp>.png
      │
      ▼
uploadAndDraw(bitmap, savedPath)
      │
      ├─ real:  Retrofit → POST http://10.10.10.246:8000/predict (multipart)
      └─ mock:  MockApiService → ImmediateCall<DetectionResponse>
      │
      ▼
DetectionResponse { detections: List<DetectionObject(className, bbox, confidence)> }
      │
      ▼
BoxRenderer.drawAndSave(bitmap, detections, outputPath)
      │  draws RectF boxes + "ClassName confidence%" labels on Bitmap copy
      ▼
DCIM/<timestamp>_output.png
```

## File Change Summary (Phase 3)

| File | Change | Language |
|---|---|---|
| `gradle/libs.versions.toml` | Replace support → AndroidX; add CameraX/Retrofit/OkHttp/Gson | — |
| `app/build.gradle` | compileSdk 33, minSdk 21, targetSdk 33; update deps | Groovy |
| `gradle.properties` | `android.useAndroidX=true`, `android.enableJetifier=true` | — |
| `activity_main.xml` | ConstraintLayout → AndroidX; `CameraSurfaceView` → `PreviewView` | XML |
| `MainActivity.java` | `Activity` → `AppCompatActivity`; CameraX Preview + ImageCapture; capture/upload | Java |
| `DetectionBbox.kt` | New data class | Kotlin |
| `DetectionObject.kt` | New data class | Kotlin |
| `DetectionResponse.kt` | New data class | Kotlin |
| `ApiService.kt` | New Retrofit interface | Kotlin |
| `MockApiService.kt` | New mock implementation | Kotlin |
| `ApiClient.kt` | New singleton with `USE_MOCK` flag | Kotlin |
| `BoxRenderer.kt` | New renderer | Kotlin |
