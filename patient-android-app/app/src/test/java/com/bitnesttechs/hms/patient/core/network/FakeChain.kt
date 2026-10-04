package com.bitnesttechs.hms.patient.core.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit

/**
 * An [Interceptor.Chain] that records every request it is asked to proceed
 * with and answers each with the next code from [codes] (the last one
 * repeats), so an interceptor can be driven without a server.
 */
class FakeChain(
    private val original: Request = Request.Builder().url("https://example.invalid/api/ping").build(),
    private val codes: List<Int> = listOf(200)
) : Interceptor.Chain {
    val proceeded = mutableListOf<Request>()

    override fun request(): Request = original
    override fun proceed(request: Request): Response {
        proceeded += request
        val code = codes[minOf(proceeded.size - 1, codes.lastIndex)]
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("status $code")
            .body("".toResponseBody("application/json".toMediaType()))
            .build()
    }
    override fun connection() = null
    override fun call() = throw UnsupportedOperationException()
    override fun connectTimeoutMillis() = 0
    override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
    override fun readTimeoutMillis() = 0
    override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
    override fun writeTimeoutMillis() = 0
    override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
}
