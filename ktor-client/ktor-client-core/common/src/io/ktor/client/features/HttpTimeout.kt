/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.features

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.util.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.*

/**
 * Client HTTP timeout feature. There are no default values, so default timeouts will be taken from engine configuration
 * or considered as infinite time if engine doesn't provide them.
 */
class HttpTimeout(
    private val requestTimeout: Long?,
    private val connectTimeout: Long?,
    private val socketTimeout: Long?
) {
    /**
     * [HttpTimeout] configuration that is used during installation.
     */
    class Configuration {
        /**
         * Request timeout in milliseconds.
         */
        var requestTimeout: Long? = null

        /**
         * Connect timeout in milliseconds.
         */
        var connectTimeout: Long? = null

        /**
         * Socket timeout in milliseconds.
         */
        var socketTimeout: Long? = null

        internal fun build(): HttpTimeout = HttpTimeout(requestTimeout, connectTimeout, socketTimeout)
    }

    /**
     * Companion object for feature installation.
     */
    companion object Feature : HttpClientFeature<Configuration, HttpTimeout> {

        override val key: AttributeKey<HttpTimeout> = AttributeKey("Timeout")

        override fun prepare(block: Configuration.() -> Unit): HttpTimeout = Configuration().apply(block).build()

        @UseExperimental(InternalCoroutinesApi::class)
        override fun install(feature: HttpTimeout, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.Before) {
                if (!context.attributes.contains(HttpTimeoutAttributes.key)) {
                    context.attributes.put(HttpTimeoutAttributes.key, HttpTimeoutAttributes())
                }

                context.attributes[HttpTimeoutAttributes.key].apply {
                    connectTimeout = connectTimeout ?: feature.connectTimeout
                    socketTimeout = socketTimeout ?: feature.socketTimeout
                    requestTimeout = requestTimeout ?: feature.requestTimeout

                    val requestTimeout = requestTimeout ?: feature.requestTimeout
                    if (requestTimeout == null || requestTimeout == 0L) return@apply

                    val executionContext = context.executionContext!!
                    val killer = GlobalScope.launch {
                        delay(requestTimeout)
                        executionContext.cancel(HttpRequestTimeoutException())
                    }

                    context.executionContext!!.invokeOnCompletion {
                        killer.cancel()
                    }
                }
            }
        }
    }
}

/**
 * This exception is thrown in case request timeout exceeded.
 */
class HttpRequestTimeoutException() : CancellationException("Request timeout has been expired")

/**
 * This exception is thrown in case connect timeout exceeded.
 */
expect open class HttpConnectTimeoutException : Throwable

/**
 * This exception is thrown in case socket timeout exceeded.
 */
expect open class HttpSocketTimeoutException : Throwable

/**
 * Container for timeout attributes to be stored in [HttpRequest.attributes].
 */
data class HttpTimeoutAttributes(
    var requestTimeout: Long? = null,
    var connectTimeout: Long? = null,
    var socketTimeout: Long? = null
) {
    companion object {
        val key = AttributeKey<HttpTimeoutAttributes>("TimeoutAttributes")
    }
}
