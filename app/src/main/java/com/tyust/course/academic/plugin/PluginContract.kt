package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class PluginErrorCode {
    UNSUPPORTED, NOT_OPEN, INVALID_CREDENTIALS, SESSION_EXPIRED, CAPTCHA_REQUIRED, WEB_LOGIN_REQUIRED,
    NO_CAPACITY, CONFLICT, ALREADY_SELECTED, CREDIT_LIMIT, PAGE_CHANGED, NETWORK_RETRYABLE,
    RESULT_UNKNOWN, UNTRUSTED_URL, VALIDATION_FAILED, TIMEOUT, CANCELLED, RUNTIME_EXITED,
    RESOURCE_LIMIT, BAD_SIGNATURE, PERMISSION_DENIED, STALE_CONTEXT
}

class PluginException(val code: PluginErrorCode, message: String, cause: Throwable? = null) : Exception(message, cause)

object PluginLimits {
    const val API_VERSION = 3
    const val MEMORY_BYTES = 64L * 1024 * 1024
    const val STACK_BYTES = 1024L * 1024
    const val JS_MILLIS = 5_000L
    const val WALL_MILLIS = 120_000L
    const val RESPONSE_BYTES = 5 * 1024 * 1024
    const val WIRE_BYTES = 8 * 1024 * 1024
    const val PACKAGE_BYTES = 2 * 1024 * 1024
    const val EXPANDED_BYTES = 8 * 1024 * 1024
    const val STATE_BYTES = 256 * 1024
}

object PluginJson {
    fun parse(text: String): JSONObject {
        requireValid(text)
        return try { JSONObject(text) } catch (e: Exception) {
            throw PluginException(PluginErrorCode.VALIDATION_FAILED, "插件返回了无效 JSON", e)
        }
    }

    fun requireValid(text: String) {
        if (text.toByteArray().size > PluginLimits.WIRE_BYTES) fail("JSON 超过大小限制")
        var depth = 0
        var quoted = false
        var escaped = false
        for (ch in text) {
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 64) fail("JSON 嵌套超过限制")
                '}', ']' -> depth--
            }
        }
        if (depth != 0 || quoted) fail("JSON 结构不完整")
    }

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { quoteCanonical(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> quoteCanonical(value)
        is Boolean -> value.toString()
        is Number -> JSONObject.numberToString(value)
        else -> fail("不支持的 JSON 值")
    }

    // Android's JSONObject.quote escapes '/' whereas JSON.stringify does not.
    // Signature bytes must not depend on the platform JSON implementation.
    private fun quoteCanonical(value: String): String = buildString {
        append('"')
        value.forEachIndexed { index, ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 32 || ch.isHighSurrogate() && (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) ||
                    ch.isLowSurrogate() && (index == 0 || !value[index - 1].isHighSurrogate()))
                    append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch)
            }
        }
        append('"')
    }

    fun success(data: Any? = JSONObject.NULL) = JSONObject().put("ok", true).put("data", data ?: JSONObject.NULL)
    fun error(code: PluginErrorCode, message: String) = JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", code.name).put("message", message.take(2000)))
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
    fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).map { array.getJSONObject(it) }
    private fun fail(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
}

/** Deliberately small JSON Schema evaluator; both platforms consume the same versioned schema. */
class PluginSchema(private val root: JSONObject) {
    fun validate(value: Any?, schema: JSONObject = root, path: String = "$") {
        if (schema.has("\$ref")) {
            val name = schema.getString("\$ref").removePrefix("#/\$defs/")
            return validate(value, root.getJSONObject("\$defs").getJSONObject(name), path)
        }
        schema.optJSONArray("anyOf")?.let { variants ->
            if ((0 until variants.length()).any { runCatching { validate(value, variants.getJSONObject(it), path) }.isSuccess }) return
            fail(path, "数据不符合接口结构")
        }
        if (schema.has("const") && PluginJson.canonical(value) != PluginJson.canonical(schema.get("const"))) fail(path, "固定值不匹配")
        schema.optJSONArray("enum")?.let { choices ->
            if ((0 until choices.length()).none { PluginJson.canonical(value) == PluginJson.canonical(choices.get(it)) }) fail(path, "未知枚举值")
        }
        when (schema.optString("type")) {
            "object" -> {
                val obj = value as? JSONObject ?: fail(path, "应为对象")
                PluginJson.strings(schema.optJSONArray("required")).forEach { if (!obj.has(it)) fail("$path.$it", "缺少字段") }
                val properties = schema.optJSONObject("properties") ?: JSONObject()
                obj.keys().forEach { key ->
                    when {
                        properties.has(key) -> validate(obj.get(key), properties.getJSONObject(key), "$path.$key")
                        schema.opt("additionalProperties") == false -> fail("$path.$key", "不允许额外字段")
                        schema.optJSONObject("additionalProperties") != null -> validate(obj.get(key), schema.getJSONObject("additionalProperties"), "$path.$key")
                    }
                }
            }
            "array" -> {
                val arr = value as? JSONArray ?: fail(path, "应为数组")
                if (arr.length() > schema.optInt("maxItems", Int.MAX_VALUE) || arr.length() < schema.optInt("minItems", 0)) fail(path, "数组长度超限")
                if (schema.optBoolean("uniqueItems") && (0 until arr.length()).map { PluginJson.canonical(arr.get(it)) }.distinct().size != arr.length()) fail(path, "数组包含重复项")
                schema.optJSONObject("items")?.let { item -> for (i in 0 until arr.length()) validate(arr.get(i), item, "$path[$i]") }
            }
            "string" -> {
                val s = value as? String ?: fail(path, "应为字符串")
                if (s.length < schema.optInt("minLength", 0) || s.length > schema.optInt("maxLength", Int.MAX_VALUE)) fail(path, "字符串长度超限")
                if (schema.has("pattern") && !Regex(schema.getString("pattern")).containsMatchIn(s)) fail(path, "字符串格式错误")
            }
            "boolean" -> if (value !is Boolean) fail(path, "应为布尔值")
            "integer", "number" -> {
                val n = (value as? Number)?.toDouble() ?: fail(path, "应为数值")
                if (!n.isFinite() || (schema.optString("type") == "integer" && n % 1.0 != 0.0) ||
                    n < schema.optDouble("minimum", -Double.MAX_VALUE) || n > schema.optDouble("maximum", Double.MAX_VALUE)) fail(path, "数值超出范围")
            }
        }
    }

    fun response(operation: String, result: JSONObject): JSONObject {
        validate(result, root.getJSONObject("\$defs").getJSONObject(operation))
        if (!result.getBoolean("ok")) {
            val error = result.getJSONObject("error")
            throw PluginException(PluginErrorCode.valueOf(error.getString("code")), error.getString("message"))
        }
        return result.getJSONObject("data")
    }

    private fun fail(path: String, message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, "$path: $message")
}

data class PluginManifest(val json: JSONObject) {
    val id: String get() = json.getString("id")
    val version: String get() = json.getString("version")
    val name: String get() = json.getString("name")
    val kind: String get() = json.getString("kind")
    val isService: Boolean get() = kind == "service"
    val isNative: Boolean get() = kind == "native"
    val isAcademic: Boolean get() = !isService && (!isNative || contributes.optBoolean("academic"))
    val apiVersion: Int get() = json.getInt("apiVersion")
    val contributes: JSONObject get() = json.optJSONObject("contributes") ?: JSONObject()
    val permissions: Set<String> get() = PluginJson.strings(json.optJSONArray("permissions")).toSet()
    val service: JSONObject? get() = json.optJSONObject("service")
    val baseProvider: String? get() = json.optString("extends").takeIf(String::isNotBlank)
    val capabilities: Set<String> get() = PluginJson.strings(json.getJSONArray("capabilities")).toSet()
    val network: List<JSONObject> get() = PluginJson.objects(json.getJSONArray("network"))
    val school: JSONObject get() = json.getJSONObject("school")

    fun validate(schema: PluginSchema) {
        schema.validate(json)
        BuiltinAcademicInheritance.configuration(this)
        if (kind in setOf("independent", "service", "native") && baseProvider != null || kind !in setOf("independent", "service", "native") && baseProvider == null) invalid("适配类型与内置继承关系不一致")
        if (!isNative && !json.has("school")) invalid("教务适配和旧版服务需要学校信息")
        if (isNative) NativePluginContract.validateManifest(this)
        else if (json.has("contributes") || json.has("permissions") || capabilities.any { it.startsWith("ui.") || it.startsWith("task.") || it.startsWith("data.") }) invalid("通用组件需要 API v3 原生插件")
        if (isService) ServicePluginContract.validateManifest(this)
        else if (service != null || capabilities.any { it.startsWith("service.") }) invalid("教务适配不能声明校园服务")
        if (kind == "configuration" && (capabilities.isNotEmpty() || json.has("entry"))) invalid("配置型适配不能包含可执行能力")
        if (kind != "configuration" && json.optString("entry") != "index.js") invalid("可执行适配缺少入口")
        for ((group, expected) in GROUPS) {
            val actual = capabilities.filter { it.startsWith("$group.") }.toSet()
            if (actual.isNotEmpty() && actual != expected) invalid("$group 必须完整覆盖能力组")
        }
        network.forEach { PluginNetworkPolicy.validateRule(it) }
    }

    private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
    companion object {
        val AUTH = setOf("auth.start", "auth.resume", "auth.refreshCaptcha", "auth.validate")
        val SELECTION = setOf("selection.catalog", "selection.courses", "selection.sections", "selection.enrolled", "selection.select", "selection.drop")
        val STUDY = setOf("study.terms", "study.schedule", "study.calendar", "study.grades", "study.gradeDetails", "study.exams")
        val ALL = AUTH + STUDY + SELECTION
        val GROUPS = mapOf("auth" to AUTH, "selection" to SELECTION)
    }
}
