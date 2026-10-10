package com.tyust.course.academic.plugin

import android.content.Context
import android.util.AtomicFile
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/** Downloads upstream originals only. No upstream source is shipped inside the App or plugin. */
internal class PluginUserscriptStore(app: Context, val policy: PluginUserscriptPolicy, namespace: String,
    private val downloadOverride: ((String) -> String)? = null,
    private val syntaxOverride: (suspend (String) -> Unit)? = null,
    keyProvider: (() -> SecretKey)? = null,
    private val active: () -> Boolean) {
    private val root = File(app.noBackupFilesDir, "plugin-userscripts/${PluginJson.sha256(namespace.toByteArray())}/${PluginJson.sha256(policy.id.toByteArray())}")
    private val record = AtomicFile(File(root, "subscription.json"))
    private val vault = NativePluginVault(app, namespace, keyProvider = keyProvider, guard = ::requireActive)
    private val mutex = locks.getOrPut(root.absolutePath) { Mutex() }
    private val downloader = PluginUserscriptDownloader(policy, ::requireActive)
    private fun requireActive() { if (!active()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "脚本账号或插件已改变") }
    fun metadata(): JSONObject = synchronized(lock) { requireActive(); if (record.baseFile.isFile) JSONObject(String(record.readFully())) else JSONObject().put("subscribed", false) }
    private fun save(value: JSONObject) = synchronized(lock) {
        requireActive(); val out = record.startWrite()
        try { out.write(value.toString().toByteArray()); record.finishWrite(out) } catch (e: Exception) { record.failWrite(out); throw e }
    }
    fun settings(): JSONObject = (vault.get("userscript:${policy.id}:settings") ?: JSONObject()).apply {
        PluginJson.objects(policy.declaration.getJSONObject("adapter").getJSONArray("settings")).forEach { setting ->
            if (!has(setting.getString("key"))) normalizedSetting(setting, setting.opt("default"))?.let { put(setting.getString("key"), it) }
        }
    }
    fun putSetting(key: String, value: Any) = synchronized(lock) {
        if (key.length !in 1..128) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "脚本存储键过长")
        val settings = settings().put(key, value)
        if (settings.toString().toByteArray().size > 262144 || settings.length() > 256) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "脚本存储超过限额")
        vault.put("userscript:${policy.id}:settings", settings)
    }
    fun publicStatus(): JSONObject {
        val status = metadata()
        val settings = settings()
        val visible = JSONObject()
        PluginJson.objects(policy.declaration.getJSONObject("adapter").getJSONArray("settings")).forEach { setting ->
            normalizedSetting(setting, settings.opt(setting.getString("key")))?.let { visible.put(setting.getString("key"), it) }
        }
        return JSONObject().put("scriptId", policy.id).put("subscribed", status.optBoolean("subscribed"))
            .put("version", status.optJSONObject("current")?.optString("version").orEmpty())
            .put("pendingVersion", status.optJSONObject("pending")?.optString("version").orEmpty())
            .put("previousVersion", status.optJSONObject("previous")?.optString("version").orEmpty())
            .put("checkedAt", status.optLong("checkedAt")).put("updateMessage", status.optString("message"))
            .put("author", status.optJSONObject("current")?.optString("author").orEmpty()).put("license", status.optJSONObject("current")?.optString("license").orEmpty())
            .put("settings", visible).put("questionBankConfigured", settings.optString("shenchanranToken").isNotBlank())
            .put("questionBankBalance", settings.opt("tkLeft")?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: JSONObject.NULL)
    }
    fun redact(value: String): String {
        var safe = value
        val values = settings()
        (PluginJson.strings(policy.declaration.optJSONArray("sensitiveKeys")) + "shenchanranToken").distinct().forEach { key ->
            values.optString(key).takeIf { it.isNotEmpty() }?.let { safe = safe.replace(it, "[已隐藏]") }
        }
        return safe.replace(Regex("(?i)(token|password|cookie|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=[已隐藏]")
            .replace(Regex("(?i)\\b[a-z0-9]{32,}\\b"), "[已隐藏]").replace(Regex("(?<![0-9])1[3-9][0-9]{9}(?![0-9])"), "[账号]")
    }
    suspend fun update(subscribe: Boolean = false, retryRolledBack: Boolean = false): JSONObject = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = metadata()
            if (!current.optBoolean("subscribed") && !subscribe) return@withContext publicStatus()
            current.put("subscribed", true).put("checkedAt", System.currentTimeMillis())
            if (retryRolledBack) current.remove("skippedVersion")
            try {
                val baseline = current.optJSONObject("current")
                val updateUrl = baseline?.optString("updateUrl")?.takeIf { it.isNotBlank() } ?: policy.declaration.getString("updateUrl")
                val remote = UserscriptMetadata.parse(download(updateUrl, retryRolledBack))
                if (remote.version == current.optString("skippedVersion")) {
                    save(current.put("message", "已保留回退版本；手动检查更新可重试被回退的版本")); return@withContext publicStatus()
                }
                if (remote.version == baseline?.optString("version") || remote.version == current.optJSONObject("pending")?.optString("version")) {
                    save(current.put("message", "已是订阅的最新版本")); return@withContext publicStatus()
                }
                val downloadUrl = remote.one("downloadURL").ifBlank { baseline?.optString("downloadUrl") ?: policy.declaration.getString("downloadUrl") }
                val source = download(downloadUrl, retryRolledBack)
                val parsed = UserscriptMetadata.validate(source, policy)
                if (parsed.version != remote.version) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "更新元信息与脚本版本不一致")
                if (syntaxOverride != null) syntaxOverride.invoke(source) else {
                    val engine = QuickJs.create(Dispatchers.IO)
                    try {
                        engine.memoryLimit = 16L * 1024 * 1024
                        engine.evaluationTimeoutMillis = 5000
                        engine.evaluate<Unit>("void new Function(" + JSONObject.quote(source) + ");")
                    } finally { engine.close() }
                }
                currentCoroutineContext().ensureActive()
                val digest = PluginJson.sha256(source.toByteArray())
                synchronized(lock) {
                    requireActive()
                    val file = AtomicFile(File(root, "$digest.user.js")); val out = file.startWrite()
                    try { out.write(source.toByteArray()); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
                    val next = parsed.json().put("digest", digest)
                    current.put("pending", next).put("message", "新版已校验，下次任务启用")
                    save(current)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                save(current.put("message", if (e is PluginException) e.message.orEmpty() else "更新未完成，已保留现有版本"))
                if (!current.has("current") && !current.has("pending")) throw e
            }
            publicStatus()
        }
    }
    suspend fun activate(): Pair<JSONObject, String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val status = metadata()
            val selected = status.optJSONObject("pending") ?: status.optJSONObject("current")
                ?: throw PluginException(PluginErrorCode.NOT_OPEN, "请先订阅下载原脚本")
            val digest = selected.getString("digest")
            if (!digest.matches(Regex("[a-f0-9]{64}"))) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "脚本摘要无效")
            val source = File(root, "$digest.user.js").readText()
            if (PluginJson.sha256(source.toByteArray()) != digest) throw PluginException(PluginErrorCode.BAD_SIGNATURE, "本地脚本校验失败，请检查更新")
            UserscriptMetadata.validate(source, policy)
            status.optJSONObject("pending")?.let { pending ->
                status.optJSONObject("current")?.let { previous ->
                    status.put("previous", previous)
                    vault.put("userscript:${policy.id}:previous-settings", settings())
                }
                status.put("current", pending); status.remove("pending"); save(status.put("message", "当前任务已固定脚本版本"))
            }
            val retained = listOf("current", "previous", "pending").mapNotNull { status.optJSONObject(it)?.optString("digest") }.toSet()
            root.listFiles()?.filter { it.name.endsWith(".user.js") && it.name.removeSuffix(".user.js") !in retained }?.forEach { it.delete() }
            JSONObject(selected.toString()) to source
        }
    }
    suspend fun rollback(): JSONObject = mutex.withLock {
        withContext(Dispatchers.IO) {
            val status = metadata()
            val previous = status.optJSONObject("previous") ?: throw PluginException(PluginErrorCode.NOT_OPEN, "还没有可回退的版本")
            val old = status.optJSONObject("current")
            val oldSettings = settings()
            vault.get("userscript:${policy.id}:previous-settings")?.let { vault.put("userscript:${policy.id}:settings", it) }
            vault.put("userscript:${policy.id}:previous-settings", oldSettings)
            status.put("current", previous).put("previous", old).put("skippedVersion", old?.optString("version")); status.remove("pending")
            save(status.put("message", "已回退脚本和设置，下次任务生效")); publicStatus()
        }
    }
    private fun download(value: String, revalidate: Boolean): String {
        val url = policy.source(value)
        downloadOverride?.let { requireActive(); return it(url.toString()) }
        return downloader.download(url.toString(), revalidate)
    }
    companion object {
        /** Account removal shares the write lock, so a finishing download cannot recreate its cache. */
        fun clear(app: Context, namespace: String) = synchronized(lock) {
            val folder = File(app.noBackupFilesDir, "plugin-userscripts/${PluginStorageScope.hash(namespace)}")
            folder.deleteRecursively()
            Unit
        }
        fun normalizedSetting(setting: JSONObject, value: Any?): Any? = if (setting.getString("type") == "boolean") {
            when (value) { true, 1, 1.0, "1", "true" -> true; false, 0, 0.0, "0", "false" -> false; else -> null }
        } else value?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in setting.getDouble("min")..setting.getDouble("max") }
        private val lock = PluginServiceAccounts.lock
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
