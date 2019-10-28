/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.okhttp

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.date.*
import kotlinx.coroutines.*
import io.ktor.utils.io.*
import okhttp3.*
import okhttp3.internal.http.HttpMethod
import okio.*
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.*
import kotlin.coroutines.*

@InternalAPI
@Suppress("KDocMissingDocumentation")
class OkHttpEngine(override val config: OkHttpConfig) : HttpClientEngineBase("ktor-okhttp") {

    override val dispatcher by lazy {
        Dispatchers.fixedThreadPoolDispatcher(
            config.threadsCount,
            "ktor-okhttp-thread-%d"
        )
    }

    private val engine: OkHttpClient = config.preconfigured ?: run {
        val builder = OkHttpClient.Builder()
        builder.apply(config.config)

        config.proxy?.let { builder.proxy(it) }
        builder.build()
    }

    private val clientCache = createNewClientCache(maxSize = 8)

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val engineRequest = data.convertToOkHttpRequest(callContext)

        val requestEngine = clientCache.computeIfAbsent(engine, data.attributes)

        return if (data.isUpgradeRequest()) {
            executeWebSocketRequest(requestEngine, engineRequest, callContext)
        } else {
            executeHttpRequest(requestEngine, engineRequest, callContext)
        }
    }

    override fun close() {
        super.close()

        coroutineContext[Job]!!.invokeOnCompletion {
            GlobalScope.launch(dispatcher) {
                engine.dispatcher().executorService().shutdown()
                engine.connectionPool().evictAll()
                engine.cache()?.close()
            }.invokeOnCompletion {
                (dispatcher as Closeable).close()
            }
        }
    }

    private suspend fun executeWebSocketRequest(
        engine: OkHttpClient,
        engineRequest: Request,
        callContext: CoroutineContext
    ): HttpResponseData {
        val requestTime = GMTDate()
        val session = OkHttpWebsocketSession(engine, engineRequest, callContext)

        val originResponse = session.originResponse.await()
        return buildResponseData(originResponse, requestTime, session, callContext)
    }

    private suspend fun executeHttpRequest(
        engine: OkHttpClient,
        engineRequest: Request,
        callContext: CoroutineContext
    ): HttpResponseData {
        println("engine.connectTimeoutMillis() = ${engine.connectTimeoutMillis()}")
        println("engine.readTimeoutMillis() = ${engine.readTimeoutMillis()}")
        println("engine.writeTimeoutMillis() = ${engine.writeTimeoutMillis()}")
        val requestTime = GMTDate()
        val response = engine.execute(engineRequest)

        val body = response.body()
        callContext[Job]!!.invokeOnCompletion { body?.close() }

        val responseContent = body?.source()?.toChannel(callContext) ?: ByteReadChannel.Empty
        return buildResponseData(response, requestTime, responseContent, callContext)
    }

    private fun buildResponseData(
        response: Response, requestTime: GMTDate, body: Any, callContext: CoroutineContext
    ): HttpResponseData {
        val status = HttpStatusCode(response.code(), response.message())
        val version = response.protocol().fromOkHttp()
        val headers = response.headers().fromOkHttp()

        return HttpResponseData(status, requestTime, headers, version, body, callContext)
    }
}

private fun BufferedSource.toChannel(context: CoroutineContext): ByteReadChannel = GlobalScope.writer(context) {
    use { source ->
        var lastRead = 0
        while (source.isOpen && context.isActive && lastRead >= 0) {
            channel.write { buffer ->
                lastRead = try {
                    source.read(buffer)
                } catch (e: Throwable) {
                    throw when (e) {
                        is SocketTimeoutException -> HttpSocketTimeoutException()
                        else -> e
                    }
                }
            }
        }
    }
}.channel

private fun HttpRequestData.convertToOkHttpRequest(callContext: CoroutineContext): Request {
    val builder = Request.Builder()

    with(builder) {
        url(url.toString())

        mergeHeaders(headers, body) { key, value ->
            addHeader(key, value)
        }

        val bodyBytes = if (HttpMethod.permitsRequestBody(method.value)) {
            body.convertToOkHttpBody(callContext)
        } else null


        method(method.value, bodyBytes)
    }

    return builder.build()
}

internal fun OutgoingContent.convertToOkHttpBody(callContext: CoroutineContext): RequestBody? = when (this) {
    is OutgoingContent.ByteArrayContent -> RequestBody.create(null, bytes())
    is OutgoingContent.ReadChannelContent -> StreamRequestBody(contentLength) { readFrom() }
    is OutgoingContent.WriteChannelContent -> {
        StreamRequestBody(contentLength) { GlobalScope.writer(callContext) { writeTo(channel) }.channel }
    }
    is OutgoingContent.NoContent -> RequestBody.create(null, ByteArray(0))
    else -> throw UnsupportedContentTypeException(this)
}

/**
 * Synchronized LRU cache based on [LinkedHashMap] with specified [maxSize].
 */
private fun createNewClientCache(maxSize: Int): MutableMap<HttpTimeoutAttributes?, OkHttpClient> =
    Collections.synchronizedMap(object : LinkedHashMap<HttpTimeoutAttributes?, OkHttpClient>(10, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<HttpTimeoutAttributes?, OkHttpClient>): Boolean {
            return size > maxSize
        }
    })

/**
 * Take [OkHttpClient] from cache or compute a new one if there is no client with specified [attributes].
 */
private fun MutableMap<HttpTimeoutAttributes?, OkHttpClient>.computeIfAbsent(
    baseClient: OkHttpClient,
    attributes: Attributes
): OkHttpClient {
    if (!attributes.contains(HttpTimeoutAttributes.key)) return baseClient
    return attributes[HttpTimeoutAttributes.key].let { timeoutAttributes ->
        synchronized(this) {
            var res = get(timeoutAttributes)
            if (res != null) {
                return res
            }

            res = baseClient.newBuilder()
                .setupTimeoutAttributes(timeoutAttributes)
                .build()

            put(timeoutAttributes, res)

            res
        }
    }
}

private fun OkHttpClient.Builder.setupTimeoutAttributes(timeoutAttributes: HttpTimeoutAttributes): OkHttpClient.Builder {
    timeoutAttributes.connectTimeout?.let { connectTimeout(it, TimeUnit.MILLISECONDS) }
    timeoutAttributes.socketTimeout?.let {
        readTimeout(it, TimeUnit.MILLISECONDS)
        writeTimeout(it, TimeUnit.MILLISECONDS)
    }
    return this
}
