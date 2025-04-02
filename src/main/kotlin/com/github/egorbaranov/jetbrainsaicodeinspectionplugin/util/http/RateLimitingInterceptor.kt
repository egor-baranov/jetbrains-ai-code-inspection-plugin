package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.http

import okhttp3.Interceptor
import okhttp3.Response
import kotlin.math.max

class RateLimitingInterceptor(
    requestsPerMinute: Int
) : Interceptor {
    private var lastRequestTime = 0L
    private val requestInterval = 60_000L / requestsPerMinute
    
    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRequestTime
        val waitTime = max(0, requestInterval - elapsed)

        if (waitTime > 0) {
            Thread.sleep(waitTime)
        }
        
        lastRequestTime = System.currentTimeMillis()
        return chain.proceed(chain.request())
    }
}