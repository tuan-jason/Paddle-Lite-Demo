package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.FileOutputStream

object QuestionBoxRenderer {

    private const val BOX_COLOR = 0xFF2979FF.toInt()
    private const val JPEG_QUALITY = 90

    fun render(source: Bitmap, regions: List<QuestionRegion>, savedImagePath: String) {
        if (regions.isEmpty() || savedImagePath.isEmpty()) return

        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        // Scale stroke and text proportionally to image size so boxes are
        // visible on both small thumbnails and large camera photos.
        val scale = maxOf(source.width, source.height) / 1000f
        val strokeWidth = maxOf(2f, 4f * scale)
        val textSize = maxOf(24f, 36f * scale)

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BOX_COLOR
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BOX_COLOR
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }

        for (region in regions) {
            canvas.drawRect(region.bounds, boxPaint)
            // Label drawn inside the top-left corner so it's always within bounds.
            canvas.drawText(
                "Q${region.index + 1}",
                region.bounds.left + strokeWidth * 2,
                region.bounds.top + textSize + strokeWidth * 2,
                textPaint
            )
        }

        FileOutputStream(savedImagePath).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        bitmap.recycle()
    }
}
