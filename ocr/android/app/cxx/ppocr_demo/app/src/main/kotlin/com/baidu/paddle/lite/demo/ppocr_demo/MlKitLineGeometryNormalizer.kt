package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.RectF
import kotlin.math.abs

internal enum class GeometryQuality {
    QUAD_STRONG,
    QUAD_WEAK,
    RECT_FALLBACK
}

internal data class MlKitLineGeom(
    val normalized: OcrResult,
    val rect: RectF,
    val lineHeightPx: Float,
    val lineWidthPx: Float,
    val quality: GeometryQuality
)

internal object MlKitLineGeometryNormalizer {
    fun normalizeLines(
        lines: Array<OcrResult>,
        imageWidth: Int,
        imageHeight: Int
    ): List<MlKitLineGeom> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
        return lines.mapNotNull { normalizeLine(it, imageWidth, imageHeight) }
    }

    internal fun normalizeLine(
        line: OcrResult,
        imageWidth: Int,
        imageHeight: Int
    ): MlKitLineGeom? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val rawBox = line.box
        if (rawBox == null || rawBox.size < 4) return null

        val clamped = Array(4) { i ->
            val pt = rawBox.getOrNull(i) ?: return null
            if (pt.size < 2) return null
            intArrayOf(
                pt[0].coerceIn(0, imageWidth - 1),
                pt[1].coerceIn(0, imageHeight - 1)
            )
        }

        val ordered = orderTlTrBrBl(clamped)
        val hasDuplicates = ordered.distinctBy { it[0] to it[1] }.size < 4
        val area = polygonAreaAbs(ordered)
        val rect = toAlignedRect(ordered)
        val looksAxisAlignedRect = isAxisAlignedRect(ordered)

        val quality: GeometryQuality
        val finalBox: Array<IntArray>

        if (hasDuplicates || area < MIN_QUAD_AREA_PX) {
            if (rect.width() < MIN_RECT_SIDE_PX || rect.height() < MIN_RECT_SIDE_PX) {
                return null
            }
            finalBox = rectToBox(rect)
            quality = GeometryQuality.RECT_FALLBACK
        } else if (looksAxisAlignedRect) {
            finalBox = ordered
            quality = GeometryQuality.RECT_FALLBACK
        } else {
            finalBox = ordered
            quality = if (area < WEAK_QUAD_AREA_PX) GeometryQuality.QUAD_WEAK else GeometryQuality.QUAD_STRONG
        }

        val finalRect = toAlignedRect(finalBox)
        if (finalRect.width() < MIN_RECT_SIDE_PX || finalRect.height() < MIN_RECT_SIDE_PX) {
            return null
        }

        return MlKitLineGeom(
            normalized = OcrResult(finalBox, line.text, line.score),
            rect = finalRect,
            lineHeightPx = finalRect.height(),
            lineWidthPx = finalRect.width(),
            quality = quality
        )
    }

    internal fun orderTlTrBrBl(points: Array<IntArray>): Array<IntArray> {
        val indices = points.indices
        fun x(i: Int) = points[i][0]
        fun y(i: Int) = points[i][1]

        val tl = indices.minByOrNull { x(it) + y(it) } ?: return points
        val br = indices.maxByOrNull { x(it) + y(it) } ?: return points
        val tr = indices.maxByOrNull { x(it) - y(it) } ?: return points
        val bl = indices.minByOrNull { x(it) - y(it) } ?: return points

        return arrayOf(
            intArrayOf(x(tl), y(tl)),
            intArrayOf(x(tr), y(tr)),
            intArrayOf(x(br), y(br)),
            intArrayOf(x(bl), y(bl))
        )
    }

    private fun toAlignedRect(box: Array<IntArray>): RectF {
        val xs = box.map { it[0].toFloat() }
        val ys = box.map { it[1].toFloat() }
        return RectF(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun rectToBox(rect: RectF): Array<IntArray> {
        val left = rect.left.toInt()
        val top = rect.top.toInt()
        val right = rect.right.toInt()
        val bottom = rect.bottom.toInt()
        return arrayOf(
            intArrayOf(left, top),
            intArrayOf(right, top),
            intArrayOf(right, bottom),
            intArrayOf(left, bottom)
        )
    }

    private fun isAxisAlignedRect(box: Array<IntArray>): Boolean {
        val tl = box[0]
        val tr = box[1]
        val br = box[2]
        val bl = box[3]
        return tl[1] == tr[1] &&
            bl[1] == br[1] &&
            tl[0] == bl[0] &&
            tr[0] == br[0] &&
            tr[0] > tl[0] &&
            bl[1] > tl[1]
    }

    private fun polygonAreaAbs(box: Array<IntArray>): Float {
        var area2 = 0.0
        for (i in box.indices) {
            val j = (i + 1) % box.size
            area2 += box[i][0] * box[j][1].toDouble() - box[i][1] * box[j][0].toDouble()
        }
        return abs((area2 / 2.0).toFloat())
    }

    private const val MIN_QUAD_AREA_PX = 1.0f
    private const val WEAK_QUAD_AREA_PX = 64.0f
    private const val MIN_RECT_SIDE_PX = 1.0f
}
