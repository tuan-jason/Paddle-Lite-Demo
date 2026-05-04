package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Hybrid OCR engine: PaddleLite detection + ML Kit recognition.
 * Additive only — does not modify any existing class.
 */
class HybridOcrEngine {

    private val recognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )

    @Throws(Exception::class)
    fun process(predictor: Native, bitmap: Bitmap): Array<OcrResult> {
        val boxesJson = predictor.detectBoxes(bitmap) ?: return emptyArray()
        val boxes = parseBoxes(boxesJson)
        if (boxes.isEmpty()) return emptyArray()

        val results = mutableListOf<OcrResult>()
        for (box in boxes) {
            val crop = cropRotatedRegion(bitmap, box) ?: continue
            val (text, score) = recognizeCrop(crop)
            if (crop != bitmap) crop.recycle()
//            if (text.isBlank()) continue
            results.add(OcrResult(box, text, score))
        }
        return results.toTypedArray()
    }

    fun close() = recognizer.close()

    private fun parseBoxes(json: String): List<Array<IntArray>> {
        val boxes = mutableListOf<Array<IntArray>>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val boxArr = arr.getJSONArray(i)
            if (boxArr.length() < 4) continue
            boxes.add(Array(4) { p ->
                val pt = boxArr.getJSONArray(p)
                intArrayOf(pt.getInt(0), pt.getInt(1))
            })
        }
        return boxes
    }

    private fun cropRotatedRegion(bitmap: Bitmap, box: Array<IntArray>): Bitmap? {
        // Step 1: AABB bounds — clamped to bitmap dimensions
        val left   = max(0, box.minOf { it[0] })
        val top    = max(0, box.minOf { it[1] })
        val right  = min(bitmap.width,  box.maxOf { it[0] })
        val bottom = min(bitmap.height, box.maxOf { it[1] })
        val aabbW  = right - left
        val aabbH  = bottom - top
        if (aabbW <= 0 || aabbH <= 0) return null

        // Step 2: Crop the AABB region from the source bitmap
        val aabbCrop = Bitmap.createBitmap(bitmap, left, top, aabbW, aabbH)

        // Step 3: Translate polygon points to AABB-local coordinates
        val pts = Array(4) { i ->
            floatArrayOf((box[i][0] - left).toFloat(), (box[i][1] - top).toFloat())
        }

        // Step 4: Target dimensions — edge lengths of the polygon
        // box order is TL→TR→BR→BL (from OrderPointsClockwise in C++)
        val cropW = hypot((pts[1][0] - pts[0][0]).toDouble(), (pts[1][1] - pts[0][1]).toDouble())
            .toInt().coerceAtLeast(1)
        val cropH = hypot((pts[3][0] - pts[0][0]).toDouble(), (pts[3][1] - pts[0][1]).toDouble())
            .toInt().coerceAtLeast(1)

        // Step 5: Perspective warp — map polygon corners to upright rectangle
        // Mirrors getPerspectiveTransform + warpPerspective in GetRotateCropImage
        val warpMatrix = Matrix()
        warpMatrix.setPolyToPoly(
            floatArrayOf(pts[0][0], pts[0][1], pts[1][0], pts[1][1],
                         pts[2][0], pts[2][1], pts[3][0], pts[3][1]), 0,
            floatArrayOf(0f, 0f, cropW.toFloat(), 0f,
                         cropW.toFloat(), cropH.toFloat(), 0f, cropH.toFloat()), 0,
            4
        )
        val warped = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        Canvas(warped).drawBitmap(aabbCrop, warpMatrix, Paint(Paint.FILTER_BITMAP_FLAG))
        if (aabbCrop !== bitmap) aabbCrop.recycle()

        // Step 6: Rotate 90° CW for tall crops — mirrors GetRotateCropImage's transpose+flip
        if (warped.height >= warped.width * 1.5f) {
            val rotMatrix = Matrix()
            rotMatrix.postRotate(90f)
            val rotated = Bitmap.createBitmap(warped, 0, 0, warped.width, warped.height, rotMatrix, true)
            warped.recycle()
            return rotated
        }
        return warped
    }

    private fun recognizeCrop(crop: Bitmap): Pair<String, Float> {
        val latch = CountDownLatch(1)
        val textRef = AtomicReference("")
        val scoreRef = AtomicReference(0.0f)
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { it.lines }

                // Tier 1: line-level confidence (null for ML Kit Japanese on-device model)
                val lineConf = lines.mapNotNull { it.confidence }
                    .takeIf { it.isNotEmpty() }?.average()?.toFloat()

                // Tier 2: symbol-level confidence — closest analog to PaddleLite's per-character
                // CTC score; populated by the Japanese model even when line confidence is null
                val symbolConf = lineConf ?: lines
                    .flatMap { it.elements }
                    .flatMap { it.symbols }
                    .mapNotNull { it.confidence }
                    .takeIf { it.isNotEmpty() }
                    ?.average()?.toFloat()

                Log.i("MainActivity", "lineConf: $lineConf symbolConf: $symbolConf result.text: ${result.text}")

                // Tier 3: synthetic fallback — ML Kit only returns text it decided on,
                // so a non-empty result is implicitly high-confidence
                val avgConf = symbolConf ?: if (result.text.isNotEmpty()) 0.9f else 0.0f

                textRef.set(result.text)
                scoreRef.set(avgConf.coerceIn(0.0f, 1.0f))
                latch.countDown()
            }
            .addOnFailureListener { latch.countDown() }
        latch.await()
        return Pair(textRef.get(), scoreRef.get())
    }
}
