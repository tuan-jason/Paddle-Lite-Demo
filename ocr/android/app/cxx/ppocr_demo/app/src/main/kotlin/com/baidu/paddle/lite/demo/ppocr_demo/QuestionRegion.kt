package com.baidu.paddle.lite.demo.ppocr_demo

import android.graphics.RectF

data class QuestionRegion(
    val index: Int,
    val bounds: RectF,
    val confidence: Float,
    val lineBoxes: List<RectF>
)
