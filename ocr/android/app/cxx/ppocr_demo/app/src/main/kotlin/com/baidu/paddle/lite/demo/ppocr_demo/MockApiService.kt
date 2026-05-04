package com.baidu.paddle.lite.demo.ppocr_demo

import okhttp3.MultipartBody
import okhttp3.Request
import okio.Timeout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

object MockApiService : ApiService {
    override fun predict(image: MultipartBody.Part): Call<DetectionResponse> =
        ImmediateCall(
            Response.success(
                DetectionResponse(
                    listOf(
                        DetectionObject("Title",      2, 0.9412f, DetectionBbox( 72.34f,  58.10f, 540.21f, 101.85f)),
                        DetectionObject("Plain Text", 0, 0.8821f, DetectionBbox( 72.34f, 120.00f, 540.21f, 250.00f)),
                        DetectionObject("Figure",     3, 0.7650f, DetectionBbox( 72.34f, 280.00f, 320.00f, 450.00f))
                    )
                )
            )
        )
}

private class ImmediateCall<T>(private val response: Response<T>) : Call<T> {
    override fun execute(): Response<T> = response
    override fun enqueue(callback: Callback<T>) = callback.onResponse(this, response)
    override fun isExecuted(): Boolean = false
    override fun cancel() {}
    override fun isCanceled(): Boolean = false
    override fun clone(): Call<T> = this
    override fun request(): Request = Request.Builder().url("http://mock/").build()
    override fun timeout(): Timeout = Timeout()
}
