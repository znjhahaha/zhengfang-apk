package com.tyust.course.academic.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Immutable submission scope captured from the button the user actually pressed. */
internal class NativeSubmissionGrant private constructor(private val target: String, private val method: String, private val parameters: Map<String, String>) {
    fun matches(request: JSONObject): Boolean {
        val url = request.optString("url").toHttpUrlOrNull() ?: return false
        if (request.optString("purpose") != "mutation" || request.optString("method", "GET") != method || request.has("body") ||
            url.username.isNotBlank() || url.password.isNotBlank() || url.fragment != null || url.newBuilder().query(null).build().toString() != target) return false
        val form = request.optJSONObject("form")
        return parameters.all { (name, expected) ->
            val query = url.queryParameterValues(name)
            if (form?.has(name) == true) query.isEmpty() && form.optString(name) == expected else query.singleOrNull() == expected
        }
    }
    companion object {
        fun fromButton(manifest: PluginManifest, node: JSONObject, event: JSONObject, userGesture: Boolean): NativeSubmissionGrant? {
            if (!userGesture || event.optString("type") != "click" || node.optString("type") != "button" ||
                !node.optBoolean("enabled", true) || node.optString("event") != event.optString("name") ||
                PluginJson.objects(manifest.json.optJSONArray("requires") ?: JSONArray()).none { it.optString("name") == "ui.components" && it.optInt("version") >= 2 }) return null
            val scope = node.optJSONObject("submission") ?: return null
            val url = scope.optString("url").toHttpUrlOrNull() ?: return null
            val method = scope.optString("method")
            val values = scope.optJSONObject("parameters") ?: return null
            if (!url.isHttps || url.query != null || url.fragment != null || method !in setOf("GET", "POST") || values.length() !in 1..16) return null
            val parameters = values.keys().asSequence().associateWith { values.optString(it) }
            if (parameters.any { it.key.isBlank() || it.value.isBlank() }) return null
            val query = url.newBuilder().apply { if (method == "GET") parameters.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
            PluginNetworkPolicy(manifest.network).requireAllowed(query, method, "mutation", if (method == "POST") JSONObject(parameters) else null)
            return NativeSubmissionGrant(url.toString(), method, parameters)
        }
    }
}
