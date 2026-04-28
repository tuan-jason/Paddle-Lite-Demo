package com.baidu.paddle.lite.demo.ppocr_demo

import com.google.gson.annotations.SerializedName

data class DetectionObject(
    @SerializedName("class_name") val className: String,
    @SerializedName("class_id") val classId: Int,
    val confidence: Float,
    val bbox: DetectionBbox
)
