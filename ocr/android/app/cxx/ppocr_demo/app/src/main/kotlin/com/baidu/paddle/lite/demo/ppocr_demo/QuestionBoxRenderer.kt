package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.FileOutputStream

/**
 * Renders detected [QuestionRegion] bounding boxes onto a copy of the source bitmap and
 * writes the result as a JPEG to [savedImagePath].
 *
 * ### Box color — OCR quality indicator
 *
 * Box and label color is derived from [QuestionRegion.confidence], which is the **mean OCR
 * recognition score** of the lines inside the region. It reflects text readability, not how
 * confidently the detector decided the lines form a question (the tier-scoring decision is
 * binary and is not stored on the region).
 *
 * | Confidence | Color | Meaning |
 * |---|---|---|
 * | ≥ 0.85 | Blue | High OCR quality — semantic signals were active during detection |
 * | 0.75–0.84 | Yellow | OCR readable — some semantic signals may have been skipped |
 * | 0.60–0.74 | Orange | Geometry-only grouping — text was partially garbled; review recommended |
 * | < 0.60 | Red | OCR quality too low to trust text-based boundaries |
 *
 * See `docs/question-segmentation-signal-fusion.md §Grouping` for the full rationale.
 */
object QuestionBoxRenderer {

    private const val COLOR_HIGH    = 0xFF2979FF.toInt()  // blue   — confidence ≥ 0.85
    private const val COLOR_MEDIUM  = 0xFFFFD600.toInt()  // yellow — confidence 0.75–0.84
    private const val COLOR_LOW     = 0xFFFF6D00.toInt()  // orange — confidence 0.60–0.74
    private const val COLOR_POOR    = 0xFFD50000.toInt()  // red    — confidence < 0.60

    private const val JPEG_QUALITY = 90

    /**
     * Maps a [QuestionRegion.confidence] value to the appropriate box/label color.
     *
     * @param confidence Mean OCR recognition score of lines in the region (0.0–1.0).
     * @return An ARGB color int for use with [Paint.color].
     */
    private fun colorForConfidence(confidence: Float): Int = when {
        confidence >= 0.85f -> COLOR_HIGH
        confidence >= 0.75f -> COLOR_MEDIUM
        confidence >= 0.60f -> COLOR_LOW
        else               -> COLOR_POOR
    }

    /**
     * Draws a labeled bounding box for each [QuestionRegion] onto [source] and saves the
     * result to [savedImagePath].
     *
     * The source bitmap is **not** mutated; a mutable copy is created, annotated, and recycled
     * after the file is written.
     *
     * Stroke width and label text size scale with image resolution so annotations remain
     * visible on both small thumbnails and large camera photos.
     *
     * @param source         Original bitmap from the OCR pipeline.
     * @param regions        Detected question regions to annotate.
     * @param savedImagePath Absolute path where the annotated JPEG is written.
     * @param detectionTime  Total pipeline duration in milliseconds. When > 0, overlays
     *                       "{detectionTime} ms" in black in the top-left corner of the bitmap.
     *                       Pass 0 to suppress the overlay.
     */
    fun render(source: Bitmap, regions: List<QuestionRegion>, savedImagePath: String, detectionTime: Long) {
        if (regions.isEmpty() || savedImagePath.isEmpty()) return

        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        // Scale stroke and text proportionally to image size so boxes are
        // visible on both small thumbnails and large camera photos.
        val scale = maxOf(source.width, source.height) / 1000f
        val strokeWidth = maxOf(2f, 4f * scale)
        val textSize = maxOf(24f, 36f * scale)

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }

        if (detectionTime > 0L) {
            val timingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF000000.toInt()
                this.textSize = textSize
                typeface = Typeface.DEFAULT_BOLD
            }
            val label = "$detectionTime ms"
            val textX = strokeWidth * 2
            val textY = textSize + strokeWidth * 2
            val textWidth = timingPaint.measureText(label)
            val pad = strokeWidth
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.FILL
            }
            canvas.drawRect(textX - pad, textY - textSize - pad, textX + textWidth + pad, textY + pad, bgPaint)
            canvas.drawText(label, textX, textY, timingPaint)
        }

        for (region in regions) {
            val color = colorForConfidence(region.confidence)
            boxPaint.color = color
            textPaint.color = color
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
