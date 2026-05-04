package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
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
            val crop = cropRegion(bitmap, box) ?: continue
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

    private fun cropRegion(bitmap: Bitmap, box: Array<IntArray>): Bitmap? {
        val left = max(0, box.minOf { it[0] })
        val top = max(0, box.minOf { it[1] })
        val right = min(bitmap.width, box.maxOf { it[0] })
        val bottom = min(bitmap.height, box.maxOf { it[1] })
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }

    private fun recognizeCrop(crop: Bitmap): Pair<String, Float> {
        val latch = CountDownLatch(1)
        val textRef = AtomicReference("")
        val scoreRef = AtomicReference(0.0f)
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { it.lines }
                val avgConf = lines.mapNotNull { it.confidence }
                    .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0.0f
                textRef.set(result.text)
                scoreRef.set(avgConf.coerceIn(0.0f, 1.0f))
                latch.countDown()
            }
            .addOnFailureListener { latch.countDown() }
        latch.await()
        return Pair(textRef.get(), scoreRef.get())
    }
}
