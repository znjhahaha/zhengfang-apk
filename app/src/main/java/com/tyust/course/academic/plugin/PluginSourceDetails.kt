package com.tyust.course.academic.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Metadata from another release must never label the installed package's source. */
internal object PluginSourceDetails {
    fun release(pkg: PluginPackage, metadata: JSONObject?): JSONObject? {
        if (!pkg.official || metadata?.optString("id") != pkg.manifest.id) return null
        val versions = listOf(metadata) + listOf("releases", "versions").flatMap {
            metadata.optJSONArray(it)?.let(PluginJson::objects).orEmpty()
        }
        return versions.firstOrNull { it.optString("version") == pkg.manifest.version }
    }
    fun source(pkg: PluginPackage, metadata: JSONObject?): JSONObject? = release(pkg, metadata)?.optJSONObject("source")?.takeIf {
        it.optString("version") == pkg.manifest.version && it.optString("sha256").matches(Regex("[a-f0-9]{64}"))
    }
    fun repository(value: String): String? = value.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty()
    }?.toString()
    fun page(pkg: PluginPackage) = "/plugins/${pkg.manifest.id}?version=${pkg.manifest.version}#source-${pkg.manifest.version}"
    fun download(pkg: PluginPackage) = "/api/plugins/${pkg.manifest.id}/versions/${pkg.manifest.version}/source"
}
