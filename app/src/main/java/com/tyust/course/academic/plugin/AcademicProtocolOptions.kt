package com.tyust.course.academic.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Deployment profiles are configuration; bundled protocol code stays independent of schools. */
internal object AcademicProtocolOptions {
    private fun invalid(): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, "教务协议配置无效")
    fun resolve(system: String, base: String, supplied: JSONObject?): JSONObject? {
        if (system !in setOf("eams", "chaoxing_academic")) {
            if (supplied != null) invalid()
            return null
        }
        val endpoint = base.toHttpUrlOrNull() ?: invalid()
        if (!endpoint.isHttps || endpoint.username.isNotEmpty() || endpoint.password.isNotEmpty() ||
            endpoint.query != null || endpoint.fragment != null || endpoint.encodedPath.any { it in "%\\" }) invalid()
        val result = when (system to base) {
            "eams" to "https://www.cduestc.cn/eams" -> JSONObject().put("loginPath", "/loginExt.action").put("homePath", "/homeExt.action").put("examMode", "examTable")
            "chaoxing_academic" to "https://jwxt1.wtc.edu.cn/admin" -> JSONObject().put("loginUrl", "https://authserver.wtc.edu.cn/authserver/login?service=https%3A%2F%2Fjwxt1.wtc.edu.cn%2Fadmin%2Fcaslogin")
            else -> JSONObject()
        }
        supplied?.let { options ->
            if (options.optString("protocol") != system) invalid()
            val keys = if (system == "eams") setOf("protocol", "loginPath", "homePath", "loginUrl", "examMode", "periods") else setOf("protocol", "loginUrl")
            if (options.keys().asSequence().any { it !in keys }) invalid()
            if (options.has("loginUrl")) {
                val login = options.getString("loginUrl").toHttpUrlOrNull() ?: invalid()
                if (!login.isHttps || login.username.isNotEmpty() || login.password.isNotEmpty() || login.fragment != null || login.encodedPath.any { it in "%\\" }) invalid()
            }
            if (options.has("loginPath") && options.getString("loginPath") !in setOf("/login.action", "/loginExt.action")) invalid()
            if (options.has("homePath") && options.getString("homePath") !in setOf("/home.action", "/homeExt.action")) invalid()
            if (options.has("examMode") && options.getString("examMode") !in setOf("form", "examTable")) invalid()
            if (options.has("periods") && options.optJSONArray("periods") == null) invalid()
            options.optJSONArray("periods")?.let { array ->
                val periods = PluginJson.objects(array)
                if (periods.isEmpty() || periods.size > 30 || periods.map { it.optInt("number") }.distinct().size != periods.size) invalid()
                val time = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
                for (p in periods) if (p.optInt("number") !in 1..30 || !time.matches(p.optString("start")) || !time.matches(p.optString("end")) || p.getString("start") >= p.getString("end")) invalid()
            }
            options.keys().forEach { result.put(it, options.get(it)) }
        }
        return result
    }
}
