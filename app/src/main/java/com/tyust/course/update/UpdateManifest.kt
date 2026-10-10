package com.tyust.course.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

class UpdateFailure(val code: String, message: String) : Exception(message)
internal fun updateError(code: String, message: String): Nothing = throw UpdateFailure(code, message)

data class UpdateMirror(val id: String, val name: String, val url: String)
data class UpdateArtifact(val size: Long, val sha256: String, val mirrors: List<UpdateMirror>)
data class UpdateManifest(
    val channel: String, val revision: Long, val packageName: String,
    val versionCode: Int, val versionName: String, val minSdk: Int,
    val releaseNotes: String, val forceUpdate: Boolean, val size: Long,
    val sha256: String, val mirrors: List<UpdateMirror>, val sourceSha: String,
    val buildId: String, val publishedAt: String, val envelope: String,
    val artifacts: Map<String, UpdateArtifact> = emptyMap(), val selectedAbi: String? = null
) {
    val identity get() = "$channel:$versionCode:$sha256:$size" + (selectedAbi?.let { ":$it" } ?: "")
    val artifactLabel get() = when (selectedAbi) { "arm64-v8a" -> "ARM64"; "armeabi-v7a" -> "ARM32"; else -> "ARM 通用兼容包" }
    fun forAbi(abi: String?): UpdateManifest {
        require(selectedAbi == null) { "Select from the original signed manifest" }
        if (abi == null) return this
        val artifact = artifacts[abi] ?: updateError("APK_ABI", "更新清单未提供当前架构的安装包")
        return copy(size = artifact.size, sha256 = artifact.sha256, mirrors = artifact.mirrors, selectedAbi = abi)
    }
    fun forDevice(supportedAbis: List<String>): UpdateManifest {
        if (artifacts.isEmpty()) return this
        val abi = listOf("arm64-v8a", "armeabi-v7a").firstOrNull { it in supportedAbis && it in artifacts }
        if (abi != null) return forAbi(abi)
        if (supportedAbis.any { it in setOf("arm64-v8a", "armeabi-v7a") }) return this
        updateError("APK_ABI", "当前设备不支持此版本的 ARM 安装包")
    }
    fun isInstallCandidate(currentCode: Int, sdk: Int, testCurrent: Boolean = false): Boolean =
        minSdk <= sdk && (versionCode > currentCode || (testCurrent && channel == "test" && versionCode == currentCode))
}

/** One parser for Android and JVM: reject duplicate keys and JSON extensions. */
object UpdateJson {
    fun parse(text: String): JSONObject {
        if (text.toByteArray(Charsets.UTF_8).size > 128 * 1024) updateError("MANIFEST_SIZE", "更新清单过大")
        // Android JSONTokener also accepts unescaped controls and some non-JSON escapes.
        var quoted = false; var index = 0
        while (index < text.length) {
            val ch = text[index++]
            if (ch == '"') quoted = !quoted
            else if (quoted && ch == '\\') {
                if (index >= text.length) updateError("MANIFEST_JSON", "更新清单格式无效")
                val escape = text[index++]
                if (escape == 'u') {
                    if (index + 4 > text.length || !text.substring(index, index + 4).matches(Regex("[0-9a-fA-F]{4}"))) updateError("MANIFEST_JSON", "更新清单格式无效")
                    index += 4
                } else if (escape !in "\"\\/bfnrt") updateError("MANIFEST_JSON", "更新清单格式无效")
            } else if (!quoted && (ch == '/' || ch == '#' || ch.code < 32 && ch !in "\t\n\r")) updateError("MANIFEST_JSON", "更新清单格式无效")
            else if (quoted && ch.code < 32) updateError("MANIFEST_JSON", "更新清单格式无效")
        }
        val token = JSONTokener(text)
        fun value(depth: Int): Any {
            require(depth <= 16) { "JSON nesting" }
            return when (val ch = token.nextClean()) {
                '{' -> {
                    val obj = JSONObject(); val keys = mutableSetOf<String>()
                    if (token.nextClean() != '}') {
                        token.back()
                        while (true) {
                            require(token.nextClean() == '"'); val key = token.nextString('"')
                            require(keys.add(key)); require(token.nextClean() == ':')
                            obj.put(key, value(depth + 1))
                            val end = token.nextClean(); if (end == '}') break
                            require(end == ',')
                        }
                    }; obj
                }
                '[' -> {
                    val array = JSONArray()
                    if (token.nextClean() != ']') {
                        token.back()
                        while (true) {
                            array.put(value(depth + 1))
                            val end = token.nextClean(); if (end == ']') break
                            require(end == ',')
                        }
                    }; array
                }
                '"' -> token.nextString('"')
                else -> {
                    val word = StringBuilder().append(ch)
                    while (true) {
                        val c = token.next()
                        if (c == '\u0000' || c.isWhitespace() || c in ",]}") { if (c != '\u0000') token.back(); break }
                        word.append(c)
                    }
                    when (val s = word.toString()) {
                        "true" -> true; "false" -> false; "null" -> JSONObject.NULL
                        else -> { require(s.matches(Regex("-?(0|[1-9][0-9]*)"))); s.toLong() }
                    }
                }
            }
        }
        return try {
            val obj = value(0) as JSONObject
            require(token.nextClean() == '\u0000'); obj
        } catch (_: Exception) { updateError("MANIFEST_JSON", "更新清单格式无效") }
    }
}

object UpdateManifestVerifier {
    fun key(encoded: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(
        X509EncodedKeySpec(encoded.decodeBase64()?.toByteArray() ?: error("Invalid public key")))

    fun verify(text: String, keys: Map<String, PublicKey>, channel: String): UpdateManifest {
        try {
            val envelope = UpdateJson.parse(text)
            require(envelope.get("schemaVersion") is Number && envelope.getLong("schemaVersion") == 2L)
            val key = keys[envelope.getString("keyId")] as? ECPublicKey
                ?: updateError("MANIFEST_SIGNATURE", "更新清单签名不受信任")
            require(key.params.curve.field.fieldSize == 256 && key.params.order == java.math.BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16))
            val bytes = envelope.getString("payload").decodeBase64()?.toByteArray() ?: error("payload")
            val signature = envelope.getString("signature").decodeBase64()?.toByteArray() ?: error("signature")
            if (!Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(bytes); verify(signature) })
                updateError("MANIFEST_SIGNATURE", "更新清单签名校验失败")
            val payload = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val p = UpdateJson.parse(payload)
            fun integer(name: String, min: Long, max: Long): Long {
                val v = p.get(name); require(v is Long || v is Int)
                return (v as Number).toLong().also { require(it in min..max) }
            }
            require(p.getString("channel") == channel && channel in setOf("stable", "test"))
            require(p.getString("packageName") == "com.tyust.course")
            val code = integer("versionCode", 1, Int.MAX_VALUE.toLong()).toInt()
            val name = p.getString("versionName"); require(name.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            val sha = p.getString("sha256"); require(sha.matches(Regex("[a-f0-9]{64}")))
            val source = p.getString("sourceSha"); require(source.matches(Regex("[a-f0-9]{40}")))
            val build = p.getString("buildId"); require(build.matches(Regex("[0-9]{1,24}")))
            val published = p.getString("publishedAt"); require(published.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")))
            val notes = p.getString("releaseNotes"); require(notes.length <= 20000)
            require(p.get("forceUpdate") is Boolean)
            fun mirrorEntries(mirrors: JSONArray): List<UpdateMirror> {
              require(mirrors.length() in 1..8)
              val ids = mutableSetOf<String>()
              return (0 until mirrors.length()).map { i ->
                val m = mirrors.getJSONObject(i); val id = m.getString("id"); val label = m.getString("name")
                require(id.matches(Regex("[a-z0-9-]{1,32}")) && ids.add(id) && label.length in 1..40)
                val url = m.getString("url"); require(validUrl(url))
                UpdateMirror(id, label, url)
              }
            }
            val entries = mirrorEntries(p.getJSONArray("mirrors"))
            val artifacts = if (p.has("artifacts")) {
                val values = p.getJSONObject("artifacts")
                require(values.length() in 1..2)
                values.keys().asSequence().associateWith { abi ->
                    require(abi in setOf("arm64-v8a", "armeabi-v7a"))
                    val value = values.getJSONObject(abi)
                    val size = value.get("size"); require(size is Long || size is Int)
                    require((size as Number).toLong() in 1..512L * 1024 * 1024)
                    val digest = value.getString("sha256"); require(digest.matches(Regex("[a-f0-9]{64}")))
                    UpdateArtifact(size.toLong(), digest, mirrorEntries(value.getJSONArray("mirrors")))
                }
            } else emptyMap()
            return UpdateManifest(channel, integer("revision", 1, Long.MAX_VALUE), p.getString("packageName"),
                code, name, integer("minSdk", 24, 1000).toInt(), notes, p.getBoolean("forceUpdate"),
                integer("size", 1, 512L * 1024 * 1024), sha, entries, source, build, published, text, artifacts)
        } catch (e: UpdateFailure) { throw e }
        catch (_: Exception) { updateError("MANIFEST_INVALID", "更新清单无效或不兼容") }
    }

    fun validUrl(value: String): Boolean {
        if (value.length > 4096) return false
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            url.port == 443 && url.host in setOf("dl.hidisiwa.xyz", "dl-test.hidisiwa.xyz", "github.com",
                "raw.githubusercontent.com", "release-assets.githubusercontent.com", "gh-proxy.com", "ghproxy.net",
                "gitee.com", "raw.giteeusercontent.com")
    }

    fun choose(candidates: List<UpdateManifest>, previous: UpdateManifest?): UpdateManifest? = candidates.filter {
        previous == null || (it.channel == previous.channel && it.revision >= previous.revision &&
            (it.revision != previous.revision || it.copy(envelope = "") == previous.copy(envelope = "")) &&
            it.versionCode >= previous.versionCode && (it.versionCode != previous.versionCode ||
                (it.sha256 == previous.sha256 && it.size == previous.size &&
                    it.artifacts.mapValues { (_, a) -> a.sha256 to a.size } == previous.artifacts.mapValues { (_, a) -> a.sha256 to a.size })))
    }.maxWithOrNull(compareBy<UpdateManifest> { it.revision }.thenBy { it.versionCode })
}
