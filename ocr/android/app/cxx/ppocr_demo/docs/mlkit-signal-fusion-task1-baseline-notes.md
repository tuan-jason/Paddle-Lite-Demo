# MlKitSignalFusionQuestionDetector - Task 1 Baseline Notes

Date: 2026-04-29

This note captures Task 1 outputs: detector entry points, baseline fixture coverage, and the target class/file layout.

## 1) Detector Entry Points and Call Graph

Gallery OCR flow:

1. `MainActivity.onActivityResult(...)`
2. `GalleryOcrRouter.processBitmap(...)`
3. OCR source branch:
   - ML Kit when `OcrFeatureFlags.USE_MLKIT_OCR = true` and SDK >= M
   - Paddle otherwise
4. JSON parse via `OcrResultParser.parse(...)`
5. Question segmentation via `MlKitSignalFusionQuestionDetector.detect(...)`
6. Render via `QuestionBoxRenderer.render(...)`

Integration points:

- OCR routing flag: `app/src/main/kotlin/com/baidu/paddle/lite/demo/ppocr_demo/OcrFeatureFlags.kt`
- OCR router: `app/src/main/kotlin/com/baidu/paddle/lite/demo/ppocr_demo/GalleryOcrRouter.kt`
- Detector selection: `app/src/main/java/com/baidu/paddle/lite/demo/ppocr_demo/MainActivity.java`

## 2) Baseline Behavior Snapshot (Reproducible)

Baseline fixture test class:

- `MlKitSignalFusionQuestionDetectorBaselineTest`

Fixtures:

- `baselineFixture_numberedQuestions_splitIntoTwoRegions`
  - Expected regions: `2`
  - Expected bounds:
    - R0: `[20,100,700,190]`
    - R1: `[22,320,690,410]`
- `baselineFixture_largeVerticalGap_splitsNarrativeBlocks`
  - Expected regions: `2`
  - Expected bounds:
    - R0: `[30,120,760,210]`
    - R1: `[32,520,760,610]`
- `baselineFixture_japaneseImperativeInJaMode_splitsIntoTwo`
  - Expected regions: `2`
  - Expected bounds:
    - R0: `[40,200,700,240]`
    - R1: `[40,248,700,288]`

Run command:

```bash
./gradlew :app:testDebugUnitTest --tests com.baidu.paddle.lite.demo.ppocr_demo.MlKitSignalFusionQuestionDetectorBaselineTest
```

## 3) Target Class/File Layout (Task-1 Scaffold)

Created:

- `app/src/main/kotlin/com/baidu/paddle/lite/demo/ppocr_demo/MlKitSignalFusionQuestionDetector.kt`
  - Current behavior: delegates to `SignalFusionQuestionDetector` to preserve baseline.
- `app/src/test/kotlin/com/baidu/paddle/lite/demo/ppocr_demo/MlKitSignalFusionQuestionDetectorBaselineTest.kt`
  - Captures baseline outputs for regression during future ML Kit-specific implementation.

MainActivity constraint applied:

- Only detector swap in gallery flow:
  - `new SignalFusionQuestionDetector()` -> `new MlKitSignalFusionQuestionDetector()`

