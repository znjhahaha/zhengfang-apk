package com.tyust.course.academic.plugin

import java.net.URI
import java.util.UUID

/** A grant belongs to one committed main document, not merely to a WebView. */
class PluginWebGate(private val origin: String) {
    private var token: String? = null
    private var url: String? = null
    private val requests = mutableSetOf<String>()
    @Synchronized fun revoke() { token = null; url = null; requests.clear() }
    fun owns(url: String): Boolean = runCatching { val uri = URI(url); uri.userInfo == null && "${uri.scheme}://${uri.rawAuthority}" == origin }.getOrDefault(false)
    @Synchronized fun bind(url: String): String { revoke(); if (!owns(url)) denied(); this.url = url; return UUID.randomUUID().toString().also { token = it } }
    @Synchronized fun documentUrl(): String? = url
    @Synchronized fun current(instance: String): Boolean = token != null && token == instance
    @Synchronized fun document(): String? = token
    @Synchronized fun accept(sourceOrigin: String, mainFrame: Boolean, instance: String, requestId: String) {
        if (!mainFrame || sourceOrigin.trimEnd('/') != origin || !current(instance) || !requestId.matches(Regex("[a-zA-Z0-9_-]{1,96}"))) denied()
        if (requests.size >= 256) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "当前网页调用过多，请刷新页面")
        if (!requests.add(requestId)) throw PluginException(PluginErrorCode.CONFLICT, "网页请求已经处理")
    }
    private fun denied(): Nothing = throw PluginException(PluginErrorCode.STALE_CONTEXT, "网页来源或页面上下文已失效")
}
