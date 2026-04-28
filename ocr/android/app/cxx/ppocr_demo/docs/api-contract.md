# API Design Reference

## `POST /predict` — Response Structure

The endpoint returns a JSON object with a single top-level key `detections`.

### Top-level `data` object

```json
{
  "detections": [ ...DetectionObject ]
}
```

### `DetectionObject`

```json
{
  "class_name": "Title",
  "class_id": 2,
  "confidence": 0.9412,
  "bbox": {
    "x1": 72.34,
    "y1": 58.10,
    "x2": 540.21,
    "y2": 101.85
  }
}
```

### Field breakdown

| Field | Type | Description |
|---|---|---|
| `detections` | `array` | Top-level key — list of all detected layout regions |
| `detections[].class_name` | `string` | Human-readable label (e.g. `"Title"`, `"Plain Text"`, `"Figure"`) |
| `detections[].class_id` | `int` | Numeric class index from the model |
| `detections[].confidence` | `float` | Detection confidence, rounded to 4 decimal places |
| `detections[].bbox.x1` | `float` | Left edge (pixels, rounded to 2 dp) |
| `detections[].bbox.y1` | `float` | Top edge |
| `detections[].bbox.x2` | `float` | Right edge |
| `detections[].bbox.y2` | `float` | Bottom edge |

### Notes

- `data["detections"]` is what `call_predict_api()` in `client.py` returns — the wrapper object is stripped at `client.py:54`.
- No metadata, timing, or image info is included in the response.
- Bbox coordinates are in the original image's pixel space (not normalized).
- Source: `api.py:48–74`, `client.py:36–54`.
