package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-side rules for `contributes.widgets`.
 *
 * A widget contribution is display data only: the plugin publishes a sanitized snapshot and the host
 * renders it with RemoteViews, so widget declarations never carry executable code of their own.
 */
object PluginWidgetContract {
    /** First App version code that ships the widget host runtime. */
    const val MIN_APP_VERSION = 129
    const val DEFAULT_STALE_SECONDS = 21_600L
    const val MAX_SNAPSHOT_BYTES = 64 * 1024
    const val MAX_PLUGIN_BYTES = 256 * 1024
    const val MAX_TIMER_AHEAD_MS = 30L * 24 * 60 * 60 * 1000
    val TEMPLATES = setOf("text", "lines", "grid", "countdown")
    val SIZES = setOf("1x1", "2x1", "2x2", "4x1", "4x2")
    val THEMES = setOf("auto", "light", "dark")

    /** Launcher sizes are grouped into the three static providers the host registers. */
    enum class Family(val label: String, val sizes: Set<String>) {
        Small("1x1 / 2x1 / 4x1", setOf("1x1", "2x1", "4x1")),
        Medium("2x2", setOf("2x2")),
        Large("4x2", setOf("4x2"));

        companion object {
            fun of(size: String): Family? = entries.firstOrNull { size in it.sizes }
            fun accepting(sizes: Collection<String>): Family? =
                entries.firstOrNull { family -> sizes.any { it in family.sizes } }
        }
    }

    data class View(val key: String, val title: String)

    data class Option(val id: String, val title: String, val type: String, val default: Any?, val scope: String)

    data class Declaration(
        val id: String,
        val title: String,
        val description: String,
        val sizes: List<String>,
        val template: String,
        val defaultView: String?,
        val views: List<View>,
        val options: List<Option>,
        val push: Boolean,
        val staleAfterSeconds: Long,
        /** Pull widgets only: the `contributes.dataProviders` entry the host queries. */
        val providerId: String,
        val json: JSONObject,
    ) {
        /** `viewKey` is optional on publish: an omitted key replaces the declared default view. */
        fun viewKey(configured: String?): String {
            val requested = configured?.takeIf(String::isNotBlank)
            if (requested != null) {
                if (views.none { it.key == requested }) widgetInvalid("小组件视图未声明：$requested")
                return requested
            }
            return defaultView ?: views.firstOrNull()?.key.orEmpty()
        }
    }

    fun declarations(manifest: PluginManifest): List<Declaration> {
        val array = manifest.contributes.optJSONArray("widgets") ?: return emptyList()
        return PluginJson.objects(array).map { item ->
            val refresh = item.optJSONObject("refresh")
            val mode = refresh?.optString("mode").orEmpty().takeIf(String::isNotBlank) ?: "push"
            Declaration(
                id = item.optString("id"),
                title = item.optString("title"),
                description = item.optString("description"),
                sizes = PluginJson.strings(item.optJSONArray("sizes")),
                template = item.optString("template"),
                defaultView = item.optString("defaultView").takeIf(String::isNotBlank),
                views = PluginJson.objects(item.optJSONArray("views") ?: JSONArray())
                    .map { View(it.getString("key"), it.optString("title", it.optString("key"))) },
                options = PluginJson.objects(item.optJSONArray("options") ?: JSONArray()).map {
                    Option(it.optString("id"), it.optString("title"), it.optString("type", "string"),
                        it.opt("default"), it.optString("scope", "instance"))
                },
                push = mode != "pull",
                staleAfterSeconds = refresh?.optLong("staleAfterSeconds", DEFAULT_STALE_SECONDS) ?: DEFAULT_STALE_SECONDS,
                providerId = refresh?.optString("providerId").orEmpty(),
                json = item,
            )
        }
    }

    fun declaration(manifest: PluginManifest, id: String): Declaration? =
        declarations(manifest).firstOrNull { it.id == id }

    fun declaresWidgets(manifest: PluginManifest): Boolean =
        (manifest.contributes.optJSONArray("widgets")?.length() ?: 0) > 0

    fun pages(manifest: PluginManifest): Set<String> =
        PluginJson.objects(manifest.contributes.optJSONArray("pages") ?: JSONArray())
            .map { it.optString("id") }.filter(String::isNotBlank).toSet()

    /**
     * Validates the widget extension point of any native-like manifest. `kind:"widget"` must declare at
     * least one widget; a plain native plugin may opt in through `contributes.widgets`.
     */
    fun validate(manifest: PluginManifest) {
        val hasWidgets = declaresWidgets(manifest)
        if (manifest.kind == "widget" && !hasWidgets) widgetInvalid("小组件插件必须声明 contributes.widgets")
        if (!hasWidgets) return
        if ("widget" !in manifest.permissions) widgetInvalid("小组件需要声明 widget 权限")
        if (manifest.capabilities.any { it.startsWith("widget.") })
            widgetInvalid("widget.* 是宿主能力，不能在 capabilities 中声明")
        val requires = PluginJson.objects(manifest.json.optJSONArray("requires") ?: JSONArray())
            .associate { it.optString("name") to it.optInt("version") }
        if ((requires["widget.render"] ?: 0) < 1) widgetInvalid("小组件需要声明 requires: widget.render@1")
        if (manifest.json.optInt("minAppVersionCode") < MIN_APP_VERSION)
            widgetInvalid("小组件需要声明 minAppVersionCode $MIN_APP_VERSION")
        val providerIds = PluginJson.objects(manifest.contributes.optJSONArray("dataProviders") ?: JSONArray())
            .map { it.optString("id") }.filter(String::isNotBlank).toSet()
        val providers = providerIds.size
        val declarations = declarations(manifest)
        val ids = declarations.map { it.id }
        if (ids.toSet().size != ids.size) widgetInvalid("小组件 ID 重复")
        for (widget in declarations) {
            if (widget.id.isBlank()) widgetInvalid("小组件缺少 ID")
            if (widget.title.isBlank()) widgetInvalid("小组件缺少标题")
            if (widget.sizes.isEmpty()) widgetInvalid("小组件 ${widget.id} 未声明尺寸")
            if (widget.sizes.any { it !in SIZES }) widgetInvalid("小组件 ${widget.id} 尺寸无效")
            if (widget.sizes.distinct().size != widget.sizes.size) widgetInvalid("小组件 ${widget.id} 尺寸重复")
            if (widget.template !in TEMPLATES) widgetInvalid("小组件 ${widget.id} 模板无效")
            val keys = widget.views.map { it.key }
            if (keys.distinct().size != keys.size) widgetInvalid("小组件 ${widget.id} 视图 key 重复")
            if (widget.defaultView != null && widget.defaultView !in keys)
                widgetInvalid("小组件 ${widget.id} 默认视图未声明")
            val optionIds = widget.options.map { it.id }
            if (optionIds.distinct().size != optionIds.size) widgetInvalid("小组件 ${widget.id} 配置项 ID 重复")
            if (widget.options.any { it.type !in setOf("string", "boolean", "number") })
                widgetInvalid("小组件 ${widget.id} 配置项类型无效")
            if (widget.push) {
                if ((requires["widget.publish"] ?: 0) < 1) widgetInvalid("推送式小组件需要声明 requires: widget.publish@1")
            } else {
                if (providers == 0) widgetInvalid("拉取式小组件需要声明 contributes.dataProviders")
                if (widget.providerId.isBlank()) widgetInvalid("拉取式小组件 ${widget.id} 需要声明 refresh.providerId")
                if (widget.providerId !in providerIds)
                    widgetInvalid("小组件 ${widget.id} 的 refresh.providerId 未在 contributes.dataProviders 中声明")
            }
            if (widget.options.size > 8) widgetInvalid("小组件 ${widget.id} 配置项过多")
        }
    }

    /**
     * Host-side validation of a published snapshot that the JSON schema cannot express: timer bounds,
     * action page references and the per-update byte budget. Returns a control-character free copy.
     */
    fun sanitize(declaration: Declaration, snapshot: JSONObject, now: Long = System.currentTimeMillis()): JSONObject {
        if (snapshot.optInt("schema") != 1) widgetInvalid("小组件快照 schema 必须为 1")
        val text = JSONObject(snapshot.toString())
        strip(text)
        if (text.toString().toByteArray().size > MAX_SNAPSHOT_BYTES) widgetInvalid("小组件快照超过 64 KiB")
        val actions = PluginJson.objects(text.optJSONArray("actions") ?: JSONArray())
        val actionIds = actions.map { it.optString("id") }
        if (actionIds.distinct().size != actionIds.size) widgetInvalid("小组件操作 ID 重复")
        text.optJSONObject("timer")?.let { timer ->
            val start = timer.optLong("startAt")
            val end = timer.optLong("endAt")
            if (end <= start) widgetInvalid("倒计时结束时间必须晚于开始时间")
            if (end - now > MAX_TIMER_AHEAD_MS) widgetInvalid("倒计时不能超过 30 天")
            if (timer.optBoolean("countdown") && end <= now) timer.put("countdown", false)
        }
        return text
    }

    /** Action targets must stay inside the plugin's own declared pages. */
    fun validateActions(manifest: PluginManifest, snapshot: JSONObject) {
        val pages = pages(manifest)
        PluginJson.objects(snapshot.optJSONArray("actions") ?: JSONArray()).forEach { action ->
            when (action.optString("kind")) {
                "openPage" -> if (action.optString("pageId") !in pages) widgetInvalid("小组件操作指向未声明页面")
                "refresh", "cycle" -> Unit
                else -> widgetInvalid("小组件操作类型无效")
            }
        }
    }

    private fun strip(value: Any?) {
        when (value) {
            is JSONObject -> for (key in value.keys().asSequence().toList()) {
                val child = value.opt(key)
                if (child is String) value.put(key, clean(child)) else strip(child)
            }
            is JSONArray -> for (index in 0 until value.length()) {
                val child = value.opt(index)
                if (child is String) value.put(index, clean(child)) else strip(child)
            }
        }
    }

    private fun clean(text: String): String =
        text.filter { it == '\n' || it == '\t' || !it.isISOControl() }.take(2_000)
}

internal fun widgetInvalid(message: String): Nothing =
    throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
