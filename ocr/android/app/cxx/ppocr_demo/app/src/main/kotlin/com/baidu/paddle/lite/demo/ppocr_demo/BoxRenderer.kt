package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.FileOutputStream

object BoxRenderer {
    private val TAG = "BoxRenderer"
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 32f
    }

    fun drawAndSave(source: Bitmap, detections: List<DetectionObject>, outputPath: String) {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        for (det in detections) {
            val b = det.bbox
            Log.d(TAG, "x1: ${b.x1} y1: ${b.y1} x2: ${b.x2} y2: ${b.y2}")
            canvas.drawRect(b.x1, b.y1, b.x2, b.y2, boxPaint)
            canvas.drawText(
                "${det.className} ${(det.confidence * 100).toInt()}%",
                b.x1,
                maxOf(b.y1 - 6f, labelPaint.textSize),
                labelPaint
            )
        }
        FileOutputStream(outputPath).use { mutable.compress(Bitmap.CompressFormat.PNG, 100, it) }
        mutable.recycle()
    }
}
