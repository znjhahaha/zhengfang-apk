package com.tyust.course.academic.plugin

import org.json.JSONObject

data class PluginService(val pkg: PluginPackage, val contract: JSONObject) {
    fun identity(): JSONObject = JSONObject().put("providerId", pkg.manifest.id)
        .put("providerVersion", pkg.manifest.version).put("digest", pkg.digest)
        .put("name", contract.getString("name")).put("version", contract.getInt("version"))
        .put("kind", contract.getString("kind")).put("title", contract.getString("title"))
}

/** Only explicit, versioned dependencies cross a plugin boundary. */
object PluginServiceDirectory {
    fun declared(caller: PluginManifest, name: String, version: Int): Boolean =
        listOf("serviceDependencies", "services").any { field ->
            caller.json.optJSONArray(field)?.let(PluginJson::objects).orEmpty().any {
                it.getString("name") == name && it.getInt("version") == version
            }
        }

    fun discover(caller: PluginManifest, packages: List<PluginPackage>, name: String, version: Int): List<PluginService> {
        if (!declared(caller, name, version)) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "插件未声明此服务及版本")
        return packages.flatMap { pkg ->
            pkg.manifest.json.optJSONArray("services")?.let(PluginJson::objects).orEmpty()
                .filter { it.getString("name") == name && it.getInt("version") == version }
                .map { PluginService(pkg, it) }
        }.sortedBy { it.pkg.manifest.id }
    }

    fun validateInput(service: PluginService, value: Any?) = PluginSchema(service.contract.getJSONObject("input")).validate(value)
    fun validateOutput(service: PluginService, value: Any?) = PluginSchema(service.contract.getJSONObject("output")).validate(value)
    fun validateReceipt(service: PluginService, receipt: JSONObject) {
        // A confirmed write has the same output contract as a read. Invalid
        // receipts must remain unknown so that reconciliation can establish truth.
        if (receipt.optString("status") == "confirmed") validateOutput(service, receipt.opt("value") ?: JSONObject.NULL)
    }
}
