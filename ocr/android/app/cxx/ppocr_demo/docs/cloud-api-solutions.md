# Cloud/API Solutions for Question Detection

## Problem Context

Given a bitmap input, detect exam questions and return 4-corner bounding box coordinates (left, top, right, bottom) in the input bitmap's coordinate system, so boxes can be re-drawn on the same bitmap (see `MainActivity` line ~406, `QuestionBoxRenderer.render()`).

All cloud options below return bounding geometry that can be mapped to the existing `OcrResult.box` format (`int[4][2]`: TL, TR, BR, BL) and fed into `SignalFusionQuestionDetector` for question region grouping.

---

## Option 1: Google Cloud Vision API (Document Text Detection) — Recommended

**Endpoint**: `POST https://vision.googleapis.com/v1/images:annotate`
**Feature**: `DOCUMENT_TEXT_DETECTION`

**What it returns**:
- Full text hierarchy: pages → blocks → paragraphs → words → symbols
- Each element has `boundingBox` with 4 vertices (polygon corners) in the input image's pixel coordinate system
- Confidence scores at every level

**How to get question boxes**:
- Use the block/paragraph-level bounding boxes as initial regions.
- Apply `SignalFusionQuestionDetector` logic on the block/paragraph text + bounding boxes.
- The `boundingBox.vertices` give `[{x,y}, {x,y}, {x,y}, {x,y}]` — directly mappable to `OcrResult.box`.

**Latency**: ~500ms–1.5s (network dependent)
**Cost**: $1.50 per 1,000 images (first 1,000/month free)
**Japanese support**: Excellent

**Integration sketch**:
```kotlin
val response = cloudVisionApi.annotateImage(bitmap, "DOCUMENT_TEXT_DETECTION")
val ocrResults = response.blocks.flatMap { it.paragraphs }.map { para ->
    OcrResult(
        box = para.boundingBox.vertices.toBoxArray(), // 4 corners in bitmap coords
        text = para.text,
        score = para.confidence
    )
}
val regions = SignalFusionQuestionDetector().detect(ocrResults, bitmap)
// regions[i].bounds gives (left, top, right, bottom) for drawing
```

---

## Option 2: AWS Textract

**Endpoint**: `POST textract:AnalyzeDocument` or `DetectDocumentText`

**What it returns**:
- `LINE` and `WORD` blocks with `BoundingBox` (normalized 0–1 coordinates: `Left`, `Top`, `Width`, `Height`)
- Also provides `Polygon` with 4 corner points (normalized)

**How to get question boxes**:
- Convert normalized coordinates to bitmap pixels: `x_px = Left * bitmap.width`, etc.
- Map to `OcrResult.box` format.
- Run through `SignalFusionQuestionDetector`.

**Latency**: ~1–3s
**Cost**: $1.50 per 1,000 pages
**Japanese support**: Supported

---

## Option 3: Azure AI Vision (Read API)

**Endpoint**: `POST /vision/v3.2/read/analyze`

**What it returns**:
- Pages → lines → words, each with `boundingBox` as 8 floats `[x1,y1,x2,y2,x3,y3,x4,y4]` in pixel coordinates

**How to get question boxes**:
- Parse the 8-float array into `int[4][2]` box format directly.
- Feed into `SignalFusionQuestionDetector`.

**Latency**: ~1–2s
**Cost**: $1.00 per 1,000 images (free tier: 5,000/month)
**Japanese support**: Excellent

---

## Option 4: Custom Backend (Existing API Pattern)

The project already has a `POST /predict` endpoint pattern (see `ApiService.kt`, `ApiClient.kt`, `MockApiService.kt`). A server-side pipeline could be deployed:

**Server**: Run full PaddleOCR (Python, not mobile-lite) + signal fusion logic server-side.

**What it returns**: Directly return `List<QuestionRegion>` with `bounds` in bitmap coordinates.

**Advantages**:
- Full PaddleOCR (not lite) is significantly more accurate than the mobile version.
- Can use larger models (PP-OCRv4, etc.).
- Server-side processing is not constrained by mobile hardware.
- Retrofit + mock infrastructure already exists in the codebase.

**Integration**: Reuse existing `ApiClient`/`ApiService` pattern, add a new endpoint that returns question regions directly.

---

## Comparison

| Option | Accuracy | Latency | Cost | Japanese | Effort |
|---|---|---|---|---|---|
| Google Cloud Vision | ★★★★★ | ~1s | $1.50/1K | Excellent | Low |
| AWS Textract | ★★★★ | ~2s | $1.50/1K | Good | Low |
| Azure AI Vision | ★★★★½ | ~1.5s | $1.00/1K | Excellent | Low |
| Custom Backend | ★★★★★+ | ~1–2s | Server cost | Full control | Medium |

**Recommendation**: Google Cloud Vision for best Japanese OCR quality and direct pixel-coordinate bounding boxes. Custom Backend if maximum accuracy and control are needed.
