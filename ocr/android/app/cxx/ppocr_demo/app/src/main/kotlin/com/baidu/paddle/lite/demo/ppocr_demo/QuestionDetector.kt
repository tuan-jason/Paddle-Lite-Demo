package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.Bitmap

interface QuestionDetector {
    fun detect(lines: Array<OcrResult>, source: Bitmap): List<QuestionRegion>
}
