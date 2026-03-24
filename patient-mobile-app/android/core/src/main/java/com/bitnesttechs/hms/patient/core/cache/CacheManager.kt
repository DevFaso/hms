package com.bitnesttechs.hms.patient.core.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disk-backed JSON cache with TTL for offline support.
 * Caches API responses as JSON files in the app's cache directory.
 */
@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    json: Json
) {
    @PublishedApi internal val json: Json = json
    @PublishedApi internal val cacheDir: File = File(context.cacheDir, "hms_api_cache").also { it.mkdirs() }
    @PublishedApi internal val defaultTtlMs: Long = 3_600_000L // 1 hour

    /**
     * Store a serializable value with a cache key.
     */
    inline fun <reified T> store(key: String, value: T, ttlMs: Long = defaultTtlMs) {
        try {
            val entry = CacheEntry(
                data = json.encodeToString(value),
                expiresAt = System.currentTimeMillis() + ttlMs
            )
            File(cacheDir, sanitizeKey(key)).writeText(json.encodeToString(entry))
        } catch (_: Exception) {
            // Silent failure — cache is best-effort
        }
    }

    /**
     * Retrieve a cached value if it exists and hasn't expired.
     */
    inline fun <reified T> retrieve(key: String): T? {
        return try {
            val file = File(cacheDir, sanitizeKey(key))
            if (!file.exists()) return null
            val entry = json.decodeFromString<CacheEntry>(file.readText())
            if (entry.expiresAt < System.currentTimeMillis()) {
                file.delete()
                return null
            }
            json.decodeFromString<T>(entry.data)
        } catch (_: Exception) {
            null
        }
    }

    fun remove(key: String) {
        File(cacheDir, sanitizeKey(key)).delete()
    }

    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    @PublishedApi
    internal fun sanitizeKey(key: String): String =
        key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
}

@kotlinx.serialization.Serializable
data class CacheEntry(
    val data: String,
    val expiresAt: Long
)
