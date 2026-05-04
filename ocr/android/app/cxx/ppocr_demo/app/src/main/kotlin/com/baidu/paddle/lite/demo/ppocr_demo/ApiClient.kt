package com.baidu.paddle.lite.demo.ppocr_demo

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    private const val BASE_URL = "http://10.10.10.255:8000/"

    /** Flip to false once the API at BASE_URL is deployed and reachable. */
    const val USE_MOCK = false

    private val real: ApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(OkHttpClient.Builder().addInterceptor(logging).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    fun getService(): ApiService = if (USE_MOCK) MockApiService else real
}
