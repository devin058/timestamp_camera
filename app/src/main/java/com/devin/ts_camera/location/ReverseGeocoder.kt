package com.devin.ts_camera.location

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns GPS coordinates into a human-readable street address.
 *
 * Uses Nominatim (OpenStreetMap): free, no API key, no registration. It is the
 * only free option tested that returns street-level detail — BigDataCloud's
 * no-key endpoint stops at district level and rejects some dense urban points
 * outright.
 *
 * Nominatim's usage policy requires an identifying User-Agent and at most one
 * request per second, so callers must throttle; [ReverseGeocoder] also caches
 * by rounded coordinate to avoid repeat lookups.
 *
 * Lookups are best-effort — callers must fall back to raw coordinates when
 * this returns null (offline, rate limited, or an unmapped point).
 */
object ReverseGeocoder {

    private const val TAG = "ReverseGeocoder"
    private const val ENDPOINT = "https://nominatim.openstreetmap.org/reverse"
    private const val TIMEOUT_MS = 8_000
    private const val USER_AGENT = "TS_Camera/1.0 (Android timestamp camera)"

    /** Minimum gap between requests, per Nominatim's usage policy. */
    private const val MIN_INTERVAL_MS = 1_100L

    /** Coordinate precision kept in the cache key (~100 m). */
    private const val CACHE_DECIMALS = 3

    private val cache = object : LinkedHashMap<String, String?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) =
            size > 64
    }
    private var lastRequestAt = 0L

    /**
     * @return a one-line address such as "南池子大街, 东华门街道, 东城区, 北京市",
     *         or null when the lookup fails or the point has no address.
     */
    suspend fun lookup(latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            val key = cacheKey(latitude, longitude)
            synchronized(cache) { cache[key] }?.let { return@withContext it }
            if (synchronized(cache) { cache.containsKey(key) }) {
                // A cached null means this point was already known to fail.
                return@withContext null
            }

            val result = request(key, latitude, longitude)
            synchronized(cache) { cache[key] = result }
            result
        }

    private fun cacheKey(lat: Double, lon: Double): String {
        val f = "%.${CACHE_DECIMALS}f"
        return "${f.format(lat)},${f.format(lon)}"
    }

    private fun request(key: String, lat: Double, lon: Double): String? {
        throttle()
        val url = "$ENDPOINT?format=jsonv2&lat=$lat&lon=$lon&accept-language=zh-CN&zoom=18"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP ${connection.responseCode} for $key")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocode failed for $key", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Keeps requests under Nominatim's one-per-second limit. */
    private fun throttle() {
        synchronized(cache) {
            val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                }
            }
            lastRequestAt = System.currentTimeMillis()
        }
    }

    /**
     * Builds "road, suburb, district, city, state" from Nominatim's `address`
     * object, skipping levels that are missing and collapsing duplicates.
     *
     * Example response for a Beijing point:
     * ```
     * { "address": { "road": "南池子大街", "suburb": "东华门街道",
     *                "city": "东城区", "state": "北京市" } }
     * ```
     */
    internal fun parse(body: String): String? = try {
        val root = JSONObject(body)
        val address = root.optJSONObject("address")

        val parts = mutableListOf<String>()
        fun add(value: String?) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) return
            if (parts.any { it.equals(trimmed, ignoreCase = true) }) return
            parts.add(trimmed)
        }

        if (address != null) {
            // Most specific first; house_number rarely exists outside Europe,
            // so it is only used when present.
            add(address.optString("house_number"))
            add(address.optString("road"))
            add(address.optString("neighbourhood"))
            add(address.optString("suburb"))
            add(address.optString("city_district"))
            add(address.optString("district"))
            add(address.optString("city"))
            add(address.optString("state"))
        }

        // Fall back to the full display name if the structured address was
        // empty but Nominatim still recognised the point.
        if (parts.isEmpty()) {
            root.optString("display_name").trim().takeIf { it.isNotEmpty() }
        } else {
            parts.joinToString(", ")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not parse reverse geocode response", e)
        null
    }
}
