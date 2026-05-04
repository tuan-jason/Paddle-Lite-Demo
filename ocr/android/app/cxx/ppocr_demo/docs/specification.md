# OCR Gallery Feature — Specification

## Feature Summary

Add a "Gallery" `ImageButton` centered between `btn_switch` and `btn_shutter` in the bottom bar. On tap, it launches the device gallery picker. On image selection, the app decodes the image, runs PaddleOCR via a new JNI path, and:

- Saves an annotated image (bounding boxes drawn) to external storage.
- Shows a `Toast` with the saved image path.
- Prints each recognized text line and confidence score to `Log.d("tuancoltech", ...)`.

## Constraints

- `minSdkVersion 15`, `targetSdkVersion 28` — use `startActivityForResult` / `onActivityResult`; no `ActivityResultLauncher`.
- No new Gradle dependencies — `org.json` is built into Android.
- Existing `Process_val` / `nativeProcess` (camera pipeline) are **not modified**.
- All C++ changes are **additive only**.
- Pixel format bridge: Android `Bitmap.ARGB_8888` → `byte[]` RGBA → C++ `uint8_t*`.

## User Flow

1. User taps `btn_gallery`.
2. System gallery picker opens (`ACTION_GET_CONTENT`, `image/*`).
3. User selects an image.
4. App resolves URI → decodes to `Bitmap` → extracts RGBA `byte[]`.
5. `predictor.processBitmap(bitmap, savedImagePath)` is called.
6. C++ runs det → cls → rec pipeline on the pixels.
7. Annotated image (boxes drawn) is written to `savedImagePath`.
8. JSON result is returned to Java.
9. `OcrResultParser.parse(json)` produces `OcrResult[]`.
10. Each result is logged: `Log.d("tuancoltech", "line N: <text> (score)")`.
11. `Toast` shows: `"Result saved to <savedImagePath>"`.

## Out of Scope

- Displaying the annotated image inside the app UI.
- Modifying the camera real-time pipeline to return structured results.
- Support for multiple image selection.
