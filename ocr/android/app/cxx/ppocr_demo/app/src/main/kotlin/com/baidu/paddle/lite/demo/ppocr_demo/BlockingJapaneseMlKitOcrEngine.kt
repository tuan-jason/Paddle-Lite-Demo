package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class BlockingJapaneseMlKitOcrEngine {
    @Throws(Exception::class)
    fun process(bitmap: Bitmap): Array<OcrResult> {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<Array<OcrResult>>()
        val errorRef = AtomicReference<Exception>()

        JapaneseMlKitOcrEngine().use { engine ->
            engine.process(bitmap, object : MlKitOcrEngine.Callback {
                override fun onSuccess(results: Array<OcrResult>) {
                    resultRef.set(results)
                    latch.countDown()
                }

                override fun onFailure(error: Exception) {
                    errorRef.set(error)
                    latch.countDown()
                }
            })

            latch.await()
        }

        errorRef.get()?.let { throw it }
        return resultRef.get() ?: emptyArray()
    }
}
