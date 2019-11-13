/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.jetty

import io.ktor.client.engine.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.util.*
import kotlinx.coroutines.*
import org.eclipse.jetty.http2.client.*
import org.eclipse.jetty.util.thread.*
import java.util.*
import java.util.LinkedHashMap
import javax.management.*

/**
 * Size of the cache that keeps least recently used [HTTP2Client] instances.
 */
private const val CLIENT_CACHE_SIZE = 10

internal class JettyHttp2Engine(override val config: JettyEngineConfig) : HttpClientEngineBase("ktor-jetty") {

    override val dispatcher: CoroutineDispatcher by lazy {
        Dispatchers.fixedThreadPoolDispatcher(
            config.threadsCount,
            "ktor-jetty-thread-%d"
        )
    }

    /**
     * Cache that keeps least recently used [HTTP2Client] instances.
     */
    private val clientCache = createLRUCache(::createJettyClient, HTTP2Client::stop, CLIENT_CACHE_SIZE)

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val jettyClient = clientCache[data.attributes] ?: error("Http2Client can't be constructed")

        return data.executeRequest(jettyClient, config, callContext)
    }

    override fun close() {
        super.close()

        coroutineContext[Job]!!.invokeOnCompletion {
            clientCache.forEach { (_, client) -> client.stop() }
        }
    }

    private fun createJettyClient(attributes: Attributes): HTTP2Client = HTTP2Client().apply {
        addBean(config.sslContextFactory)
        check(config.proxy == null) { "Proxy unsupported in Jetty engine." }

        executor = QueuedThreadPool().apply {
            name = "ktor-jetty-client-qtp"
        }

        setupTimeoutAttributes(attributes.getOrNull(HttpTimeout.Configuration.key))

        start()
    }
}

/**
 * Update [HTTP2Client] to use connect and socket timeouts specified by [HttpTimeout] feature.
 */
private fun HTTP2Client.setupTimeoutAttributes(timeoutAttributes: HttpTimeout.Configuration?) {
    timeoutAttributes?.connectTimeout?.let {
        connectTimeout = when (it) {
            0L -> Long.MAX_VALUE
            else -> it
        }
    }
    timeoutAttributes?.socketTimeout?.let { idleTimeout = it }
}
