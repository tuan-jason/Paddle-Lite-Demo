package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build

object GalleryOcrRouter {
    @JvmStatic
    @Throws(Exception::class)
    fun processBitmap(
        paddle: Native,
        bitmap: Bitmap,
        outPath: String
    ): String {
        if (OcrFeatureFlags.USE_HYBRID_OCR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val engine = HybridOcrEngine()
            try {
                val results = engine.process(paddle, bitmap)
                return OcrResultJsonAdapter.toJson(results)
            } finally {
                engine.close()
            }
        }

        if (!OcrFeatureFlags.USE_MLKIT_OCR || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return paddle.processBitmap(bitmap, outPath)
        }

        val results = BlockingJapaneseMlKitOcrEngine().process(bitmap)
        return OcrResultJsonAdapter.toJson(results)
    }
}
