package com.baidu.paddle.lite.demo.ppocr_demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GalleryOcrRouterTest {

    @Test
    fun featureFlags_haveExpectedDefaults() {
        assertTrue(OcrFeatureFlags.USE_MLKIT_OCR)
        assertTrue(OcrFeatureFlags.USE_MLKIT_QUESTION_DETECTOR)
        assertFalse(OcrFeatureFlags.ENABLE_MLKIT_DETECTOR_DEBUG_LOGS)
    }
}
