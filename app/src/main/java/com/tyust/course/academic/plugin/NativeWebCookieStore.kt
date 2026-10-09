package com.tyust.course.academic.plugin

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Encrypted native login for WebViews without profiles. Never uses the global CookieManager. */
internal class NativeWebCookieStore(
    private val vault: NativePluginVault,
    origins: Set<String>,
    private val active: () -> Boolean
) {
    private val gates = origins.map(::PluginWebGate)
    private data class Entry(val url: HttpUrl, val cookie: Cookie) {
        fun json() = JSONObject().put("url", url.toString()).put("value", cookie.toString())
    }

    fun header(value: String): String = synchronized(PluginServiceAccounts.lock) {
        requireActive()
        val url = allowed(value) ?: return@synchronized ""
        entries().filter { it.cookie.matches(url) }.joinToString("; ") { "${it.cookie.name}=${it.cookie.value}" }
    }

    fun set(value: String, cookieValue: String) = synchronized(PluginServiceAccounts.lock) {
        requireActive()
        val url = allowed(value) ?: return@synchronized
        val cookie = Cookie.parse(url, cookieValue) ?: return@synchronized
        // Read the shared account vault for every write so concurrent native pages merge updates.
        val updated = entries().filterNot {
            it.cookie.name == cookie.name && it.cookie.domain == cookie.domain && it.cookie.path == cookie.path
        }.toMutableList()
        if (cookie.expiresAt > System.currentTimeMillis()) {
            updated += Entry(url.newBuilder().query(null).fragment(null).build(), cookie)
        }
        vault.put(KEY, JSONObject().put("cookies", JSONArray(updated.map(Entry::json))))
    }

    /** After a provider upgrade, move this account's native cookies into its own WebKit profile. */
    fun migrate(write: (String, String) -> Unit, flush: () -> Unit) = synchronized(PluginServiceAccounts.lock) {
        requireActive()
        if (vault.get(KEY) == null) return@synchronized
        entries().forEach { write(it.url.toString(), it.cookie.toString()) }
        flush()
        vault.remove(KEY)
    }
    internal fun migrationSnapshot(): JSONObject? = synchronized(PluginServiceAccounts.lock) { requireActive(); vault.get(KEY) }
    internal fun finishMigration(snapshot: JSONObject, confirmed: () -> Unit) = synchronized(PluginServiceAccounts.lock) {
        requireActive()
        if (vault.get(KEY)?.toString() != snapshot.toString()) throw PluginException(PluginErrorCode.CONFLICT, "旧会话在迁移期间发生变化，请重试")
        confirmed(); vault.remove(KEY)
    }

    private fun entries(): List<Entry> = PluginJson.objects(vault.get(KEY)?.optJSONArray("cookies") ?: JSONArray()).mapNotNull {
        val url = allowed(it.optString("url")) ?: return@mapNotNull null
        val cookie = Cookie.parse(url, it.optString("value")) ?: return@mapNotNull null
        if (cookie.expiresAt <= System.currentTimeMillis()) null else Entry(url, cookie)
    }

    private fun allowed(value: String): HttpUrl? = value.toHttpUrlOrNull()?.takeIf { url ->
        url.username.isEmpty() && url.password.isEmpty() && gates.any { it.owns(url.toString()) }
    }

    private fun requireActive() {
        if (!active()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
    }

    private companion object { const val KEY = "session:web-native" }
}
