/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.cio

import io.ktor.client.features.*
import io.ktor.network.sockets.*
import kotlin.coroutines.*

/**
 * Exception mapper that catches [SocketTimeoutException] and throws [HttpSocketTimeoutException] instead. Should be
 * used to unify exceptions throws as result of [HttpTimeout] feature setup.
 */
internal class ExceptionMapper : ContinuationInterceptor {
    override val key: CoroutineContext.Key<*>
        get() = ContinuationInterceptor

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        return object : Continuation<T> by continuation {
            override fun resumeWith(result: Result<T>) {
                when {
                    result.exceptionOrNull() !is SocketTimeoutException -> continuation.resumeWith(result)
                    else -> continuation.resumeWithException(HttpSocketTimeoutException())
                }
            }
        }
    }

    companion object Key : CoroutineContext.Key<ExceptionMapper>
}
