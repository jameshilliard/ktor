/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.util

import kotlinx.coroutines.*
import java.net.*

/**
 * Wrap [block] into [withTimeout] wrapper and throws [SocketTimeoutException] if timeout exceeded.
 */
internal suspend fun CoroutineScope.withSocketTimeout(socketTimeout: Long, block: suspend CoroutineScope.() -> Unit) {
    if (socketTimeout == 0L) {
        block()
    } else {
        async {
            withTimeoutOrNull(socketTimeout, block) ?: throw SocketTimeoutException()
        }.await()
    }
}
