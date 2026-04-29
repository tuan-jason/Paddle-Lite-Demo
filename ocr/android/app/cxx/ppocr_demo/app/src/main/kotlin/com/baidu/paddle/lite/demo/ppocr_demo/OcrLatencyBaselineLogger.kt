package com.baidu.paddle.lite.demo.ppocr_demo

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.round

/**
 * Emits structured baseline latency logs for Paddle processBitmap inference time.
 *
 * Sample event:
 * {"event":"ocr_latency_sample", ...}
 *
 * Summary event (rolling window of latest 20 samples):
 * {"event":"ocr_latency_summary", ...}
 */
object OcrLatencyBaselineLogger {
    private const val TAG = "OcrLatencyBaseline"
    private const val SUMMARY_WINDOW_SIZE = 20

    private val processBitmapMsSamples = mutableListOf<Long>()

    @JvmStatic
    @Synchronized
    fun logProcessBitmapSample(
        imageId: String?,
        processBitmapMs: Long,
        imageWidth: Int,
        imageHeight: Int
    ) {
        processBitmapMsSamples.add(processBitmapMs)

        try {
            val sample = JSONObject()
                .put("event", "ocr_latency_sample")
                .put("engine", "paddle")
                .put("sample_count", processBitmapMsSamples.size)
                .put("image_id", imageId)
                .put("image_width", imageWidth)
                .put("image_height", imageHeight)
                .put("process_bitmap_ms", processBitmapMs)
                .put("ts_epoch_ms", System.currentTimeMillis())
            Log.i(TAG, sample.toString())

            if (processBitmapMsSamples.size >= SUMMARY_WINDOW_SIZE) {
                val processWindow = lastWindow(processBitmapMsSamples, SUMMARY_WINDOW_SIZE)
                val summary = JSONObject()
                    .put("event", "ocr_latency_summary")
                    .put("engine", "paddle")
                    .put("window_size", SUMMARY_WINDOW_SIZE)
                    .put("sample_count", processBitmapMsSamples.size)
                    .put("window_process_bitmap_ms_median", round1(median(processWindow)))
                    .put("window_process_bitmap_ms_p90", round1(percentile(processWindow, 90.0)))
                    .put("ts_epoch_ms", System.currentTimeMillis())
                Log.i(TAG, summary.toString())
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to build structured baseline latency log", e)
        }
    }

    private fun lastWindow(source: List<Long>, windowSize: Int): List<Long> {
        val from = maxOf(0, source.size - windowSize)
        return source.subList(from, source.size).toList()
    }

    private fun median(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2].toDouble()
        } else {
            (sorted[(n / 2) - 1] + sorted[n / 2]) / 2.0
        }
    }

    private fun percentile(values: List<Long>, percentile: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val n = sorted.size
        val index = (ceil((percentile / 100.0) * n).toInt() - 1).coerceIn(0, n - 1)
        return sorted[index].toDouble()
    }

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0
}
