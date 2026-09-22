package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Locale
import okio.ByteString.Companion.decodeBase64

/** Pure validation shared by host UI and result delivery; no OS authority is granted here. */
object ServiceNativePolicy {
    const val FILE_BYTES = 64 * 1024
    val NEW_BLOCKS = setOf("keyValue", "table", "timeline", "barChart", "grid")
    private val statuses = setOf("success", "cancelled", "opened", "unavailable", "error")
    fun operations(manifest: PluginManifest) = PluginJson.objects(manifest.service?.optJSONArray("nativeOperations") ?: JSONArray())
    fun operation(manifest: PluginManifest, id: String): JSONObject = operations(manifest).firstOrNull { it.getString("id") == id }
        ?: invalid("系统操作未在插件中声明")
    fun validateManifest(manifest: PluginManifest) {
        val config = manifest.service ?: return
        val entries = PluginJson.objects(config.getJSONArray("entries"))
        val operations = operations(manifest)
        if (manifest.json.getInt("apiVersion") < 3 && (config.has("nativeOperations") || entries.any { it.has("placements") })) invalid("扩展入口和系统能力需要 API 3")
        if (operations.map { it.getString("id") }.distinct().size != operations.size) invalid("系统操作标识重复")
        operations.forEach {
            if (it.getString("reason").isBlank()) invalid("请说明系统能力用途")
            if (ServicePluginContract.action(manifest, it.getString("resultActionId")).getString("kind") != "query") invalid("系统结果只能交给查询操作，写入仍需另行确认")
            if ((it.getString("kind") == "pickFile") != it.has("mimeTypes")) invalid("仅文件选择操作需要声明文件类型")
        }
    }
    fun request(manifest: PluginManifest, link: JSONObject): JSONObject {
        if (manifest.json.getInt("apiVersion") < 3) invalid("系统能力需要 API 3")
        val operation = operation(manifest, link.getString("operationId"))
        val params = link.optJSONObject("params") ?: JSONObject()
        val allowed = when (operation.getString("kind")) {
            "scanCode", "pickFile" -> emptySet()
            "notification" -> setOf("title", "body")
            "calendar" -> setOf("title", "startAt", "endAt", "location", "description")
            else -> invalid("不支持此系统能力")
        }
        if (params.keys().asSequence().any { it !in allowed || params.opt(it) !is String || params.getString(it).length > 1000 }) invalid("系统操作参数无效")
        if (operation.getString("kind") in setOf("notification", "calendar") && (params.optString("title").isBlank() || params.getString("title").length > 100)) invalid("请提供有效标题")
        if (operation.getString("kind") == "notification" && params.optString("body").isBlank()) invalid("通知内容不能为空")
        if (operation.getString("kind") == "calendar") {
            val start = instant(params.optString("startAt")); val end = instant(params.optString("endAt"))
            if (end <= start || end - start > 31L * 24 * 3600 * 1000) invalid("日程结束时间须晚于开始时间，时长不超过 31 天")
        }
        return operation
    }
    fun instant(value: String): Long = try {
        require(Regex("20\\d{2}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-](?:0\\d|1[0-3]):[0-5]\\d|[+-]14:00)").matches(value))
        // SimpleDateFormat and Okio keep this path available on the App's Android 7 minimum.
        val parts = Regex("^(.*?)(Z|[+-]\\d{2}:\\d{2})$").matchEntire(value)!!
        val date = parts.groupValues[1]
        val normalized = (if ('.' in date) date.substringBefore('.') + "." + date.substringAfter('.').padEnd(3, '0') else "$date.000") + parts.groupValues[2]
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).apply { isLenient = false }
        val position = ParsePosition(0)
        val parsed = formatter.parse(normalized, position) ?: error("Invalid date")
        require(position.index == normalized.length)
        parsed.time
    } catch (_: Exception) { invalid("日程时间须为 2000—2099 年、包含时区和秒的 ISO 格式") }
    fun validateResult(manifest: PluginManifest, actionId: String, result: JSONObject) {
        if (result.opt("operationId") !is String || result.opt("status") !is String) invalid("系统结果标识无效")
        val operation = operation(manifest, result.getString("operationId"))
        val kind = operation.getString("kind")
        val status = result.getString("status")
        if (operation.getString("resultActionId") != actionId || ServicePluginContract.action(manifest, actionId).getString("kind") != "query") invalid("系统操作回调不匹配")
        if (status !in statuses || (status == "opened" && kind != "calendar") || (kind == "calendar" && status == "success")) invalid("系统操作状态无效")
        val allowed = setOf("operationId", "status", "text", "name", "mimeType", "size", "base64")
        if (result.keys().asSequence().any { it !in allowed }) invalid("系统结果包含未知字段")
        if (status != "success" && result.keys().asSequence().any { it !in setOf("operationId", "status") }) invalid("未完成的操作不能返回数据")
        if (result.has("text") && (kind != "scanCode" || result.opt("text") !is String || result.getString("text").length > 4000)) invalid("扫码内容超限")
        if (kind == "scanCode" && status == "success" && !result.has("text")) invalid("扫码结果缺少文字")
        val fileFields = setOf("name", "mimeType", "size", "base64")
        if (operation.getString("kind") != "pickFile" && fileFields.any(result::has)) invalid("此操作不能返回文件")
        if (kind == "pickFile" && status == "success" && !fileFields.all(result::has)) invalid("文件结果不完整")
        if (setOf("name", "mimeType", "base64").any { result.has(it) && result.opt(it) !is String }) invalid("文件结果类型错误")
        if (result.optString("name").length > 200 || result.optString("mimeType").length > 100 || result.optString("base64").length > ((FILE_BYTES + 2) / 3) * 4) invalid("文件结果超过限制")
        if (result.has("size") && (result.opt("size") !is Number || result.getDouble("size") % 1.0 != 0.0 || result.getDouble("size") !in 0.0..FILE_BYTES.toDouble())) invalid("文件大小超过限制")
        if (result.has("mimeType") && result.getString("mimeType") !in PluginJson.strings(operation.optJSONArray("mimeTypes"))) invalid("文件类型未获准")
        if (result.has("base64")) {
            val encoded = result.getString("base64")
            val bytes = encoded.decodeBase64() ?: invalid("文件编码错误")
            if (bytes.size != result.getInt("size") || bytes.base64() != encoded) invalid("文件大小或编码不一致")
        }
    }
    private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
}
