/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.tests

import io.ktor.client.call.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.response.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlin.test.*
import kotlin.time.*

class HttpTimeoutTest : ClientLoader() {
    @Test
    fun getTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 500 }
        }

        test { client ->
            val response = client.get<String>("$TEST_SERVER/timeout/with-delay?delay=10")
            assertEquals("Text", response)
        }
    }

    @Test
    fun getRequestTimeoutTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 10 }
        }

        test { client ->
            assertFails {
                client.get<String>("$TEST_SERVER/timeout/with-delay?delay=5000")
            }
        }
    }

    @Test
    fun getRequestTimeoutPerRequestAttributesTest() = clientTests {
        config {
            install(HttpTimeout)
        }

        test { client ->
            assertFails {
                client.get<String>("$TEST_SERVER/timeout/with-delay?delay=5000") {
                    attributes.put(
                        HttpTimeoutAttributes.key,
                        HttpTimeoutAttributes(requestTimeout = 10)
                    )
                }
            }
        }
    }

    @Test
    fun getWithSeparateReceiveTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 500 }
        }

        test { client ->
            val call = client.call("$TEST_SERVER/timeout/with-delay?delay=10") { method = HttpMethod.Get }
            val res: String = call.receive()

            assertEquals("Text", res)
        }
    }

    @Test
    fun getWithSeparateReceivePerRequestAttributesTest() = clientTests {
        config {
            install(HttpTimeout)
        }

        test { client ->
            val call = client.call("$TEST_SERVER/timeout/with-delay?delay=10") {
                method = HttpMethod.Get
                attributes.put(
                    HttpTimeoutAttributes.key,
                    HttpTimeoutAttributes(requestTimeout = 500)
                )
            }
            val res: String = call.receive()

            assertEquals("Text", res)
        }
    }

    @Test
    fun getRequestTimeoutWithSeparateReceiveTest() = clientTests(
        listOf("native")                                // Native performs receiving inside call.
    ) {
        config {
            install(HttpTimeout) { requestTimeout = 1000 }
        }

        test { client ->
            val call = client.call("$TEST_SERVER/timeout/with-stream?delay=500") { method = HttpMethod.Get }
            assertFailsWithRootCause<HttpRequestTimeoutException> {
                call.receive<String>()
            }
        }
    }

    @Test
    fun getRequestTimeoutWithSeparateReceivePerRequestAttributesTest() = clientTests(
        listOf("native")                                // Native performs receiving inside call.
    ) {
        config {
            install(HttpTimeout)
        }

        test { client ->
            val call = client.call("$TEST_SERVER/timeout/with-stream?delay=500") {
                method = HttpMethod.Get
                attributes.put(
                    HttpTimeoutAttributes.key,
                    HttpTimeoutAttributes(requestTimeout = 1000)
                )
            }
            assertFailsWithRootCause<HttpRequestTimeoutException> {
                call.receive<String>()
            }
        }
    }

    @Test
    fun getStreamTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 500 }
        }

        test { client ->
            val response = client.get<ByteArray>("$TEST_SERVER/timeout/with-stream?delay=10")

            assertEquals("Text", String(response))
        }
    }

    @Test
    fun getStreamPerRequestAttributesTest() = clientTests {
        config {
            install(HttpTimeout)
        }

        test { client ->
            val response = client.get<ByteArray>("$TEST_SERVER/timeout/with-stream?delay=10") {
                attributes.put(
                    HttpTimeoutAttributes.key,
                    HttpTimeoutAttributes(requestTimeout = 500)
                )
            }

            assertEquals("Text", String(response))
        }
    }

    @Test
    fun getStreamRequestTimeoutTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 500 }
        }

        test { client ->
            assertFailsWithRootCause<HttpRequestTimeoutException> {
                client.get<ByteArray>("$TEST_SERVER/timeout/with-stream?delay=200")
            }
        }
    }

    @Test
    fun getStreamRequestTimeoutPerRequestAttributesTest() = clientTests {
        config {
            install(HttpTimeout)
        }

        test { client ->
            assertFailsWithRootCause<HttpRequestTimeoutException> {
                client.get<ByteArray>("$TEST_SERVER/timeout/with-stream?delay=200") {
                    attributes.put(
                        HttpTimeoutAttributes.key,
                        HttpTimeoutAttributes(requestTimeout = 500)
                    )
                }
            }
        }
    }

    @Test
    fun redirectTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 500 }
        }

        test { client ->
            val response = client.get<String>("$TEST_SERVER/timeout/with-redirect?delay=10&count=2")
            assertEquals("Text", response)
        }
    }

    @Test
    fun redirectPerRequestAttributesTest() = clientTests {
        config {
            install(HttpTimeout)
        }

        test { client ->
            val response = client.get<String>("$TEST_SERVER/timeout/with-redirect?delay=10&count=2") {
                attributes.put(
                    HttpTimeoutAttributes.key,
                    HttpTimeoutAttributes(requestTimeout = 500)
                )
            }
            assertEquals("Text", response)
        }
    }

    @Test
    fun redirectRequestTimeoutOnFirstStepTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 10 }
        }

        test { client ->
            assertFailsWithRootCause<HttpRequestTimeoutException> {
                client.get<String>("$TEST_SERVER/timeout/with-redirect?delay=500&count=5")
            }
        }
    }

    @Test
    fun redirectRequestTimeoutOnFirstStepPerRequestAttributesTest() = clientTests {
        config {
            install(HttpTimeout)
        }

        test { client ->
            assertFailsWithRootCause<HttpRequestTimeoutException> {
                client.get<String>("$TEST_SERVER/timeout/with-redirect?delay=500&count=5") {
                    attributes.put(
                        HttpTimeoutAttributes.key,
                        HttpTimeoutAttributes(requestTimeout = 10)
                    )
                }
            }
        }
    }

    @Test
    fun redirectRequestTimeoutOnSecondStepTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 200 }
        }

        test { client ->
            assertFailsWithRootCause<HttpRequestTimeoutException> {
                client.get<String>("$TEST_SERVER/timeout/with-redirect?delay=250&count=5")
            }
        }
    }

    @Test
    fun redirectRequestTimeoutOnSecondStepPerRequestAttributesTest() = clientTests {
        config {
            install(HttpTimeout)
        }

        test { client ->
            assertFailsWithRootCause<HttpRequestTimeoutException> {
                client.get<String>("$TEST_SERVER/timeout/with-redirect?delay=250&count=5") {
                    attributes.put(
                        HttpTimeoutAttributes.key,
                        HttpTimeoutAttributes(requestTimeout = 200)
                    )
                }
            }
        }
    }

    @Test
    fun connectTimeoutTest() = clientTests(
        listOf("js"),                               // JS doesn't support connect timeout.
        listOf("io.ktor.client.engine.ios.Ios")     // iOS doesn't support connect timeout.
    ) {
        config {
            install(HttpTimeout) { connectTimeout = 1000 }
        }

        test { client ->
            assertFailsWithRootCause<HttpConnectTimeoutException> {
                client.get<String>("http://www.google.com:81")
            }
        }
    }

    @Test
    fun connectTimeoutPerRequestAttributesTest() = clientTests(
        listOf("js"),                               // JS doesn't support connect timeout.
        listOf("io.ktor.client.engine.ios.Ios")     // iOS doesn't support connect timeout.
    ) {
        config {
            install(HttpTimeout)
        }

        test { client ->
            assertFailsWithRootCause<HttpConnectTimeoutException> {
                client.get<String>("http://www.google.com:81") {
                    attributes.put(
                        HttpTimeoutAttributes.key,
                        HttpTimeoutAttributes(connectTimeout = 1000)
                    )
                }
            }
        }
    }

    @Test
    fun socketTimeoutReadTest() = clientTests(
        listOf("js"),                               // JS doesn't support socket timeout.
        listOf("io.ktor.client.engine.curl.Curl")   // Curl doesn't support socket timeout.
    ) {
        config {
            install(HttpTimeout) { socketTimeout = 1000 }
        }

        test { client ->
            assertFailsWithRootCause<HttpSocketTimeoutException> {
                client.get<String>("$TEST_SERVER/timeout/with-stream?delay=5000")
            }
        }
    }

    @Test
    fun socketTimeoutReadPerRequestAttributesTest() = clientTests(
        listOf("js"),                               // JS doesn't support socket timeout.
        listOf("io.ktor.client.engine.curl.Curl")   // Curl doesn't support socket timeout.
    ) {
        config {
            install(HttpTimeout)
        }

        test { client ->
            assertFailsWithRootCause<HttpSocketTimeoutException> {
                client.get<String>("$TEST_SERVER/timeout/with-stream?delay=5000") {
                    attributes.put(
                        HttpTimeoutAttributes.key,
                        HttpTimeoutAttributes(socketTimeout = 1000)
                    )
                }
            }
        }
    }

    @Test
    fun socketTimeoutWriteFailOnWriteTest() = clientTests(
        listOf("js"),                               // JS doesn't support socket timeout.
        listOf(
            "io.ktor.client.engine.curl.Curl",      // Curl doesn't support socket timeout.
            "io.ktor.client.engine.android.Android" // Android doesn't support socket timeout on write operations.
        )
    ) {
        config {
            install(HttpTimeout) { socketTimeout = 500 }
        }

        test { client ->
            assertFailsWithRootCause<HttpSocketTimeoutException> {
                client.post("$TEST_SERVER/timeout/slow-read") { body = makeString(4 * 1024 * 1024) }
            }
        }
    }

    @Test
    fun socketTimeoutWriteFailOnWritePerRequestAttributesTest() = clientTests(
        listOf("js"),                               // JS doesn't support socket timeout.
        listOf(
            "io.ktor.client.engine.curl.Curl",      // Curl doesn't support socket timeout.
            "io.ktor.client.engine.android.Android" // Android doesn't support socket timeout on write operations.
        )
    ) {
        config {
            install(HttpTimeout)
        }

        test { client ->
            assertFailsWithRootCause<HttpSocketTimeoutException> {
                client.post("$TEST_SERVER/timeout/slow-read") {
                    body = makeString(4 * 1024 * 1024)
                    attributes.put(
                        HttpTimeoutAttributes.key,
                        HttpTimeoutAttributes(socketTimeout = 500)
                    )
                }
            }
        }
    }
}
