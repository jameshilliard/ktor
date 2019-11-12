/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.utils

import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*

/**
 * Return [ByteReadChannel] similar to the original channel, but with specified [ByteChannel.close] handler.
 */
@InternalAPI
fun ByteReadChannel.withCloseHandler(
    block: (Throwable?, Throwable?, (Throwable?) -> Boolean) -> Boolean
): ByteReadChannel = ByteChannel(autoFlush = true).also {
    GlobalScope.launch {
        joinTo(
            object : ByteChannel by it {
                override fun close(cause: Throwable?) = block(cause, cause?.rootCause, it::close)
            },
            closeOnEnd = true
        )
    }
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
