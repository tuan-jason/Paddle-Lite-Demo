package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Pre-processes a raw camera/gallery bitmap before it enters the OCR pipeline.
 *
 * Corrects perspective distortion, document skew, uneven lighting, and aspect-ratio
 * distortion so that `SignalFusionQuestionDetector`'s geometric signals (gap distances,
 * modal left margin, anchor x-range) operate on a flat, axis-aligned document image.
 *
 * See `docs/bitmap-preprocessing-pipeline.md` for design rationale, per-step details,
 * and the implementation task list.
 *
 * ### Pipeline
 * ```
 * source Bitmap
 *   → Step 1+2: document corner detection → perspective correction
 *   → Step 3:   skew correction
 *   → Step 4:   contrast enhancement
 *   → Step 5:   resolution normalisation
 *   → corrected Bitmap
 * ```
 *
 * ### Fallback contract
 * Each step is wrapped in a try/catch. If a step fails or produces no result, the bitmap
 * from the previous step is passed through unchanged. The pipeline never throws.
 */
object BitmapPreprocessor {

    /**
     * Runs the full pre-processing pipeline on [source] and returns the corrected bitmap.
     *
     * The returned bitmap may be [source] itself if no corrections could be applied.
     * Callers are responsible for recycling [source] if a new bitmap is returned:
     * ```kotlin
     * val corrected = BitmapPreprocessor.process(raw)
     * if (corrected != raw) raw.recycle()
     * ```
     *
     * @param source Raw bitmap from `BitmapUtils.decodeBitmapWithOrientation`.
     * @return Corrected bitmap ready for `predictor.processBitmap`.
     */
    fun process(source: Bitmap): Bitmap {
        var current = source

        // Replaces current with next; recycles the old intermediate if it differs from
        // both next and source (source is owned by the caller — never recycled here).
        fun advance(next: Bitmap?) {
            val prev = current
            current = next ?: prev
            if (current !== prev && prev !== source) prev.recycle()
        }

        // Step 1 + 2: document corner detection → perspective correction
        advance(tryStep("perspective") {
            val corners = detectDocumentCorners(current)
            if (corners != null) correctPerspective(current, corners) else current
        })

        // Step 3: skew correction
        advance(tryStep("skew") {
            val angle = measureSkewAngle(current)
            correctSkew(current, angle)
        })

        // Step 4: contrast enhancement
        advance(tryStep("contrast") {
            enhanceContrast(current)
        })

        // Step 5: resolution normalisation
        advance(tryStep("resolution") {
            normaliseResolution(current)
        })

        return current
    }

    /**
     * Executes [block] and returns its result, or `null` if [block] throws.
     * The [stepName] is included in the log tag for diagnostics.
     */
    private inline fun tryStep(stepName: String, block: () -> Bitmap): Bitmap? {
        return try {
            block()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Pre-processing step '$stepName' failed — skipping", e)
            null
        }
    }

    // ── Step stubs — implemented in subsequent tasks ──────────────────────────

    /**
     * Detects the four corners of the document in [bitmap].
     *
     * Returns a [FloatArray] of 8 values `[x0,y0, x1,y1, x2,y2, x3,y3]` representing
     * the four corners in the order returned by [approxPolyDP] (not yet TL/TR/BR/BL —
     * that ordering is applied by [orderCorners] inside [correctPerspective]).
     *
     * Returns `null` if no quadrilateral covering ≥ 20% of the image area is found.
     *
     * Algorithm: RGBA→grey → GaussianBlur(5×5) → Canny(75,200) → findContours →
     * largest contour → approxPolyDP(ε=2% arc length) → accept if exactly 4 vertices.
     */
    private fun detectDocumentCorners(bitmap: Bitmap): FloatArray? {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)

        // Convert to greyscale — bitmaps decoded from gallery are RGBA
        val grey = Mat()
        Imgproc.cvtColor(rgba, grey, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()

        // Suppress noise before edge detection
        val blurred = Mat()
        Imgproc.GaussianBlur(grey, blurred, Size(5.0, 5.0), 0.0)
        grey.release()

        // Strong edge detection
        val edges = Mat()
        Imgproc.Canny(blurred, edges, 75.0, 200.0)
        blurred.release()

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edges, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        edges.release()
        hierarchy.release()

        if (contours.isEmpty()) return null

        val minArea = bitmap.width.toDouble() * bitmap.height.toDouble() * MIN_DOCUMENT_AREA_FRACTION

        // Walk contours largest-first; return the first valid quadrilateral
        contours.sortedByDescending { Imgproc.contourArea(it) }.forEach { contour ->
            if (Imgproc.contourArea(contour) < minArea) return null  // remaining are smaller

            val contour2f = MatOfPoint2f(*contour.toArray())
            val perimeter  = Imgproc.arcLength(contour2f, true)
            val approx     = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, APPROX_EPSILON_FRACTION * perimeter, true)
            contour2f.release()

            if (approx.rows() == 4) {
                val pts = approx.toArray()
                approx.release()
                return floatArrayOf(
                    pts[0].x.toFloat(), pts[0].y.toFloat(),
                    pts[1].x.toFloat(), pts[1].y.toFloat(),
                    pts[2].x.toFloat(), pts[2].y.toFloat(),
                    pts[3].x.toFloat(), pts[3].y.toFloat()
                )
            }
            approx.release()
        }
        return null
    }

    /**
     * Sorts 4 corner points into clockwise TL → TR → BR → BL order.
     *
     * Input/output format: `[x0,y0, x1,y1, x2,y2, x3,y3]` (8 floats, 4 pairs).
     *
     * Algorithm (coordinate-sum/diff):
     * - **TL** = point with minimum `x + y`  (smallest x and y → top-left)
     * - **BR** = point with maximum `x + y`  (largest  x and y → bottom-right)
     * - **TR** = point with maximum `x − y`  (large x, small y → top-right)
     * - **BL** = point with minimum `x − y`  (small x, large y → bottom-left)
     *
     * This is robust to any input ordering and works correctly for any convex
     * quadrilateral that is not degenerate (all 4 points distinct and non-collinear).
     *
     * `internal` visibility so it can be exercised directly in unit tests (T-11).
     */
    internal fun orderCorners(pts: FloatArray): FloatArray {
        // Represent each point as an index into the flat array for easy extraction.
        val indices = 0 until 4
        fun x(i: Int) = pts[i * 2]
        fun y(i: Int) = pts[i * 2 + 1]

        val tl = indices.minByOrNull { x(it) + y(it) }!!
        val br = indices.maxByOrNull { x(it) + y(it) }!!
        val tr = indices.maxByOrNull { x(it) - y(it) }!!
        val bl = indices.minByOrNull { x(it) - y(it) }!!

        return floatArrayOf(
            x(tl), y(tl),  // TL
            x(tr), y(tr),  // TR
            x(br), y(br),  // BR
            x(bl), y(bl)   // BL
        )
    }

    /**
     * Warps [bitmap] to a flat rectangle using the 4 raw corner points in [corners].
     *
     * Internally calls [orderCorners] to guarantee TL → TR → BR → BL ordering before
     * building the transform, so [corners] may arrive in any order from [detectDocumentCorners].
     *
     * Target dimensions are computed from the physical edge lengths of the detected
     * quadrilateral (max of the two opposite edges for each axis), preserving the
     * document's natural proportions without hard-coding an A4 size.
     *
     * The perspective warp is applied via [android.graphics.Matrix.setPolyToPoly] drawn
     * onto a [Canvas] backed by a fresh [Bitmap]. [Paint.FILTER_BITMAP_FLAG] is set so
     * the Skia renderer applies bilinear filtering during the warp.
     */
    private fun correctPerspective(bitmap: Bitmap, corners: FloatArray): Bitmap {
        val ordered = orderCorners(corners)

        // Accessor helpers — ordered layout: TL[0], TR[1], BR[2], BL[3]
        fun ox(i: Int) = ordered[i * 2]
        fun oy(i: Int) = ordered[i * 2 + 1]
        fun edgeLen(ai: Int, bi: Int) =
            hypot((ox(bi) - ox(ai)).toDouble(), (oy(bi) - oy(ai)).toDouble()).toFloat()

        // Target size = longer of the two opposite edges on each axis
        val targetW = maxOf(edgeLen(0, 1), edgeLen(3, 2)).toInt().coerceAtLeast(1)  // TL→TR, BL→BR
        val targetH = maxOf(edgeLen(0, 3), edgeLen(1, 2)).toInt().coerceAtLeast(1)  // TL→BL, TR→BR

        // dst corners match the TL→TR→BR→BL ordering of ordered[]
        val dst = floatArrayOf(
            0f,             0f,              // TL
            targetW.toFloat(), 0f,           // TR
            targetW.toFloat(), targetH.toFloat(), // BR
            0f,             targetH.toFloat()    // BL
        )

        val matrix = android.graphics.Matrix()
        matrix.setPolyToPoly(ordered, 0, dst, 0, 4)

        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return result
    }

    /**
     * Measures the residual skew angle of text lines in [bitmap] and returns it in degrees.
     *
     * Returns `0f` if the angle cannot be reliably determined (fewer than
     * [MIN_HOUGH_LINES] near-horizontal lines detected).
     *
     * Algorithm: RGBA→grey → Canny(50,150) → HoughLinesP → keep lines with
     * `|angle| < 30°` → median of their angles.
     *
     * The returned angle is the raw median; the `|angle| < 0.5°` no-op guard lives
     * in [correctSkew] so the caller controls the tolerance.
     *
     * `internal` visibility so it can be exercised directly in unit tests (T-14).
     */
    internal fun measureSkewAngle(bitmap: Bitmap): Float {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)

        val grey = Mat()
        Imgproc.cvtColor(rgba, grey, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()

        // Lower Canny thresholds than corner detection — text lines have softer edges
        val edges = Mat()
        Imgproc.Canny(grey, edges, 50.0, 150.0)
        grey.release()

        val minLineLen = bitmap.width * MIN_LINE_LENGTH_FRACTION
        val maxLineGap = bitmap.width * MAX_LINE_GAP_FRACTION

        val lines = Mat()
        Imgproc.HoughLinesP(
            edges, lines,
            1.0, Math.PI / 180.0,
            HOUGH_VOTE_THRESHOLD,
            minLineLen, maxLineGap
        )
        edges.release()

        // Each row is [x1, y1, x2, y2] as a double[4]
        val angles = ArrayList<Float>(lines.rows())
        for (i in 0 until lines.rows()) {
            val seg = lines.get(i, 0)           // DoubleArray [x1, y1, x2, y2]
            val dx  = seg[2] - seg[0]
            val dy  = seg[3] - seg[1]
            val deg = Math.toDegrees(atan2(dy, dx)).toFloat()
            if (abs(deg) < NEAR_HORIZONTAL_MAX_DEG) angles += deg
        }
        lines.release()

        if (angles.size < MIN_HOUGH_LINES) return 0f

        angles.sort()
        val mid = angles.size / 2
        return if (angles.size % 2 == 0) (angles[mid - 1] + angles[mid]) / 2f else angles[mid]
    }

    /**
     * Rotates [bitmap] by [-angleDeg] degrees around its centre to correct skew.
     * No-op if `|angleDeg| < 0.5f`. Uses `android.graphics.Matrix` + hardware Canvas (GPU).
     */
    private fun correctSkew(bitmap: Bitmap, angleDeg: Float): Bitmap {
        if (abs(angleDeg) < SKEW_NO_OP_THRESHOLD) return bitmap
        val matrix = android.graphics.Matrix()
        matrix.postRotate(-angleDeg, bitmap.width / 2f, bitmap.height / 2f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Normalises contrast across [bitmap] using `ColorMatrixColorFilter` on a
     * hardware-accelerated Canvas.
     *
     * Samples min/max luminance from a [CONTRAST_SAMPLE_SIZE]×[CONTRAST_SAMPLE_SIZE]
     * thumbnail, then builds a `ColorMatrix` that linearly stretches the range
     * `[minLum, maxLum]` → `[0, 255]` for each channel. No-op if the luminance
     * range is narrower than [CONTRAST_MIN_RANGE] (nearly uniform image).
     */
    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val thumbW = CONTRAST_SAMPLE_SIZE
        val thumbH = ((thumbW.toFloat() / bitmap.width) * bitmap.height).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(bitmap, thumbW, thumbH, false)

        val pixels = IntArray(thumb.width * thumb.height)
        thumb.getPixels(pixels, 0, thumb.width, 0, 0, thumb.width, thumb.height)
        thumb.recycle()

        var minLum = 1f
        var maxLum = 0f
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            if (lum < minLum) minLum = lum
            if (lum > maxLum) maxLum = lum
        }

        val range = maxLum - minLum
        if (range < CONTRAST_MIN_RANGE) return bitmap

        // Linear stretch: map [minLum*255, maxLum*255] → [0, 255] per channel.
        val scale = 1f / range
        val translate = -minLum * scale * 255f

        val cm = ColorMatrix(floatArrayOf(
            scale, 0f,    0f,    0f, translate,
            0f,    scale, 0f,    0f, translate,
            0f,    0f,    scale, 0f, translate,
            0f,    0f,    0f,    1f, 0f
        ))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    /**
     * Resizes [bitmap] to [TARGET_WIDTH] px wide, preserving aspect ratio, using
     * `Bitmap.createScaledBitmap`. No-op if the bitmap is already at or below [TARGET_WIDTH].
     */
    private fun normaliseResolution(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= TARGET_WIDTH) return bitmap
        val targetH = (TARGET_WIDTH.toFloat() / bitmap.width * bitmap.height).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, targetH, true)
    }

    private const val TAG = "BitmapPreprocessor"

    // Minimum contour area relative to total image area for a valid document boundary.
    private const val MIN_DOCUMENT_AREA_FRACTION = 0.20

    // approxPolyDP epsilon as a fraction of the contour arc length.
    private const val APPROX_EPSILON_FRACTION = 0.02

    // HoughLinesP: minimum line length as a fraction of image width (30%).
    private const val MIN_LINE_LENGTH_FRACTION = 0.30

    // HoughLinesP: maximum gap between collinear segments as a fraction of image width (5%).
    private const val MAX_LINE_GAP_FRACTION = 0.05

    // HoughLinesP: minimum accumulator votes for a line to be accepted.
    private const val HOUGH_VOTE_THRESHOLD = 80

    // Lines with |angle| beyond this value (degrees) are not text baselines.
    private const val NEAR_HORIZONTAL_MAX_DEG = 30f

    // Minimum number of near-horizontal lines required to compute a reliable median angle.
    private const val MIN_HOUGH_LINES = 5

    // Skew angles below this threshold (degrees) are treated as negligible — no rotation applied.
    private const val SKEW_NO_OP_THRESHOLD = 0.5f

    // Side length (px) of the thumbnail used to sample luminance for auto-contrast.
    private const val CONTRAST_SAMPLE_SIZE = 64

    // Minimum luminance range required to apply contrast enhancement; narrower ranges are skipped.
    private const val CONTRAST_MIN_RANGE = 0.10f

    // Target output width (px) for resolution normalisation — ≈ A4 at 105 DPI.
    private const val TARGET_WIDTH = 1240
}
