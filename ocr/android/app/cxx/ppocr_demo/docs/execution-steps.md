# Execution Steps — CameraX Capture + Layout Detection API Upload

## S1 — Build Configuration (2026-04-23)

**Files changed:** `gradle/libs.versions.toml`, `app/build.gradle`, `gradle.properties`

**What:** Replaced all `android.support.*` library coordinates with AndroidX equivalents.
Added CameraX 1.2.3, Retrofit 2.9.0, OkHttp 4.12.0, Gson 2.10.1.
Bumped `compileSdkVersion` 28→33, `minSdkVersion` 15→21, `targetSdkVersion` 28→33.
Added `android.useAndroidX=true` and `android.enableJetifier=true` to `gradle.properties`.

**Why `compileSdk 33`:** Highest SDK supported by the project's existing AGP 7.4.2.
CameraX 1.2.3 is the latest stable release compatible with compileSdk 33.

**Why `minSdk 21`:** CameraX hard requirement; all supported devices are already API 21+.

**Why Jetifier alongside full source migration:** The compiled `common` module JAR
(provides `Utils`) may internally reference support library classes. Jetifier remaps those
at build time without requiring source access to the common module.

---

## S2 — Layout & AndroidX Migration (2026-04-23)

**Files changed:** `activity_main.xml`, `MainActivity.java`

**What:**
- `activity_main.xml`: root tag changed to `androidx.constraintlayout.widget.ConstraintLayout`;
  `CameraSurfaceView` replaced with `androidx.camera.view.PreviewView` (`id=pv_preview`).
- `MainActivity.java`:
  - `Activity` → `AppCompatActivity` (required `LifecycleOwner` for CameraX).
  - All `android.support.*` imports replaced with `androidx.*` equivalents.
  - `implements CameraSurfaceView.OnTextureChangedListener` removed.
  - `onTextureChanged()` block commented out (see AD-3 in architecture.md).
  - `requestWindowFeature(FEATURE_NO_TITLE)` replaced with `getSupportActionBar().hide()`.
  - `svPreview`, `lastFrameIndex`, `lastFrameTime`, `savedImagePath` fields removed.
  - `pvPreview` (`PreviewView`) field added.
  - `imageCapture`, `cameraExecutor`, `lensFacing` fields added for CameraX.
  - `onResume()` / `onPause()` stripped of `CameraSurfaceView` calls.
  - **`onActivityResult()` preserved verbatim** (Phase 1 + 2 gallery OCR path).

---

## S3 — Networking Layer (2026-04-23)

**Files created:** `DetectionBbox.kt`, `DetectionObject.kt`, `DetectionResponse.kt`,
`ApiService.kt`, `MockApiService.kt`, `ApiClient.kt`

**What:**
- Data classes map the API contract from `docs/api-contract.md`:
  `DetectionResponse.detections[].bbox.{x1,y1,x2,y2}`.
- `ApiService`: single `@Multipart @POST("predict")` method returning `Call<DetectionResponse>`.
- `MockApiService`: `object` implementing `ApiService`; returns an `ImmediateCall` that
  calls `callback.onResponse()` synchronously with a hardcoded three-detection response.
- `ApiClient`: `object` with `USE_MOCK = true`; flip to `false` when real API is ready.
  Real path uses OkHttp + `HttpLoggingInterceptor` (BODY level) + Gson converter.

**Decision — `ImmediateCall` instead of `retrofit-mock`:** Adding the `retrofit:retrofit-mock`
artifact just for `Calls.response()` is unnecessary. A private 8-line `ImmediateCall<T>`
class satisfies the same contract without a new dependency.

---

## S4 — Camera, Capture & Compress (2026-04-23)

**Files changed:** `MainActivity.java`

**What:**
- `startCamera()`: obtains `ProcessCameraProvider` and calls `bindCameraUseCases()`.
- `bindCameraUseCases()`: binds `Preview` (→ `pvPreview.getSurfaceProvider()`) and
  `ImageCapture` (MINIMIZE_LATENCY) to the activity lifecycle.
- `switchCamera()`: toggles `lensFacing`, calls `startCamera()` to rebind.
- `takePicture()`:
  1. CameraX saves a raw JPEG to `getCacheDir()/<timestamp>_tmp.jpg`.
  2. Loaded as `Bitmap`; temp file deleted.
  3. `compressToMax(1024, 1024)`: scales down proportionally if either dimension > 1024.
  4. `saveAsPng()`: saves compressed bitmap to `DCIM/<timestamp>.png`.
  5. Toast "Saved to <path>".
  6. Calls `uploadAndDraw(bitmap, savedPath)` on the same background thread.

**Decision — OutputFileOptions instead of ImageProxy:** CameraX's `OnImageCapturedCallback`
returns `ImageProxy` in YUV_420_888; converting that to `Bitmap` requires manual YUV→RGB
conversion. `OutputFileOptions` to a temp file sidesteps this without any native code.

---

## S5 — Upload & Box Render (2026-04-23)

**Files changed:** `MainActivity.java`
**Files created:** `BoxRenderer.kt`

**What:**
- `uploadAndDraw()`: wraps the saved PNG as `MultipartBody.Part("file", ...)` and calls
  `ApiClient.getService().predict(part).enqueue(...)`. On success, derives the output path
  by inserting `_output` before the file extension and calls `BoxRenderer.drawAndSave()`.
- `BoxRenderer.drawAndSave()`: copies the bitmap to a mutable `ARGB_8888` canvas,
  draws a 4dp red `STROKE` rectangle per detection using `bbox.{x1,y1,x2,y2}` (pixel
  coordinates as returned by the API), draws the class name + rounded confidence above
  each box, saves as PNG, recycles the mutable copy.
- `outputPathFor()`: pure string helper — inserts `_output` before the last `.` in the
  filename (e.g. `2024_01_01_10_00_00.png` → `2024_01_01_10_00_00_output.png`).
