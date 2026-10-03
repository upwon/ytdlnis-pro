package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, val body: String, val retryAfterMs: Long? = null) :
    Exception("HTTP $code: ${body.take(300)}") {
    /** A daily / account quota is used up: waiting a few seconds will not help, so do not burn more requests. */
    val quotaExhausted: Boolean
        get() = code == 429 && body.lowercase().let {
            "per-day" in it || "per_day" in it || "daily" in it || "insufficient_quota" in it || "exceeded your current quota" in it
        }
    val retryable: Boolean get() = !quotaExhausted && (code == 408 || code == 425 || code == 429 || code >= 500)
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** Throws [ApiException] for non-2xx; returns the body bytes otherwise. */
internal suspend fun Call.awaitBytes(): ByteArray {
    val response = await()
    response.use {
        val bytes = it.body.bytes()
        if (!it.isSuccessful) {
            val retryAfter = it.header("Retry-After")?.toLongOrNull()?.times(1000)
            throw ApiException(it.code, bytes.toString(Charsets.UTF_8), retryAfter)
        }
        return bytes
    }
}

/** Retries rate limits, 5xx and network errors with exponential backoff. */
internal suspend fun <T> retrying(
    maxAttempts: Int,
    baseDelayMs: Long,
    block: suspend (attempt: Int) -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return block(attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            attempt++
            val retryable = (e is ApiException && e.retryable) || e is IOException
            if (!retryable || attempt >= maxAttempts) throw e
            val backoff = baseDelayMs * (1L shl (attempt - 1))
            delay(minOf(30_000L, maxOf(backoff, (e as? ApiException)?.retryAfterMs ?: 0L)))
        }
    }
}

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .writeTimeout(180, TimeUnit.SECONDS)
    .build()
