package com.bitnesttechs.hms.patient.core.network

/**
 * The `message` (or `error`) of a Spring `MessageResponse`-style error body,
 * which the backend localises from Accept-Language. Null when the body is
 * empty, has neither field, or is not JSON, so the caller falls back to its
 * own resource text; never the raw body.
 *
 * A regex rather than a JSON parser so it runs in pure-JVM unit tests
 * without Robolectric stubbing `org.json`. Malformed input returns null and
 * never throws.
 */
object ServerMessage {

    fun parse(body: String?): String? {
        if (body.isNullOrBlank()) return null
        for (key in listOf("message", "error")) {
            val match = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
            val raw = match?.groupValues?.getOrNull(1)
            if (!raw.isNullOrBlank()) return unescape(raw)
        }
        return null
    }

    private fun unescape(raw: String): String =
        raw.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
}
