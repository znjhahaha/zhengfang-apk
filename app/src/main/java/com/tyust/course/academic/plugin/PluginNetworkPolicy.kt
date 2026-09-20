package com.tyust.course.academic.plugin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Exact origins, bounded paths and explicit request purposes; rechecked on every redirect. */
class PluginNetworkPolicy(private val rules: List<JSONObject>) {
    fun requireAllowed(url: HttpUrl, method: String, purpose: String, form: JSONObject?) {
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            Regex("(?i)%2f|%5c|%00").containsMatchIn(url.encodedPath)) denied()
        val allowed = rules.any { rule ->
            val origin = rule.getString("origin").toHttpUrlOrNull() ?: return@any false
            val prefix = rule.getString("pathPrefix")
            origin.scheme == url.scheme && origin.host == url.host && origin.port == url.port &&
                (url.encodedPath == prefix || url.encodedPath.startsWith(prefix.trimEnd('/') + "/")) &&
                method in PluginJson.strings(rule.getJSONArray("methods")) &&
                purpose in PluginJson.strings(rule.getJSONArray("purposes")) &&
                matches(rule.optJSONObject("requiredQuery")) { url.queryParameterValues(it).singleOrNull() } &&
                matches(rule.optJSONObject("requiredForm")) { form?.optString(it) }
        }
        if (!allowed) denied()
    }

    companion object {
        fun validateRule(rule: JSONObject) {
            val url = rule.getString("origin").toHttpUrlOrNull() ?: denied()
            val path = rule.getString("pathPrefix")
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.encodedPath != "/" ||
                url.query != null || url.fragment != null || !path.startsWith('/') ||
                path.contains('\\') || path.contains('%') || path.split('/').any { it == "." || it == ".." } ||
                PluginJson.strings(rule.getJSONArray("methods")).isEmpty() ||
                PluginJson.strings(rule.getJSONArray("purposes")).isEmpty()) denied()
        }
        private fun matches(required: JSONObject?, get: (String) -> String?): Boolean =
            required == null || required.keys().asSequence().all { get(it) == required.getString(it) }
        private fun denied(): Nothing = throw PluginException(PluginErrorCode.UNTRUSTED_URL, "请求超出适配声明的网络范围")
    }
}
