/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.sessions

import io.ktor.application.*
import io.ktor.util.logging.*
import kotlin.reflect.*

/**
 * [SessionTracker] that stores the contents of the session as part of HTTP Cookies/Headers.
 * It uses a specific [serializer] to serialize and deserialize objects of type [type].
 *
 * @property type is a session instance type
 * @property serializer session serializer
 */
class SessionTrackerByValue(val type: KClass<*>, val serializer: SessionSerializer) : SessionTracker {
    override suspend fun load(call: ApplicationCall, transport: String?): Any? {
        return transport?.let { serialized ->
            try {
                serializer.deserialize(serialized)
            } catch (t: Throwable) {
                call.application.log.debug("Failed to deserialize session: $serialized", t)
                null
            }
        }
    }

    override suspend fun store(call: ApplicationCall, value: Any): String {
        val serialized = serializer.serialize(value)
        return serialized
    }

    override fun validate(value: Any) {
        if (!type.javaObjectType.isAssignableFrom(value.javaClass)) {
            throw IllegalArgumentException("Value for this session tracker expected to be of type $type but was $value")
        }
    }

    override suspend fun clear(call: ApplicationCall) {
        // it's stateless, so nothing to clear
    }

    override fun toString(): String {
        return "SessionTrackerByValue"
    }
}

