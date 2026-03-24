package com.bitnesttechs.hms.patient.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

/**
 * Retrofit [Converter.Factory] that transparently unwraps the backend's
 * standard envelope:
 *   { "success": true, "message": "...", "data": <payload> }
 *
 * For API functions returning [ApiResponse] directly the converter is skipped
 * so callers that need the full envelope still work.
 * For functions returning [Unit] / [Nothing] (fire-and-forget endpoints) it is
 * also skipped.
 *
 * Must be registered BEFORE [kotlinx.serialization.json.Json.asConverterFactory].
 */
class ApiEnvelopeConverterFactory(private val json: Json) : Converter.Factory() {

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<ResponseBody, *>? {
        val rawType = getRawType(type)
        // Let ApiResponse<T> and Unit pass through to the standard converter
        if (rawType == ApiResponse::class.java) return null
        if (rawType == Unit::class.java || rawType == Void::class.java) return null

        @Suppress("UNCHECKED_CAST")
        val serializer = serializer(type) as KSerializer<Any?>

        return Converter<ResponseBody, Any?> { body ->
            val source = body.string()
            val element = json.parseToJsonElement(source)
            // If the response is a wrapped envelope, extract "data"
            val target = if (element is JsonObject && element.containsKey("data")) {
                element["data"]!!
            } else {
                element
            }
            json.decodeFromJsonElement(serializer, target)
        }
    }
}
