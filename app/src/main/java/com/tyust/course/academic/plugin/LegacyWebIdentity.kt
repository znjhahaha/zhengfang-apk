package com.tyust.course.academic.plugin

import org.json.JSONObject

/** The legacy process can own exactly one immutable storage identity until it exits. */
internal data class LegacyWebIdentity(val profile: String, val origins: Set<String>) {
    init {
        if (!validProfile(profile)) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "无效网页账号")
        if (origins.isEmpty() || origins.size > 100 || origins.any { !it.startsWith("https://") || PluginWebPolicy.exactOrigin(it) != it })
            throw PluginException(PluginErrorCode.UNTRUSTED_URL, "网页账号来源无效")
    }
    fun requireProfile(value: String) {
        if (value != profile) throw PluginException(PluginErrorCode.STALE_CONTEXT, "网页账号已改变")
    }
    fun accepts(url: String) = origins.any { PluginWebGate(it).owns(url) }
    fun browser(origin: String, declaration: JSONObject, params: JSONObject): PluginWebPolicy {
        if (origin !in origins) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "网页来源未声明")
        val policy = PluginWebPolicy(origin, declaration, params)
        if (!policy.browser || !policy.allows(policy.initialUrl)) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "兼容网页仅支持无宿主桥的浏览页面")
        return policy
    }
    companion object { fun validProfile(value: String) = value.matches(Regex("plugin_[a-f0-9]{64}")) }
}
