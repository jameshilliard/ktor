/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.jetty

import io.ktor.client.engine.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import kotlinx.coroutines.*
import org.eclipse.jetty.http2.client.*
import org.eclipse.jetty.util.thread.*
import java.util.*
import java.util.LinkedHashMap

internal class JettyHttp2Engine(override val config: JettyEngineConfig) : HttpClientEngineBase("ktor-jetty") {

    override val dispatcher: CoroutineDispatcher by lazy {
        Dispatchers.fixedThreadPoolDispatcher(
            config.threadsCount,
            "ktor-jetty-thread-%d"
        )
    }

    private val clientCache = createNewClientCache(maxSize = 8)

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val jettyClient = clientCache.computeIfAbsent(config, data.attributes.getOrNull(HttpTimeoutAttributes.key))

        return data.executeRequest(jettyClient, config, callContext)
    }

    override fun close() {
        super.close()

        coroutineContext[Job]!!.invokeOnCompletion {
            clientCache.forEach { (_, client) -> client.stop() }
        }
    }
}

/**
 * Synchronized LRU cache based on [LinkedHashMap] with specified [maxSize].
 */
private fun createNewClientCache(maxSize: Int): MutableMap<HttpTimeoutAttributes?, HTTP2Client> =
    Collections.synchronizedMap(object : LinkedHashMap<HttpTimeoutAttributes?, HTTP2Client>(10, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<HttpTimeoutAttributes?, HTTP2Client>): Boolean {
            val remove = size > maxSize
            if (remove) {
                eldest.value.stop()
            }
            return remove
        }
    })

/**
 * Take [HTTP2Client] from cache or compute a new one if there is no client with specified [timeoutAttributes].
 */
private fun MutableMap<HttpTimeoutAttributes?, HTTP2Client>.computeIfAbsent(
    config: JettyEngineConfig,
    timeoutAttributes: HttpTimeoutAttributes?
): HTTP2Client {
    synchronized(this) {
        var res = get(timeoutAttributes)
        if (res != null) return res

        res = createJettyClient(config, timeoutAttributes)
        put(timeoutAttributes, res)

        return res
    }
}

private fun createJettyClient(config: JettyEngineConfig, timeoutAttributes: HttpTimeoutAttributes?): HTTP2Client = HTTP2Client().apply {
    addBean(config.sslContextFactory)
    check(config.proxy == null) { "Proxy unsupported in Jetty engine." }

    executor = QueuedThreadPool().apply {
        name = "ktor-jetty-client-qtp"
    }

    setupTimeoutAttributes(timeoutAttributes)

    start()
}

/**
 * Update [HTTP2Client] to use connect and socket timeouts specified by [HttpTimeout] feature.
 */
private fun HTTP2Client.setupTimeoutAttributes(timeoutAttributes: HttpTimeoutAttributes?) {
    timeoutAttributes?.connectTimeout?.let {
        connectTimeout = when(it) {
            0L -> Long.MAX_VALUE
            else -> it
        }
    }
    timeoutAttributes?.socketTimeout?.let { idleTimeout = it }
}
