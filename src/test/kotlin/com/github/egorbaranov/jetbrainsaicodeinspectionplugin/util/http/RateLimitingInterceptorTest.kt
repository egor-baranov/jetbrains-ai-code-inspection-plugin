package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.http

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Request
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows

class RateLimitingInterceptorTest {
    private val mockChain = mockk<Interceptor.Chain>().apply {
        every { request() } returns mockk<Request>()
        every { proceed(any()) } returns mockk()
    }

    @Test
    fun `should enforce delay between consecutive requests`() {
        val interceptor = RateLimitingInterceptor(60) // 1 request/second
        val tolerance = 50L // ms

        // First request should be immediate
        val time1 = measureTime { interceptor.intercept(mockChain) }
        assertTrue(time1 < tolerance, "First request should be immediate")

        // Second request should take ~1000ms
        val time2 = measureTime { interceptor.intercept(mockChain) }
        assertTrue(time2 in 950L..1050L, "Second request should wait ~1000ms")
    }

    @Test
    fun `should not delay when requests are spaced properly`() {
        val interceptor = RateLimitingInterceptor(60) // 1 request/second
        
        interceptor.intercept(mockChain)
        Thread.sleep(1050) // Wait longer than interval
        
        val executionTime = measureTime { interceptor.intercept(mockChain) }
        assertTrue(executionTime < 50, "Should execute immediately after sufficient delay")
    }

    @Test
    fun `should handle maximum request rate`() {
        val maxRequests = 600 // 10 requests/second
        val interceptor = RateLimitingInterceptor(maxRequests)
        val expectedInterval = 100L // 60,000 / 600 = 100ms
        val tolerance = 30L

        // Warmup request
        interceptor.intercept(mockChain)

        // Subsequent requests should maintain interval
        repeat(5) {
            val time = measureTime { interceptor.intercept(mockChain) }
            assertTrue(time in (expectedInterval - tolerance)..(expectedInterval + tolerance),
                "Request ${it + 1} should maintain ~100ms interval")
        }
    }

    @Test
    fun `should throw exception for zero requests per minute`() {
        assertThrows<ArithmeticException> {
            RateLimitingInterceptor(0)
        }
    }

    @Test
    fun `should calculate correct intervals`() {
        val testCases = mapOf(
            1 to 60_000L,
            60 to 1_000L,
            120 to 500L,
            1000 to 60L
        )

        testCases.forEach { (rpm, expectedInterval) ->
            val interceptor = RateLimitingInterceptor(rpm)
            val actualInterval = interceptor.getRequestIntervalForTest()
            assertEquals(expectedInterval, actualInterval, 
                "Incorrect interval for $rpm RPM")
        }
    }

    // Helper function to measure execution time
    private fun measureTime(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }

    // Extension function to access private field for testing
    private fun RateLimitingInterceptor.getRequestIntervalForTest() = 
        this.javaClass.getDeclaredField("requestInterval").let {
            it.isAccessible = true
            return@let it.getLong(this)
        }
}