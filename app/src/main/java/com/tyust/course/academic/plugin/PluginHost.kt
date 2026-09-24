package com.tyust.course.academic.plugin

import android.util.AtomicFile
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Host-side authority. Values supplied by the JS context are never used for account selection. */
class PluginHost(private val operation: PluginOperation, private val storageRoot: File) {
    private val policy = PluginNetworkPolicy(operation.manifest.network)
    private val client = OkHttpClient.Builder().cookieJar(operation.session.cookies)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()
    private val log = mutableListOf<JSONObject>()
    private var requests = 0
    private var calls = 0

    @Synchronized fun call(method: String, payload: JSONObject): JSONObject {
        operation.requireActive()
        if (operation.method.substringBefore('.') in setOf("ui", "task", "data") && method != "capabilities.list" && method != "log" && !method.startsWith("crypto."))
            throw PluginException(PluginErrorCode.VALIDATION_FAILED, "原生插件须通过效果调用宿主能力")
        if (operation.method == "__inspect" && !method.startsWith("crypto."))
            throw PluginException(PluginErrorCode.VALIDATION_FAILED, "包加载检查不允许网络或存储副作用")
        if (++calls > 2000) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "宿主调用次数超过上限")
        val value: Any? = when {
            method == "http" -> http(payload)
            method == "capabilities.list" -> operation.pageContext.optJSONArray("capabilities") ?: org.json.JSONArray()
            method.startsWith("crypto.") -> crypto(method.substringAfter('.'), payload)
            method.startsWith("state.") -> store(false, method.substringAfter('.'), payload)
            method.startsWith("storage.") -> store(true, method.substringAfter('.'), payload)
            method == "log" -> {
                // Plugin-controlled messages may contain unknown protocol secrets. Retain safe metadata only.
                if (log.size < 200) log += JSONObject().put("event", "plugin.log")
                    .put("level", payload.optString("level").takeIf { it in setOf("debug", "info", "warn", "error") } ?: "info")
                JSONObject.NULL
            }
            else -> throw PluginException(PluginErrorCode.UNSUPPORTED, "未知宿主接口")
        }
        operation.requireActive()
        return PluginJson.success(value)
    }
    @Synchronized fun report(): List<JSONObject> = log.toList()

    @Synchronized fun upload(payload: JSONObject, file: File, field: String): JSONObject {
        operation.requireActive()
        if (!operation.manifest.isNative || operation.method != "host.effect") invalid("文件传输仅供原生宿主效果")
        return http(payload, file, field)
    }
    private fun http(payload: JSONObject, upload: File? = null, field: String = "file"): JSONObject {
        if (++requests > 100) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "网络请求次数超过上限")
        val purpose = payload.getString("purpose")
        if (purpose !in setOf("query", "auth", "mutation")) invalid("未知请求用途")
        if (purpose == "auth" && !operation.method.startsWith("auth.") && !(operation.manifest.isNative && operation.method == "host.effect" && "auth" in operation.manifest.permissions)) invalid("查询不能执行认证请求")
        var method = payload.optString("method", "GET")
        if (method !in setOf("GET", "POST")) invalid("不支持的请求方法")
        var url = payload.getString("url").toHttpUrlOrNull() ?: invalid("无效 URL")
        var form = payload.optJSONObject("form")
        if (payload.has("body") && form != null) invalid("body 和 form 不能同时使用")
        if (method == "GET" && (payload.has("body") || form != null)) invalid("GET 不接受请求体")
        val charsetName = payload.optString("charset", "UTF-8")
        if (charsetName !in setOf("UTF-8", "GBK", "GB2312", "GB18030")) invalid("不支持的编码")
        val charset = Charset.forName(charsetName)
        val supplied = JSONObject((payload.optJSONObject("headers") ?: JSONObject()).toString())
        val academicToken = operation.manifest.json.optInt("apiVersion") == 3 &&
            operation.manifest.json.optString("kind") in setOf("independent", "extension")
        if (payload.has("sameOriginReferer") &&
            (payload.opt("sameOriginReferer") !is Boolean || !academicToken)) invalid("同源 Referer 仅支持 API 3 教务插件")
        val sameOriginReferer = payload.optBoolean("sameOriginReferer", false)
        if (payload.has("upgradeHttpRedirects") &&
            (payload.opt("upgradeHttpRedirects") !is Boolean || !academicToken || purpose != "auth")) invalid("HTTPS 回调升级仅支持 API 3 教务认证")
        val upgradeHttpRedirects = payload.optBoolean("upgradeHttpRedirects", false)
        val cookieBinding = payload.optJSONObject("cookieHeader")
        if (payload.has("cookieHeader") && (cookieBinding == null || !academicToken)) invalid("Cookie 认证请求头仅支持 API 3 教务插件")
        fun cookieToken(target: HttpUrl): String {
            val binding = cookieBinding ?: invalid("缺少 Cookie 认证声明")
            val name = binding.optString("cookie")
            if (binding.keys().asSequence().toSet() != setOf("cookie", "header") ||
                !name.matches(Regex("[A-Za-z0-9_-]{1,64}")) || binding.optString("header") !in setOf("Authorization", "X-Token")) invalid("无效 Cookie 认证声明")
            val matches = operation.session.cookies.loadForRequest(target).filter { it.name == name }
            if (matches.isEmpty()) throw PluginException(PluginErrorCode.SESSION_EXPIRED, "网页登录凭据尚未导入，请重新完成网页登录")
            if (matches.size != 1) invalid("存在多个同名网页登录凭据")
            return try { java.net.URLDecoder.decode(matches.single().value.replace("+", "%2B"), "UTF-8") }
            catch (_: IllegalArgumentException) { invalid("网页登录凭据编码无效") }
        }
        val boundToken = if (cookieBinding != null) {
            policy.requireAllowed(url, method, purpose, form)
            val header = cookieBinding.optString("header")
            if (supplied.keys().asSequence().any { it.equals(header, true) }) invalid("认证请求头不可重复")
            cookieToken(url).also { supplied.put(header, it) }
        } else null
        val allowedHeaders = setOf("accept", "content-type", "x-requested-with") +
            (if (operation.manifest.isService || operation.manifest.isNative || academicToken) setOf("authorization") else emptySet()) +
            (if (academicToken) setOf("x-token") else emptySet())
        val headerNames = supplied.keys().asSequence().map { it.lowercase() }.toList()
        if (headerNames.distinct().size != headerNames.size) invalid("请求头不可重复")
        supplied.keys().forEach { if (it.lowercase() !in allowedHeaders) invalid("该请求头由宿主管理") }
        val authorization = supplied.keys().asSequence().firstOrNull { it.equals("authorization", true) }
        if (authorization != null) {
            val value = supplied.getString(authorization)
            if (academicToken) {
                val secret = value.removePrefix("Bearer ")
                if (value.length !in 1..8192 || secret.isEmpty() || secret.any { it.code !in 33..126 }) invalid("无效教务认证令牌")
            } else if (!value.startsWith("Bearer ") || value.length > 8192) invalid("服务认证仅支持 Bearer 请求头")
        }
        val token = supplied.keys().asSequence().firstOrNull { it.equals("x-token", true) }
        if (token != null && (supplied.getString(token).length !in 1..8192 || supplied.getString(token).any { it.code !in 33..126 })) invalid("无效教务认证令牌")
        var redirected = 0
        var callbackFragment: String? = null
        while (true) {
            operation.requireActive()
            policy.requireAllowed(url, method, purpose, form)
            val builder = Request.Builder().url(url).header("User-Agent", "ZhengfangAcademicPlugin/1")
            if (sameOriginReferer) builder.header("Referer", url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString())
            supplied.keys().forEach { builder.header(it, supplied.getString(it)) }
            if (method == "POST") {
                val body = if (upload != null) MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    form?.keys()?.forEach { addFormDataPart(it, form!!.getString(it)) }
                    addFormDataPart(field, "upload", upload.asRequestBody("application/octet-stream".toMediaType()))
                }.build() else if (form != null) FormBody.Builder(charset).apply {
                    form!!.keys().forEach { add(it, form!!.getString(it)) }
                }.build() else payload.optString("body").toRequestBody(
                    supplied.optString("Content-Type", "application/x-www-form-urlencoded; charset=$charsetName").toMediaType())
                builder.post(body)
            }
            if (purpose == "mutation") operation.markMutation()
            val call = client.newCall(builder.build())
            operation.register(call)
            try {
                call.execute().use { response ->
                    operation.requireActive()
                    if (log.size < 200) log += JSONObject().put("event", "http").put("origin", "${url.scheme}://${url.host}:${url.port}")
                        .put("method", method).put("purpose", purpose).put("status", response.code)
                    if (response.code in 300..399) {
                        if (purpose == "mutation") throw PluginException(PluginErrorCode.RESULT_UNKNOWN, "写入请求发生跳转，请先核实结果")
                        if (++redirected > 5) invalid("跳转次数超过上限")
                        if (method == "POST" && response.code in setOf(307, 308)) invalid("不能自动重放 POST 跳转")
                        val resolved = url.resolve(response.header("Location").orEmpty()) ?: invalid("无效跳转")
                        val next = PluginRedirects.upgradeToHttps(url, resolved, upgradeHttpRedirects)
                        if ((authorization != null || token != null) && (next.scheme != url.scheme || next.host != url.host || next.port != url.port))
                            throw PluginException(PluginErrorCode.UNTRUSTED_URL, "认证令牌不能随跳转发送到其他站点")
                        if (boundToken != null && cookieToken(next) != boundToken)
                            throw PluginException(PluginErrorCode.UNTRUSTED_URL, "Cookie 认证范围不能随跳转变化")
                        if (url.isHttps && !next.isHttps) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "不允许 HTTPS 降级")
                        // SPA SSO callbacks carry a token in the URL fragment. It belongs to the
                        // callback result, never to the HTTP request or network-policy path.
                        if (next.fragment != null && purpose != "auth") invalid("仅认证回调允许 URL 片段")
                        callbackFragment = next.encodedFragment
                        url = next.newBuilder().fragment(null).build()
                        method = "GET"
                        form = null
                    } else {
                        val stream = response.body?.source()
                        stream?.request(PluginLimits.RESPONSE_BYTES.toLong() + 1)
                        if ((stream?.buffer?.size ?: 0) > PluginLimits.RESPONSE_BYTES)
                            throw operation.failure(PluginErrorCode.RESOURCE_LIMIT, "响应超过 5 MiB")
                        val bytes = stream?.readByteArray() ?: ByteArray(0)
                        operation.requireActive()
                        if (purpose == "mutation" && response.code >= 500) throw PluginException(PluginErrorCode.RESULT_UNKNOWN, "服务端未确认写入结果")
                        val body = when (payload.optString("responseType", "text")) {
                            "text" -> bytes.toString(charset)
                            "base64" -> bytes.toByteString().base64()
                            else -> invalid("不支持的响应格式")
                        }
                        val headers = JSONObject()
                        listOf("Content-Type", "Date", "Retry-After").forEach { name -> response.header(name)?.let { headers.put(name, it) } }
                        val responseUrl = url.newBuilder().encodedFragment(callbackFragment).build()
                        return JSONObject().put("status", response.code).put("url", responseUrl.toString()).put("headers", headers).put("body", body)
                    }
                }
            } catch (e: IOException) {
                throw operation.failure(PluginErrorCode.NETWORK_RETRYABLE, "网络请求中断")
            } finally { operation.unregister(call) }
        }
    }

    private fun store(persistent: Boolean, method: String, payload: JSONObject): Any? {
        val key = payload.getString("key")
        if (key.isEmpty() || key.length > 200) invalid("无效存储键")
        val namespace = PluginJson.sha256((operation.session.key.schoolId + "\u0000" + operation.session.key.accountKey +
            "\u0000" + operation.manifest.id + "\u0000" + operation.development + if (operation.manifest.isNative) "\u0000" + operation.packageDigest else "").toByteArray())
        synchronized(storeLock) {
            operation.requireActive()
            val file = AtomicFile(File(storageRoot, "$namespace.json"))
            val states = sessionStates.getOrPut(operation.session) { mutableMapOf() }
            val stateId = "$namespace:${operation.epoch}"
            val values = if (persistent) {
                if (file.baseFile.exists()) PluginJson.parse(String(file.readFully(), Charsets.UTF_8)) else JSONObject()
            } else states.getOrPut(stateId) { JSONObject() }
            when (method) {
                "get" -> return values.opt(key) ?: JSONObject.NULL
                "set" -> {
                    val candidate = JSONObject(values.toString()).put(key, payload.get("value"))
                    if (candidate.toString().toByteArray().size > PluginLimits.STATE_BYTES) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "适配存储超过 256 KiB")
                    values.put(key, payload.get("value"))
                }
                "remove" -> values.remove(key)
                else -> invalid("未知存储操作")
            }
            if (persistent) {
                storageRoot.mkdirs()
                val output = file.startWrite()
                try { output.write(values.toString().toByteArray()); file.finishWrite(output) }
                catch (e: Exception) { file.failWrite(output); throw e }
            }
            return JSONObject.NULL
        }
    }

    private fun crypto(method: String, p: JSONObject): String {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun decode(key: String) = p.getString(key).decodeBase64()?.toByteArray() ?: invalid("无效 Base64")
        val bytes = p.getString("text").toByteArray(Charsets.UTF_8)
        return when (method) {
            "digest" -> { val algorithm = p.getString("algorithm"); if (algorithm !in setOf("SHA-256", "MD5")) invalid("不支持的摘要算法"); hex(MessageDigest.getInstance(algorithm).digest(bytes)) }
            "hmacSha256" -> hex(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(p.getString("key").toByteArray(), "HmacSHA256")) }.doFinal(bytes))
            "aesCbcEncrypt" -> Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(decode("keyBase64"), "AES"), IvParameterSpec(decode("ivBase64")))
            }.doFinal(bytes).toByteString().base64()
            "aesEcbEncrypt" -> {
                val key = decode("keyBase64")
                if (key.size !in setOf(16, 24, 32)) invalid("无效 AES 密钥长度")
                Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                }.doFinal(bytes).toByteString().base64()
            }
            "rsaEncrypt" -> Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decode("publicKeySpkiBase64"))))
            }.doFinal(bytes).toByteString().base64()
            "base64" -> bytes.toByteString().base64()
            else -> invalid("未知加密接口")
        }
    }
    private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
    companion object {
        private val storeLock = Any()
        private val sessionStates = java.util.WeakHashMap<com.tyust.course.academic.AcademicSession, MutableMap<String, JSONObject>>()
    }
}
