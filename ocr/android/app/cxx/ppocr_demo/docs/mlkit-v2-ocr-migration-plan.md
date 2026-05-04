# ML Kit v2 OCR Migration Plan (Japanese) — JSON Contract Preserved

## Objective

Replace `predictor.processBitmap(...)` (PaddleLite OCR) in the gallery pipeline with ML Kit Text Recognition v2 (Japanese), while preserving the exact downstream JSON structure:

```json
[{"box":[[x,y],[x,y],[x,y],[x,y]],"text":"...","score":0.0}]
```

This ensures parsing and question detection flow from `MainActivity` line 384 onward remains unchanged.

## Invariants (must stay true)

- [ ] JSON field names remain exactly: `box`, `text`, `score`.
- [ ] `box` remains 4 points ordered `TL, TR, BR, BL`.
- [ ] `score` remains a float in `[0,1]` (fallback rule defined for missing confidence).
- [ ] Existing `OcrResultParser.parse(json)` and `SignalFusionQuestionDetector.detect(...)` work without code changes.

## Execution Tasks (small, testable, implementable)

### Phase 1: Baseline and Contract Lock

- [x] **T1. Add baseline latency logging for current Paddle path**
  - Deliverable: structured logs for `processBitmap` duration.
  - Implemented:
    - `Native.processBitmap(...)` records `process_bitmap_ms` around `nativeProcessBitmap(...)`.
    - Tag `OcrLatencyBaseline` emits JSON logs:
      - sample event: `{"event":"ocr_latency_sample", ...}`
      - rolling summary event (latest 20 samples): `{"event":"ocr_latency_summary", ...}` with median/p90.
  - Test: run 20 sample images, export median/p90 timings.
    - Suggested corpus: `/Users/itabluebelt/Downloads/kanagawa_counted/math` and `/Users/itabluebelt/Downloads/kanagawa_counted/science`
    - Command:
      - `adb logcat -c`
      - `adb logcat -s OcrLatencyBaseline:I`
    - After the 20th run, capture the latest `ocr_latency_summary` line.

- [x] **T2. Add JSON contract golden tests**
  - Deliverable: tests validating parser behavior against representative JSON payloads.
  - Implemented:
    - Added `app/src/test/java/com/baidu/paddle/lite/demo/ppocr_demo/OcrResultParserTest.java`.
    - Covered valid Japanese/mixed-text payload mapping (`box`, `text`, `score`).
    - Covered malformed/null/empty/missing-field/invalid-box fallback to empty result array.
  - Verification:
    - `./gradlew :app:testDebugUnitTest --tests com.baidu.paddle.lite.demo.ppocr_demo.OcrResultParserTest`
  - Test: pass cases with normal/mixed Japanese text and malformed payload fallback behavior.

### Phase 2: ML Kit Integration (No Pipeline Break)

- [x] **T3. Add ML Kit v2 Japanese dependency (bundled model)**
  - Deliverable: Gradle dependency for Japanese recognizer and successful build.
  - Implemented:
    - Added version catalog entry for `com.google.mlkit:text-recognition-japanese:16.0.1`.
    - Added `implementation libs.mlkit.text.recognition.japanese` to `app/build.gradle`.
  - Verification:
    - `./gradlew :app:compileDebugKotlin --console=plain --stacktrace`
    - `./gradlew :app:compileDebugJavaWithJavac --console=plain --stacktrace`
  - Test: clean build succeeds on CI/local debug variant.

- [x] **T4. Implement `MlKitOcrEngine` abstraction**
  - Deliverable: interface/class that takes `Bitmap` and returns OCR line objects (`box`, `text`, `score`).
  - Implemented:
    - Added `MlKitOcrEngine` Java-friendly async interface.
    - Added `JapaneseMlKitOcrEngine` backed by ML Kit v2 Japanese `TextRecognizer`.
    - Added `MlKitOcrLineMapper` to convert ML Kit lines into existing `OcrResult` line objects.
    - Preserved box order as `TL, TR, BR, BL`, with bounding-box fallback if corner points are missing.
    - Clamped scores to `[0,1]`, using `0.0f` when ML Kit confidence is unavailable.
  - Verification:
    - `./gradlew :app:testDebugUnitTest --tests com.baidu.paddle.lite.demo.ppocr_demo.MlKitOcrLineMapperTest --console=plain --stacktrace`
    - `./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac --console=plain --stacktrace`
  - Test: unit test with mocked recognizer response to verify mapping logic.

- [x] **T5. Implement JSON adapter preserving legacy schema**
  - Deliverable: serializer that emits exact legacy JSON shape consumed by `OcrResultParser`.
  - Implemented:
    - Added `OcrResultJsonAdapter.toJson(OcrResult[])`.
    - Emits the exact legacy field order: `box`, `text`, `score`.
    - Escapes text via JSON quoting and preserves the 4-point `TL, TR, BR, BL` box order.
    - Normalizes invalid confidence values to `[0,1]` and skips invalid/null OCR rows.
  - Verification:
    - `./gradlew :app:testDebugUnitTest --tests com.baidu.paddle.lite.demo.ppocr_demo.OcrResultJsonAdapterTest --console=plain --stacktrace`
    - `./gradlew :app:testDebugUnitTest --tests com.baidu.paddle.lite.demo.ppocr_demo.OcrResultJsonAdapterTest --tests com.baidu.paddle.lite.demo.ppocr_demo.OcrResultParserTest --tests com.baidu.paddle.lite.demo.ppocr_demo.MlKitOcrLineMapperTest --console=plain --stacktrace`
  - Test: schema snapshot test (field names/order/number formats/escaping).

- [x] **T6. Wire ML Kit behind feature flag (default OFF)**
  - Deliverable: runtime switch `use_mlkit_ocr` to choose Paddle vs ML Kit.
  - Implemented:
    - Added `OcrFeatureFlags.USE_MLKIT_OCR = false`.
    - Added `GalleryOcrRouter.processBitmap(...)` as the only call-site switch.
    - Added `BlockingJapaneseMlKitOcrEngine` to preserve the existing background-thread synchronous flow.
    - Changed only the gallery OCR call site in `MainActivity`.
    - Keeps Paddle as the default path; ML Kit path still serializes through `OcrResultJsonAdapter`.
  - Verification:
    - `./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac --console=plain --stacktrace`
    - `./gradlew :app:testDebugUnitTest --tests com.baidu.paddle.lite.demo.ppocr_demo.GalleryOcrRouterTest --tests com.baidu.paddle.lite.demo.ppocr_demo.OcrResultJsonAdapterTest --tests com.baidu.paddle.lite.demo.ppocr_demo.OcrResultParserTest --tests com.baidu.paddle.lite.demo.ppocr_demo.MlKitOcrLineMapperTest --console=plain --stacktrace`
  - Test: both code paths execute successfully; outputs parse into non-crashing `OcrResult[]`.

### Phase 3: Functional Correctness and Quality

- [ ] **T7. Validate geometric compatibility with detector**
  - Deliverable: check that `box` orientation/order and line coverage work with `SignalFusionQuestionDetector`.
  - Test: compare question-region outputs on 50 labeled images (Paddle vs ML Kit).

- [ ] **T8. Define confidence fallback policy**
  - Deliverable: deterministic rule when ML Kit confidence is unavailable (e.g., `0.0f`).
  - Test: forced-null/zero-confidence scenario still yields valid JSON and stable detector behavior.

- [ ] **T9. Japanese parity evaluation**
  - Deliverable: corpus-level comparison report (CER/WER proxy + question-region F1).
  - Test: acceptance thresholds met or gaps documented with examples.

### Phase 4: Performance Validation and Rollout

- [ ] **T10. Latency benchmark (A/B)**
  - Deliverable: side-by-side timing report on same device set and image corpus.
  - Test: ML Kit median and p90 latency improvement quantified.

- [ ] **T11. Memory/ANR regression check**
  - Deliverable: memory footprint and crash/ANR sanity report under batch runs.
  - Test: no new OOM/ANR events in stress run.

- [ ] **T12. Promote ML Kit flag default ON (if acceptance met)**
  - Deliverable: config change + rollback toggle retained.
  - Test: smoke tests pass on target devices.

## Suggested Acceptance Gates

- [ ] End-to-end JSON contract unchanged and parser-compatible.
- [ ] Japanese OCR quality at or above agreed parity threshold on production-like corpus.
- [ ] Question-region detection quality does not regress beyond agreed tolerance.
- [ ] End-to-end latency materially improved vs Paddle baseline.

## Notes

- Keep Paddle implementation available behind fallback flag until rollout is proven stable.
- Do not change downstream parser/detector contracts during migration.
