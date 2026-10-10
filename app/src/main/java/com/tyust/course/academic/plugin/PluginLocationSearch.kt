package com.tyust.course.academic.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

internal object PluginLocationSearch {
    data class Place(val name: String, val point: PluginCoordinates.Point, val title: String, val address: String)
    val USER_AGENT = "ZhengfangCourse/${com.tyust.course.BuildConfig.VERSION_NAME} (https://github.com/znjhahaha/zhengfang-apk)"
    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private var requestedAt = 0L
    private val cache = linkedMapOf<String, List<Place>>()

    // Search is an explicit button action, never autocomplete or background polling.
    suspend fun search(query: String): List<Place> = mutex.withLock {
        val text = query.trim().take(120)
        require(text.length >= 2)
        cache[text]?.let { return@withLock it }
        val wait = 1100 - (android.os.SystemClock.elapsedRealtime() - requestedAt)
        if (wait > 0) delay(wait)
        requestedAt = android.os.SystemClock.elapsedRealtime()
        val found = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(searchUrl(text)).header("User-Agent", USER_AGENT).build()).execute().use { response ->
                check(response.isSuccessful)
                parseResults(response.body?.byteStream()?.use { String(it.readBytesBounded(128 * 1024), Charsets.UTF_8) } ?: "[]")
            }
        }
        if (cache.size >= 16) cache.remove(cache.keys.first())
        cache[text] = found
        found
    }

    internal fun searchUrl(query: String) = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
        .addQueryParameter("format", "jsonv2").addQueryParameter("q", query.trim().take(120))
        .addQueryParameter("limit", "6").addQueryParameter("accept-language", "zh-CN").build()

    internal fun parseResults(raw: String): List<Place> = PluginJson.objects(JSONArray(raw)).mapNotNull { entry ->
        val latitude = entry.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
        val longitude = entry.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
        val point = runCatching { PluginCoordinates.convert(PluginCoordinates.Point(latitude, longitude), "WGS84", "WGS84") }.getOrNull() ?: return@mapNotNull null
        val address = entry.optString("display_name").take(500)
        val title = entry.optString("name").ifBlank { address.substringBefore(',') }.take(120)
        if (title.isBlank()) null else Place(address.ifBlank { title }, point, title, address)
    }.distinctBy { Triple(it.title, it.point.latitude, it.point.longitude) }.take(6)
}
