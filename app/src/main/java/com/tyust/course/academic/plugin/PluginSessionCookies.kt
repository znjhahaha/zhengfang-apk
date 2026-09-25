package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.AcademicSession
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Service cookies use the provider's encrypted vault, never the academic cookie jar. */
object PluginSessionCookies {
    private fun vault(app: Context, pkg: PluginPackage, session: AcademicSession) = NativePluginVault(app,
        PluginStorageScope.session(session, pkg.manifest.id, !pkg.official)) {
        if (!PluginServiceAccounts(app).current(pkg, session)) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
    }
    fun restore(app: Context, pkg: PluginPackage, session: AcademicSession) {
        if (!session.key.schoolId.startsWith("service:")) return
        val saved = vault(app, pkg, session).get("session:auto") ?: return
        PluginJson.objects(saved.optJSONArray("cookies") ?: JSONArray()).forEach { item ->
            val url = item.getString("url").toHttpUrlOrNull() ?: return@forEach
            Cookie.parse(url, item.getString("value"))?.let { session.cookies.saveFromResponse(url, listOf(it)) }
        }
    }
    fun save(app: Context, pkg: PluginPackage, session: AcademicSession) = synchronized(PluginServiceAccounts.lock) {
        if (!session.key.schoolId.startsWith("service:") || session.retired || !PluginServiceAccounts(app).current(pkg, session)) return@synchronized
        val cookies = JSONArray(session.cookies.snapshot().map { cookie -> JSONObject()
            .put("url", "${if (cookie.secure) "https" else "http"}://${cookie.domain}${cookie.path}").put("value", cookie.toString()) })
        vault(app, pkg, session).put("session:auto", JSONObject().put("cookies", cookies))
    }
}
