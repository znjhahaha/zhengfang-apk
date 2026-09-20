package com.tyust.course.academic.plugin

import android.content.Context
import android.util.AtomicFile
import com.tyust.course.academic.AcademicSessionStore
import com.tyust.course.academic.plugin.runtime.PluginSandboxClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.zip.ZipInputStream

data class PluginPackage(val manifest: PluginManifest, val source: String, val digest: String, val official: Boolean)

object PluginPackageVerifier {
    fun publicKey(spkiBase64: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(
        X509EncodedKeySpec(spkiBase64.decodeBase64()?.toByteArray() ?: badSignature()))

    fun verifySignature(payload: JSONObject, signature: JSONObject, keys: Map<String, PublicKey>) {
        val key = keys[signature.optString("keyId")] ?: badSignature()
        val ec = key as? java.security.interfaces.ECPublicKey ?: badSignature()
        val parameters = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        if (ec.params.order != parameters.order || ec.params.generator != parameters.generator) badSignature()
        val bytes = signature.optString("signature").decodeBase64()?.toByteArray() ?: badSignature()
        val valid = runCatching { Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(PluginJson.canonical(payload).toByteArray()); verify(bytes)
        } }.getOrDefault(false)
        if (!valid) badSignature()
    }

    fun read(bytes: ByteArray, schema: PluginSchema, keys: Map<String, PublicKey>, allowDevelopment: Boolean): PluginPackage {
        if (bytes.size > PluginLimits.PACKAGE_BYTES) invalid("插件包超过 2 MiB")
        val contents = linkedMapOf<String, ByteArray>()
        var expanded = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name !in setOf("manifest.json", "index.js", "signature.json") || entry.isDirectory || contents.containsKey(entry.name)) invalid("插件包含无效或重复路径")
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = zip.read(buffer)
                    if (size < 0) break
                    expanded += size
                    if (expanded > PluginLimits.EXPANDED_BYTES) invalid("插件解压超过 8 MiB")
                    output.write(buffer, 0, size)
                }
                contents[entry.name] = output.toByteArray()
            }
        }
        val manifest = PluginManifest(PluginJson.parse(contents["manifest.json"]?.toString(Charsets.UTF_8) ?: invalid("缺少清单")))
        manifest.validate(schema)
        val hashes = manifest.json.getJSONObject("files")
        val actual = contents.keys - setOf("manifest.json", "signature.json")
        if (hashes.keys().asSequence().toSet() != actual) invalid("包内容与文件摘要不一致")
        hashes.keys().forEach { if (PluginJson.sha256(contents.getValue(it)) != hashes.getString(it)) invalid("文件摘要不匹配") }
        if ((manifest.kind != "configuration") != contents.containsKey("index.js")) invalid("插件入口与类型不一致")
        val signature = contents["signature.json"]?.let { PluginJson.parse(it.toString(Charsets.UTF_8)) }
        if (signature != null) verifySignature(manifest.json, signature, keys) else if (!allowDevelopment) badSignature()
        return PluginPackage(manifest, contents["index.js"]?.toString(Charsets.UTF_8).orEmpty(), PluginJson.sha256(bytes), signature != null)
    }
    private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
    private fun badSignature(): Nothing = throw PluginException(PluginErrorCode.BAD_SIGNATURE, "签名无效或签名密钥未受信任")
}

/** Immutable content-addressed packages. Switching the AtomicFile never changes a pinned package. */
class PluginPackageStore(private val context: Context, private val trustedKeys: Map<String, PublicKey> = emptyMap()) {
    private val root = File(context.filesDir, "academic-plugins")
    private val index = AtomicFile(File(root, "index.json"))
    private val schema = PluginSchema(PluginJson.parse(context.assets.open("academic-plugin/manifest.schema.json").bufferedReader().use { it.readText() }))
    // Registry reconfiguration can replace the store while a prior install is finishing.
    // All stores in the host process must serialize the shared AtomicFile transaction.
    private val lock get() = storeLock

    suspend fun install(bytes: ByteArray, allowDevelopment: Boolean = false): PluginPackage = withContext(Dispatchers.IO) {
        val candidate = PluginPackageVerifier.read(bytes, schema, trustedKeys, allowDevelopment)
        if (candidate.source.isNotEmpty()) {
            val session = AcademicSessionStore().session(candidate.manifest.school.getString("id"), "package-inspection", "https://invalid.example")
            val op = PluginOperation(session, candidate.manifest, "__inspect", development = true)
            val actual = PluginSandboxClient(context).execute(candidate.source, JSONObject(), op, PluginHost(op, File(context.cacheDir, "plugin-inspection")))
            if (PluginJson.strings(actual.getJSONArray("data")).toSet() != candidate.manifest.capabilities)
                throw PluginException(PluginErrorCode.VALIDATION_FAILED, "实际能力与清单不一致")
        }
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            root.mkdirs()
            val destination = File(root, "${candidate.digest}.zfplugin")
            if (!destination.exists()) {
                val staged = File(root, ".${UUID.randomUUID()}.tmp")
                try {
                    staged.outputStream().use { it.write(bytes); it.fd.sync() }
                    if (!staged.renameTo(destination)) throw java.io.IOException("插件文件切换失败")
                } finally { staged.delete() }
            }
            val state = state()
            val previous = state.optJSONObject(candidate.manifest.id)
            // Never silently downgrade an official active package through the developer import route.
            if (!candidate.official && previous?.optBoolean("official") == true)
                throw PluginException(PluginErrorCode.VALIDATION_FAILED, "请先在开发调试中停用官方适配，再导入开发包")
            val record = JSONObject().put("active", candidate.digest).put("official", candidate.official)
            previous?.optString("active")?.takeIf { it != candidate.digest }?.let { record.put("previous", it) }
                ?: previous?.optString("previous")?.takeIf { it.isNotBlank() }?.let { record.put("previous", it) }
            state.put(candidate.manifest.id, record)
            save(state)
        }
        candidate
    }
    fun active(id: String): PluginPackage? = synchronized(lock) {
        state().optJSONObject(id)?.optString("active")?.takeIf { it.isNotBlank() }?.let(::readDigest)
    }
    fun list(): List<PluginPackage> = synchronized(lock) { state().keys().asSequence().mapNotNull { active(it) }.toList() }
    fun rollback(id: String): PluginPackage = synchronized(lock) {
        val state = state()
        val record = state.optJSONObject(id) ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "未安装适配")
        val previous = record.optString("previous")
        val candidate = readDigest(previous)
        val active = record.getString("active")
        record.put("active", previous).put("previous", active).put("official", candidate.official)
        save(state)
        candidate
    }
    fun deactivate(id: String) = synchronized(lock) { val state = state(); state.remove(id); save(state) }
    private fun readDigest(digest: String): PluginPackage {
        if (!digest.matches(Regex("[a-f0-9]{64}"))) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "没有上一可用版本")
        val bytes = File(root, "$digest.zfplugin").readBytes()
        if (PluginJson.sha256(bytes) != digest) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "已安装包已损坏")
        return PluginPackageVerifier.read(bytes, schema, trustedKeys, true)
    }
    private fun state() = if (index.baseFile.exists()) PluginJson.parse(String(index.readFully())) else JSONObject()
    private fun save(state: JSONObject) {
        root.mkdirs()
        val output = index.startWrite()
        try { output.write(state.toString().toByteArray()); index.finishWrite(output) }
        catch (e: Exception) { index.failWrite(output); throw e }
    }
    companion object { private val storeLock = Any() }
}
