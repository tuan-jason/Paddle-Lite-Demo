# OCR Gallery Feature — Task List

## Implementation Tasks

- [x] T1  C++    — Declare `Pipeline::RunOcrOnBitmap()` in `pipeline.h`
- [x] T2  C++    — Implement `Pipeline::RunOcrOnBitmap()` in `pipeline.cc` (cv::Mat from pixels, RGBA→BGR, resize, det→cls→rec, JSON serialize)
- [x] T3  C++    — Add `nativeProcessBitmap` JNI function in `Native.cc`
- [x] T4  Java   — Add `nativeProcessBitmap` native declaration + `processBitmap(Bitmap, String)` wrapper in `Native.java`
- [x] T5  Java   — Create `OcrResult.java`
- [x] T6  Java   — Create `OcrResultParser.java`
- [ ] T7  Tests  — ~~Write `OcrResultParserTest` unit tests~~ *(deferred)*
- [x] T8  UI     — Add `btn_gallery` drawable XMLs to `res/drawable/`
- [x] T9  UI     — Add `btn_gallery` `ImageButton` to `activity_main.xml` (centered between `btn_switch` and `btn_shutter`)
- [x] T10 Java   — Wire `btn_gallery` in `MainActivity`: field declaration, `initView()`, `onClick()`
- [x] T11 Java   — `onActivityResult`: URI → `Bitmap` → `byte[]`, call `predictor.processBitmap()`
- [x] T12 Java   — Parse `OcrResult[]`, log each line via `Log.d("tuancoltech", ...)`, show `Toast` with saved image path

---

# CameraX Capture + Layout Detection API Upload — Task List

## Approved Decisions
- **D1** Full AndroidX migration (all `android.support.*` → `androidx.*`)
- **D2** `minSdkVersion` bumped 15 → 21 (CameraX hard requirement)
- **D3** Live OCR preview dropped; `onTextureChanged` commented out; CameraX shows plain feed
- **D4** Dependencies added: CameraX 1.2.3, Retrofit 2.9.0, OkHttp 4.12.0, Gson 2.10.1

## Build & Setup
- [x] S1a — `gradle/libs.versions.toml`: replace support library entries with AndroidX; add CameraX, Retrofit, OkHttp, Gson
- [x] S1b — `app/build.gradle`: bump compileSdk/minSdk/targetSdk; update deps
- [x] S1c — `gradle.properties`: add `android.useAndroidX=true` + `android.enableJetifier=true`

## Layout & AndroidX Migration
- [x] S2a — `activity_main.xml`: ConstraintLayout → AndroidX; `CameraSurfaceView` → `PreviewView (id=pv_preview)`
- [x] S2b — `MainActivity.java`: `Activity` → `AppCompatActivity`; all `android.support.*` → `androidx.*`; remove `OnTextureChangedListener`; comment out `onTextureChanged`

## Networking Layer (Kotlin)
- [x] S3a — `DetectionBbox.kt`, `DetectionObject.kt`, `DetectionResponse.kt` data classes
- [x] S3b — `ApiService.kt` — Retrofit `@Multipart @POST("predict")` interface
- [x] S3c — `MockApiService.kt` — deterministic mock implementation
- [x] S3d — `ApiClient.kt` — singleton with `USE_MOCK` flag; toggles real vs mock

## Camera & Capture
- [x] S4a — `MainActivity.java`: wire CameraX `Preview` + `ImageCapture`; `startCamera()` / `switchCamera()` / `bindCameraUseCases()`
- [x] S4b — `btn_shutter`: capture → compress max 1024×1024 (keep ratio) → save PNG to DCIM

## Upload & Box Render
- [x] S5a — `MainActivity.java`: `uploadAndDraw()` — Retrofit multipart POST; on success hand off to BoxRenderer
- [x] S5b — `BoxRenderer.kt` — draws `bbox` rects + label on mutable Bitmap; saves `_output` PNG
