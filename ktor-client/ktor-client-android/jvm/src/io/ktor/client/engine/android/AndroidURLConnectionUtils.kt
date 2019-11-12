/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.android

import io.ktor.client.features.*
import io.ktor.client.utils.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import kotlin.coroutines.*

/**
 * Setup [HttpURLConnection] timeout configuration using [HttpTimeoutAttributes] as a source. These attributes are
 * introduced by [HttpTimeout] client feature.
 */
internal fun HttpURLConnection.setupTimeoutAttributes(attributes: Attributes) {
    attributes.getOrNull(HttpTimeoutAttributes.key)?.let { timeoutAttributes ->
        timeoutAttributes.connectTimeout?.let { connectTimeout = it.toInt() }
        timeoutAttributes.socketTimeout?.let { readTimeout = it.toInt() }
        setupRequestTimeoutAttributes(timeoutAttributes)
    }
}

/**
 * Update [HttpURLConnection] timeout configuration to support request timeout. Required to support blocking
 * [HttpURLConnection.connect] call.
 */
private fun HttpURLConnection.setupRequestTimeoutAttributes(timeoutAttributes: HttpTimeoutAttributes) {
    // Android performs blocking connect call, so we need to add an upper bound on the call time.
    timeoutAttributes.requestTimeout?.let {
        if (it == 0L) return@let
        connectTimeout = when {
            connectTimeout == 0 -> it.toInt()
            connectTimeout < it -> connectTimeout
            else -> it.toInt()
        }
    }
}

/**
 * Call [HttpURLConnection.connect] catching [SocketTimeoutException] and returning [HttpSocketTimeoutException] instead
 * of it. If request timeout happens earlier [HttpRequestTimeoutException] will be thrown.
 */
internal suspend fun HttpURLConnection.timeoutAwareConnect() {
    try {
        connect()
    } catch (cause: Throwable) {
        // Allow to throw request timeout cancellation exception instead of connect timeout exception if needed.
        yield()
        throw when (cause) {
            is SocketTimeoutException -> HttpConnectTimeoutException()
            else -> cause
        }
    }
}

internal fun HttpURLConnection.content(callScope: CoroutineContext): ByteReadChannel = try {
    inputStream?.buffered()
} catch (_: IOException) {
    errorStream?.buffered()
}?.toByteReadChannel(
    context = callScope,
    pool = KtorDefaultPool
)?.withSocketTimeoutExceptionMapping() ?: ByteReadChannel.Empty

/**
 * Returns [ByteReadChannel] with [ByteChannel.close] handler that returns [HttpSocketTimeoutException] instead of
 * [SocketTimeoutException].
 */
@InternalAPI
private fun ByteReadChannel.withSocketTimeoutExceptionMapping(): ByteReadChannel =
    withCloseHandler { cause, rootCause, close ->
        close(
            when (rootCause) {
                is SocketTimeoutException -> HttpSocketTimeoutException()
                else -> cause
            }
        )
    }
