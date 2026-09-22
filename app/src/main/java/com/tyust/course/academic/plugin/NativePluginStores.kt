package com.tyust.course.academic.plugin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Handles are meaningful only within the host-selected school/account/package namespace. */
class NativePluginFiles(private val app: Context, namespace: String) {
    private val root = File(app.filesDir, "native-plugin-files/${PluginJson.sha256(namespace.toByteArray())}")
    fun file(handle: String): File {
        if (!handle.matches(Regex("[a-f0-9]{32}"))) invalid("文件句柄无效")
        return File(root, "$handle.bin").takeIf { it.isFile } ?: invalid("文件句柄已失效")
    }
    fun info(handle: String): JSONObject = synchronized(lock) {
        val file = file(handle)
        val metadata = PluginJson.parse(File(root, "$handle.json").readText())
        JSONObject().put("handle", handle).put("name", metadata.getString("name")).put("mime", metadata.getString("mime")).put("size", file.length())
    }
    fun create(name: String, mime: String, bytes: ByteArray = ByteArray(0)): JSONObject = synchronized(lock) {
        root.mkdirs()
        if ((root.listFiles()?.count { it.extension == "bin" } ?: 0) >= 64) limited()
        if (bytes.size > MAX_BYTES || totalBytes() + bytes.size > MAX_TOTAL) limited()
        val handle = UUID.randomUUID().toString().replace("-", "")
        val safeName = name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(160).ifBlank { "文件" }
        if (!mime.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+*-]+"))) invalid("文件类型无效")
        File(root, "$handle.bin").writeBytes(bytes)
        File(root, "$handle.json").writeText(JSONObject().put("name", safeName).put("mime", mime).toString())
        info(handle)
    }
    fun import(uri: Uri): JSONObject {
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "文件"
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(MAX_BYTES) } ?: invalid("无法读取所选文件")
        return create(name, app.contentResolver.getType(uri) ?: "application/octet-stream", bytes)
    }
    fun read(input: JSONObject): JSONObject = synchronized(lock) {
        val target = file(input.getString("handle")); val offset = input.optLong("offset", 0); val count = input.getInt("length")
        if (offset !in 0..MAX_BYTES.toLong() || count !in 1..262144) invalid("读取范围无效")
        val bytes = ByteArray(minOf(count.toLong(), (target.length() - offset).coerceAtLeast(0)).toInt())
        RandomAccessFile(target, "r").use { it.seek(offset); it.readFully(bytes) }
        JSONObject().put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("size", target.length()).put("eof", offset + bytes.size >= target.length())
    }
    fun write(input: JSONObject): JSONObject = synchronized(lock) {
        val handle = input.getString("handle"); val target = file(handle); val offset = input.optLong("offset", 0)
        val bytes = try { Base64.decode(input.getString("base64"), Base64.DEFAULT) } catch (_: Exception) { invalid("文件内容不是 Base64") }
        val newSize = maxOf(target.length(), offset + bytes.size)
        if (offset < 0 || bytes.size > 262144 || newSize > MAX_BYTES || totalBytes() - target.length() + newSize > MAX_TOTAL) limited()
        RandomAccessFile(target, "rw").use { it.seek(offset); it.write(bytes); it.fd.sync() }
        info(handle)
    }
    fun remove(handle: String) = synchronized(lock) { file(handle).delete(); File(root, "$handle.json").delete(); Unit }
    private fun totalBytes() = root.listFiles()?.filter { it.extension == "bin" }?.sumOf { it.length() } ?: 0L
    private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
    private fun limited(): Nothing = throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "插件文件达到数量或容量上限")
    companion object { const val MAX_BYTES = 16 * 1024 * 1024; const val MAX_TOTAL = 32 * 1024 * 1024; private val lock = Any() }
}

/** Secrets never enter plugin state: only opaque credential handles cross the JS boundary. */
class NativePluginVault(app: Context, namespace: String) {
    private val root = File(app.noBackupFilesDir, "native-plugin-vault/${PluginJson.sha256(namespace.toByteArray())}")
    private val ephemeral = mutableMapOf<String, JSONObject>()
    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun path(name: String) = AtomicFile(File(root, PluginJson.sha256(name.toByteArray()) + ".json"))
    fun put(name: String, value: JSONObject, persistent: Boolean = true): Unit = synchronized(vaultLock) {
        if (value.toString().toByteArray().size > PluginLimits.STATE_BYTES) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "凭据超过容量限制")
        if (!persistent) { ephemeral[name] = JSONObject(value.toString()); return@synchronized }
        root.mkdirs()
        if ((root.listFiles()?.size ?: 0) >= 100 && !path(name).baseFile.exists()) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "保存的凭据过多")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD((root.name + name).toByteArray()) }
        val encoded = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).put("data", Base64.encodeToString(cipher.doFinal(value.toString().toByteArray()), Base64.NO_WRAP))
        val target = path(name); val stream = target.startWrite()
        try { stream.write(encoded.toString().toByteArray()); target.finishWrite(stream) } catch (e: Exception) { target.failWrite(stream); throw e }
        ephemeral.remove(name)
    }
    fun get(name: String): JSONObject? = synchronized(vaultLock) {
        ephemeral[name]?.let { return@synchronized JSONObject(it.toString()) }
        val target = path(name)
        if (!target.baseFile.exists()) return@synchronized null
        try {
            val encoded = PluginJson.parse(String(target.readFully()))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(encoded.getString("iv"), Base64.DEFAULT))); updateAAD((root.name + name).toByteArray()) }
            PluginJson.parse(String(cipher.doFinal(Base64.decode(encoded.getString("data"), Base64.DEFAULT))))
        } catch (_: Exception) { throw PluginException(PluginErrorCode.SESSION_EXPIRED, "已保存的会话无法恢复，请重新认证") }
    }
    fun remove(name: String): Unit = synchronized(vaultLock) { ephemeral.remove(name); path(name).delete() }
    fun saveCredential(name: String, values: JSONObject, remember: Boolean): String = synchronized(vaultLock) {
        val old = get("credential-key:$name")?.optString("handle")
        if (!old.isNullOrBlank()) remove("credential:$old")
        val handle = UUID.randomUUID().toString()
        put("credential:$handle", values, remember)
        put("credential-key:$name", JSONObject().put("handle", handle), remember)
        handle
    }
    companion object { private val keyLock = Any(); private val vaultLock = Any(); private const val ALIAS = "native-plugin-v3-vault" }
}
