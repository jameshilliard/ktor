/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.android

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import javax.net.ssl.*
import kotlin.coroutines.*

/**
 * Android client engine
 */
class AndroidClientEngine(override val config: AndroidEngineConfig) : HttpClientEngineBase("ktor-android") {

    override val dispatcher by lazy {
        Dispatchers.fixedThreadPoolDispatcher(
            config.threadsCount,
            "ktor-android-thread-%d"
        )
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()

        val requestTime: GMTDate = GMTDate()

        val url: String = URLBuilder().takeFrom(data.url).buildString()
        val outgoingContent: OutgoingContent = data.body
        val contentLength: Long? =
            data.headers[HttpHeaders.ContentLength]?.toLong() ?: outgoingContent.contentLength

        val connection: HttpURLConnection = getProxyAwareConnection(url).apply {
            connectTimeout = config.connectTimeout
            readTimeout = config.socketTimeout

            setupTimeoutAttributes(data.attributes)

            if (this is HttpsURLConnection) {
                config.sslManager(this)
            }

            requestMethod = data.method.value
            useCaches = false
            instanceFollowRedirects = false

            mergeHeaders(data.headers, outgoingContent) { key: String, value: String ->
                addRequestProperty(key, value)
            }

            config.requestConfig(this)

            if (outgoingContent !is OutgoingContent.NoContent) {
                if (data.method in listOf(HttpMethod.Get, HttpMethod.Head)) throw RequestInvalidException(
                    "Request of type ${data.method} couldn't send a body with the [Android] engine."
                )

                if (contentLength == null && getRequestProperty(HttpHeaders.TransferEncoding) == null) {
                    addRequestProperty(HttpHeaders.TransferEncoding, "chunked")
                }

                contentLength?.let { setFixedLengthStreamingMode(it.toInt()) } ?: setChunkedStreamingMode(0)
                doOutput = true

                outgoingContent.writeTo(outputStream, callContext)
            }
        }

        connection.timeoutAwareConnect()

        val statusCode = HttpStatusCode(connection.responseCode, connection.responseMessage)
        val content: ByteReadChannel = connection.content(callContext)
        val headerFields: MutableMap<String?, MutableList<String>> = connection.headerFields
        val version: HttpProtocolVersion = HttpProtocolVersion.HTTP_1_1

        val responseHeaders = HeadersBuilder().apply {
            headerFields.forEach { (key: String?, values: MutableList<String>) ->
                if (key != null) appendAll(key, values)
            }
        }.build()

        return HttpResponseData(statusCode, requestTime, responseHeaders, version, content, callContext)
    }

    private fun getProxyAwareConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        val connection: URLConnection = config.proxy?.let { url.openConnection(it) } ?: url.openConnection()
        return connection as HttpURLConnection
    }
}

internal suspend fun OutgoingContent.writeTo(
    stream: OutputStream, callContext: CoroutineContext
): Unit = stream.use { blockingOutput ->
    when (this) {
        is OutgoingContent.ByteArrayContent -> blockingOutput.write(bytes())
        is OutgoingContent.ReadChannelContent -> run {
            readFrom().copyTo(blockingOutput)
        }
        is OutgoingContent.WriteChannelContent -> {
            val channel = GlobalScope.writer(callContext) {
                writeTo(channel)
            }.channel

            channel.copyTo(blockingOutput)
        }
        else -> throw UnsupportedContentTypeException(this)
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

@Suppress("KDocMissingDocumentation")
internal class RequestInvalidException(override val message: String) : IllegalStateException()

/**
 * Setup [HttpURLConnection] timeout configuration using [HttpTimeoutAttributes] as a source. These attributes are
 * introduced by [HttpTimeout] client feature.
 */
private fun HttpURLConnection.setupTimeoutAttributes(attributes: Attributes) {
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
private suspend fun HttpURLConnection.timeoutAwareConnect() {
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

/**
 * Return [ByteReadChannel] similar to the original channel, but with specified [ByteChannel.close] handler.
 */
private fun ByteReadChannel.withCloseHandler(
    block: (Throwable?, (Throwable?) -> Boolean) -> Boolean
): ByteReadChannel = ByteChannel(autoFlush = true).also {
    GlobalScope.launch {
        joinTo(
            object : ByteChannel by it {
                override fun close(cause: Throwable?) = block(cause, it::close)
            },
            closeOnEnd = true
        )
    }
}

/**
 * Returns [ByteReadChannel] with [ByteChannel.close] handler that returns [HttpSocketTimeoutException] instead of
 * [SocketTimeoutException].
 */
private fun ByteReadChannel.withSocketTimeoutExceptionMapping(): ByteReadChannel =
    withCloseHandler { cause, close ->
        close(
            when (cause?.rootCause) {
                is SocketTimeoutException -> HttpSocketTimeoutException()
                else -> cause
            }
        )
    }

/**
 * Root cause of the [Throwable].
 */
private val Throwable.rootCause: Throwable?
    get() {
        var rootCause: Throwable? = this
        while (rootCause?.cause != null) {
            rootCause = rootCause.cause
        }
        return rootCause
    }
