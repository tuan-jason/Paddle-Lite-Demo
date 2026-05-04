# Build Guidelines — Obtaining a PaddleLite `.nb` Model for Any Language

## Overview

PaddleOCR recognition models and their character dictionaries are tightly coupled.
The Paddle-Lite-Demo repository only ships pre-built Chinese `.nb` models.
For any other language you must:

1. Download the matching inference model from PaddleOCR.
2. Convert it to `.nb` format using the PaddleLite `opt` tool.
3. Add both the `.nb` and the dictionary to the app assets.

---

## Step 1 — Find the Inference Model for Your Language

Go to the PaddleOCR multilingual model list:

```
https://github.com/PaddlePaddle/PaddleOCR/blob/release/2.3/doc/doc_en/models_list_en.md
```

In section **"2.3 Multilingual Recognition Model"**, locate your language row and copy
the **inference model** download URL (the `.tar` file ending in `_infer.tar`).

| Language | Dict file | Inference model URL |
|---|---|---
| Japanese | `japan_dict.txt` | `https://paddleocr.bj.bcebos.com/dygraph_v2.0/multilingual/japan_mobile_v2.0_rec_infer.tar` |
| Korean | `korean_dict.txt` | `https://paddleocr.bj.bcebos.com/dygraph_v2.0/multilingual/korean_mobile_v2.0_rec_infer.tar` |
| Latin | `en_dict.txt` | `https://paddleocr.bj.bcebos.com/dygraph_v2.0/multilingual/latin_ppocr_mobile_v2.0_rec_infer.tar` |
| (others) | see models_list_en.md | see models_list_en.md |

> **Note:** The detection model (`ch_ppocr_mobile_v2.0_det`) and classification model
> (`ch_ppocr_mobile_v2.0_cls`) are language-agnostic. Only the **recognition** model
> and its dictionary need to change per language.

---

## Step 2 — Download and Extract the Inference Model

```bash
cd /tmp
curl -O <inference_model_url>
tar -xzf <model_name>_infer.tar
# Result: a folder named <model_name>_infer/ containing __model__ and __params__
```

Example for Japanese:

```bash
cd /tmp
curl -O https://paddleocr.bj.bcebos.com/dygraph_v2.0/multilingual/japan_mobile_v2.0_rec_infer.tar
tar -xzf japan_mobile_v2.0_rec_infer.tar
```

---

## Step 3 — Obtain the PaddleLite `opt` Tool

The `opt` tool converts a PaddlePaddle inference model to the `.nb` format consumed
by the PaddleLite runtime on Android/iOS.

On **Apple Silicon (M1/M2/M3)**, use `opt_m1` from PaddleLite v2.12 — despite the name,
it is an x86_64 binary that runs via Rosetta 2 without the AVX2 issue that prevents
the v2.10-rc `opt_mac` binary from running:

```
https://github.com/PaddlePaddle/Paddle-Lite/releases/download/v2.12/opt_m1
```

On **Intel macOS**, use `opt_mac` from the matching SDK release:

```
https://github.com/PaddlePaddle/Paddle-Lite/releases/tag/v2.10-rc
```

Make the binary executable:

```bash
chmod +x opt_m1   # or opt_mac
```

> **Apple Silicon note:** `opt_mac` from v2.10-rc fails with SIGILL (exit 132) under Rosetta
> because it uses AVX2 instructions. Use `opt_m1` from v2.12 instead.
> The v2.12 opt tool produces `.nb` files confirmed compatible with the v2.10-rc runtime.

---

## Step 4 — Convert the Inference Model to `.nb`

```bash
./opt_mac \
  --model_dir=/tmp/<model_name>_infer \
  --valid_targets=arm \
  --optimize_out=/tmp/<output_name>
```

Example for Japanese targeting ARM:

```bash
./opt_mac \
  --model_dir=/tmp/japan_mobile_v2.0_rec_infer \
  --valid_targets=arm \
  --optimize_out=/tmp/japan_mobile_v2.0_rec_opt
```

This produces `/tmp/japan_mobile_v2.0_rec_opt.nb`.

---

## Step 5 — Add Assets to the App

Copy the `.nb` and the matching dictionary to the Android assets folder:

```bash
cp /tmp/japan_mobile_v2.0_rec_opt.nb \
   <project>/ocr/android/app/cxx/ppocr_demo/app/src/main/assets/

cp <paddleocr_repo>/ppocr/utils/dict/japan_dict.txt \
   <project>/ocr/android/app/cxx/ppocr_demo/app/src/main/assets/
```

---

## Step 6 — Update `MainActivity.java`

Update the `recModelPath` and `labelPath` fields to point to the new files:

```java
protected String recModelPath = "japan_mobile_v2.0_rec_opt.nb";
protected String labelPath    = "japan_dict.txt";
```

Leave `detModelPath` and `clsModelPath` unchanged.

---

## Step 7 — Rebuild and Deploy

```bash
./gradlew assembleDebug
```

Then install and test on device.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Crash in `RecPredictor::Postprocess` (out-of-bounds) | Dictionary size does not match model vocabulary — model and dict are mismatched |
| `Failed to load model` at runtime | `opt` tool version does not match PaddleLite SDK version |
| Empty recognition output | Model loaded correctly but dict has wrong encoding (ensure UTF-8) |
