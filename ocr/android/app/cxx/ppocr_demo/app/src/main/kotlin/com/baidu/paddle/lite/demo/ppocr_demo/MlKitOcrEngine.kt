package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

interface MlKitOcrEngine : AutoCloseable {
    fun process(bitmap: Bitmap, callback: Callback)

    interface Callback {
        fun onSuccess(results: Array<OcrResult>)
        fun onFailure(error: Exception)
    }
}

class JapaneseMlKitOcrEngine @JvmOverloads constructor(
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )
) : MlKitOcrEngine {

    override fun process(bitmap: Bitmap, callback: MlKitOcrEngine.Callback) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { text -> callback.onSuccess(MlKitOcrLineMapper.fromText(text)) }
            .addOnFailureListener { error -> callback.onFailure(error) }
    }

    override fun close() {
        recognizer.close()
    }
}

internal data class MlKitRecognizedLine(
    val text: String,
    val cornerPoints: List<Point>?,
    val boundingBox: Rect?,
    val confidence: Float?
)

internal object MlKitOcrLineMapper {
    private const val SCORE_FALLBACK = 0.0f

    fun fromText(text: Text): Array<OcrResult> {
        return text.textBlocks
            .flatMap { block -> block.lines }
            .map { line ->
                MlKitRecognizedLine(
                    text = line.text,
                    cornerPoints = line.cornerPoints?.toList(),
                    boundingBox = line.boundingBox,
                    confidence = line.confidence
                )
            }
            .mapNotNull(::toOcrResult)
            .toTypedArray()
    }

    fun toOcrResult(line: MlKitRecognizedLine): OcrResult? {
        val box = orderedBox(line.cornerPoints, line.boundingBox) ?: return null
        val score = line.confidence
            ?.takeIf { !it.isNaN() }
            ?.coerceIn(0.0f, 1.0f)
            ?: SCORE_FALLBACK
        return OcrResult(box, line.text, score)
    }

    private fun orderedBox(cornerPoints: List<Point>?, boundingBox: Rect?): Array<IntArray>? {
        if (cornerPoints != null && cornerPoints.size >= 4) {
            return orderCorners(cornerPoints.take(4))
        }

        if (boundingBox == null || boundingBox.width() <= 0 || boundingBox.height() <= 0) {
            return null
        }

        return arrayOf(
            intArrayOf(boundingBox.left, boundingBox.top),
            intArrayOf(boundingBox.right, boundingBox.top),
            intArrayOf(boundingBox.right, boundingBox.bottom),
            intArrayOf(boundingBox.left, boundingBox.bottom)
        )
    }

    private fun orderCorners(points: List<Point>): Array<IntArray> {
        val byY = points.sortedWith(compareBy<Point> { it.y }.thenBy { it.x })
        val top = byY.take(2).sortedBy { it.x }
        val bottom = byY.drop(2).sortedBy { it.x }
        val tl = top[0]
        val tr = top[1]
        val bl = bottom[0]
        val br = bottom[1]

        return arrayOf(
            intArrayOf(tl.x, tl.y),
            intArrayOf(tr.x, tr.y),
            intArrayOf(br.x, br.y),
            intArrayOf(bl.x, bl.y)
        )
    }
}
