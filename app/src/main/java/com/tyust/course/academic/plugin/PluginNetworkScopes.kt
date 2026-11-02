package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSession
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Process-local authority shared by PluginHost and NativePluginRunner. The host must obtain
 * gesture consent before authorize(), and must check policy on every HTTP/redirect hop.
 * Opt-in service accounts must retain the same AcademicSession until switch/logout; closing
 * a PluginOperation does not revoke its session. Retire that session and revoke on logout.
 * No rule or cookie here is added to the manifest or to an academic cookie jar.
 */
object PluginNetworkScopes {
    private const val MAX_GRANTS = 256
    private const val MAX_COOKIES = 256
    private val lock = Any()
    private val random = SecureRandom()
    private val grants = mutableMapOf<String, Entry>()
    private val banks = mutableMapOf<BankKey, MutableMap<CookieKey, Cookie>>()
    private val authMethods = setOf("start", "resume", "validate", "logout").flatMap {
        listOf("serviceAuth.$it", "auth.service.$it")
    }.toSet()

    private data class Binding(val pluginId: String, val version: String, val digest: String,
        val schoolId: String, val accountId: String, val baseUrl: String, val development: Boolean,
        val instanceId: String, val sessionEpoch: Long)
    private data class BankKey(val binding: Binding, val tenantId: String)
    private data class CookieKey(val origin: String, val name: String, val path: String)
    private class Entry(val key: BankKey, val session: AcademicSession, val rules: List<JSONObject>,
        val bank: MutableMap<CookieKey, Cookie>)

    class Scope internal constructor(
        val policy: PluginNetworkPolicy,
        val cookies: CookieJar,
        val tenantId: String,
        private val install: (HttpUrl, JSONArray) -> Unit
    ) {
        /** Real protocol values only; the host exposes this method solely to service auth hooks. */
        fun setCookies(url: HttpUrl, cookies: JSONArray) = install(url, cookies)
        fun setCookies(url: String, cookies: JSONArray) = install(
            url.toHttpUrlOrNull() ?: denied("Cookie URL 无效"), cookies)
    }

    fun authorize(operation: PluginOperation, tenantId: String, rules: JSONArray): String =
        authorize(operation, tenantId, objects(rules))

    /** Validate and copy the whole request before the host displays or remembers consent. */
    internal fun validateAuthorization(operation: PluginOperation, tenantId: String, rules: JSONArray): List<JSONObject> =
        validateAuthorization(operation, tenantId, objects(rules))

    internal fun validateAuthorization(operation: PluginOperation, tenantId: String, rules: List<JSONObject>): List<JSONObject> {
        operation.requireActive()
        requireOptIn(operation)
        if (tenantId.isBlank() || tenantId != tenantId.trim() || tenantId.length > 256 ||
            tenantId.any { it.isISOControl() }) invalid("学校租户标识无效")
        if (rules.size !in 1..32) invalid("动态网络范围需要 1–32 条规则")
        val snapshot = rules.map { validateRule(JSONObject(it.toString())) }
        if (operation.packageDigest.isBlank()) invalid("插件包摘要缺失")
        return snapshot
    }

    fun authorize(operation: PluginOperation, tenantId: String, rules: List<JSONObject>): String {
        val snapshot = validateAuthorization(operation, tenantId, rules)
        val binding = binding(operation)
        val token = synchronized(lock) {
            prune()
            // Selecting a school replaces this binding's tenant authority. Same-tenant auth
            // keeps its grants and bank; removed grants reject late responses via use().
            remove { it.key.binding == binding && it.key.tenantId != tenantId }
            if (grants.size >= MAX_GRANTS) limit("动态网络授权数量超过上限")
            val key = BankKey(binding, tenantId)
            val bank = banks.getOrPut(key) { mutableMapOf() }
            var value: String
            do {
                val bytes = ByteArray(32).also(random::nextBytes)
                value = bytes.toByteString().hex()
            } while (value in grants)
            grants[value] = Entry(key, operation.session, snapshot, bank)
            value
        }
        try { operation.requireActive() } catch (error: Exception) {
            synchronized(lock) { remove { grants[token] === it } }
            throw error
        }
        return token
    }

    /**
     * protectedAuth is a host-only assertion for the protected auth.service effect; never derive
     * it from a JS payload. Dedicated serviceAuth.* / auth.service.* operations qualify directly.
     * The optional active guard lets a host add its current account/package validation.
     */
    fun requireGrant(operation: PluginOperation, grant: String, protectedAuth: Boolean = false,
        active: () -> Unit = {}): Scope {
        operation.requireActive()
        requireOptIn(operation)
        active()
        val entry = synchronized(lock) {
            prune()
            grants[grant]?.takeIf { it.key.binding == binding(operation) }
                ?: denied("动态网络授权不属于当前插件、账号或会话")
        }
        val jar = ScopedCookies(operation, grant, entry, active)
        return Scope(PluginNetworkPolicy(entry.rules.map { JSONObject(it.toString()) }, strictPaths = true), jar,
            entry.key.tenantId) { url, cookies ->
            if ("auth" !in operation.manifest.permissions || !protectedAuth && operation.method !in authMethods)
                denied("只有受保护的服务认证可设置 Cookie")
            use(operation, grant, entry, active) {
                if (!url.isHttps || !allowedUrl(entry, url)) denied("Cookie 超出授权范围")
                val authRules = entry.rules.filter { matchesUrl(it, url) && "auth" in strings(it.getJSONArray("purposes")) }
                if (authRules.isEmpty()) denied("Cookie URL 未获认证授权")
                if (cookies.length() !in 1..MAX_COOKIES) invalid("Cookie 数量无效")
                // Validate the entire batch before changing any cookie, including earlier values.
                val parsed = objects(cookies).map { value ->
                    if (value.keys().asSequence().any { it !in setOf("name", "value", "path") } ||
                        value.opt("name") !is String || value.opt("value") !is String ||
                        value.has("path") && value.opt("path") !is String) invalid("Cookie 字段无效")
                    val name = value.getString("name")
                    val content = value.getString("value")
                    if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}")) || content.length > 8192 ||
                        content.any { it.code !in 33..126 || it in "\";,\\" }) invalid("Cookie 名称或值无效")
                    val path = if (value.has("path")) value.getString("path")
                        else authRules.maxBy { it.getString("pathPrefix").length }.getString("pathPrefix")
                    // A real server may return Path=/ in encrypted auth JSON. Accept ancestor
                    // paths here; store() intersects them with this grant's same-origin paths,
                    // exactly as it does for HTTP Set-Cookie. The source URL must still be an
                    // approved auth endpoint, never an otherwise unapproved origin root.
                    if (!validPath(path) || authRules.none {
                            val prefix = it.getString("pathPrefix")
                            under(path, prefix) || under(prefix, path)
                        })
                        denied("Cookie 路径超出认证授权")
                    Cookie.Builder().name(name).value(content).hostOnlyDomain(url.host).path(path)
                        .secure().httpOnly().build()
                }
                store(entry, url, parsed)
            }
        }
    }

    /** Roll back only this issuance; other grants in the same session keep their authority. */
    internal fun revokeGrant(operation: PluginOperation, grant: String) = synchronized(lock) {
        remove { grants[grant] === it && it.key.binding == binding(operation) }
    }

    /** External public auth navigation may use an already approved current school scope.
     * This opens a URL only: no service cookies, headers or native bridge are exported. */
    fun requireNavigation(operation: PluginOperation, url: HttpUrl) {
        operation.requireActive(); requireOptIn(operation)
        val candidates = synchronized(lock) { prune(); grants.entries.filter { it.value.key.binding == binding(operation) }.map { it.key to it.value } }
        for ((token, entry) in candidates) {
            val policy = PluginNetworkPolicy(entry.rules.map { JSONObject(it.toString()) }, strictPaths = true)
            val matches = listOf("auth", "query").any { purpose ->
                try { policy.requireAllowed(url, "GET", purpose, null); true } catch (_: PluginException) { false }
            }
            if (matches) { use(operation, token, entry, {}) {}; return }
        }
        denied("网页地址不属于当前已授权的学校网络范围")
    }

    fun revoke(operation: PluginOperation) = synchronized(lock) {
        remove { it.key.binding.pluginId == operation.manifest.id &&
            it.key.binding.instanceId == operation.session.instanceId }
    }

    fun revoke(session: AcademicSession) = synchronized(lock) {
        remove { it.key.binding.instanceId == session.instanceId }
    }

    fun revokePlugin(id: String) = synchronized(lock) { remove { it.key.binding.pluginId == id } }

    private fun binding(operation: PluginOperation) = Binding(operation.manifest.id, operation.manifest.version,
        operation.packageDigest, operation.session.key.schoolId, operation.session.key.accountKey,
        operation.session.baseUrl, operation.development, operation.session.instanceId, operation.epoch)

    private fun requireOptIn(operation: PluginOperation) {
        val manifest = operation.manifest
        if (!manifest.isNative || manifest.apiVersion != 3 || manifest.json.opt("dynamicNetworkScopes") != true ||
            "network" !in manifest.permissions || manifest.json.optJSONArray("requires")?.let(::objects).orEmpty()
                .none { it.optString("name") == "network.scope.authorize" && it.optInt("version") == 1 })
            denied("插件未声明动态网络授权能力")
    }

    internal fun validateRule(rule: JSONObject): JSONObject {
        val fields = setOf("origin", "pathPrefix", "methods", "purposes", "requiredQuery", "requiredForm", "authHeader", "userAgent")
        if (rule.toString().length > 16384 || rule.keys().asSequence().any { it !in fields } ||
            rule.opt("origin") !is String || rule.opt("pathPrefix") !is String) invalid("网络规则字段无效")
        val raw = rule.getString("origin")
        val url = raw.toHttpUrlOrNull() ?: invalid("网络来源无效")
        if (!url.isHttps || raw != origin(url) || raw.length > 2048 ||
            raw.any { it.isWhitespace() || it.isISOControl() || it in "*?%#\\" } ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.encodedPath != "/" ||
            url.query != null || url.fragment != null || !validPath(rule.getString("pathPrefix")))
            invalid("网络范围须使用精确来源与路径，不能使用通配符")
        val methods = rule.optJSONArray("methods")?.let(::strings) ?: invalid("缺少请求方法")
        val purposes = rule.optJSONArray("purposes")?.let(::strings) ?: invalid("缺少请求用途")
        if (methods.isEmpty() || methods.size > 4 || methods.any { it !in setOf("GET", "POST", "PUT", "HEAD") } ||
            purposes.isEmpty() || purposes.size > 3 || purposes.any { it !in setOf("query", "auth", "mutation") })
            invalid("网络方法或用途无效")
        for (name in listOf("requiredQuery", "requiredForm")) {
            if (!rule.has(name)) continue
            val values = rule.optJSONObject(name) ?: invalid("网络约束无效")
            if (values.length() > 32 || values.keys().asSequence().any {
                    it.isBlank() || it.length > 256 || values.opt(it) !is String || values.getString(it).length > 2048
                }) invalid("网络约束必须是有界字符串")
        }
        for (name in listOf("authHeader", "userAgent"))
            if (rule.has(name) && rule.opt(name) !is String) invalid("网络规则字段类型无效")
        PluginNetworkPolicy.validateRule(rule)
        return rule
    }

    private fun validPath(path: String) = path.length in 1..2048 && path.startsWith('/') && !path.contains("//") &&
        path.none { it.code !in 33..126 || it in "*%?#\\" } &&
        path.split('/').none { it == "." || it == ".." }

    private fun under(path: String, prefix: String) = path == prefix || path.startsWith(prefix.trimEnd('/') + "/")
    private fun origin(url: HttpUrl) = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    private fun matchesUrl(rule: JSONObject, url: HttpUrl) =
        rule.getString("origin") == origin(url) && under(url.encodedPath, rule.getString("pathPrefix"))
    private fun allowedUrl(entry: Entry, url: HttpUrl) = url.username.isEmpty() && url.password.isEmpty() &&
        url.fragment == null && validPath(url.encodedPath) && entry.rules.any { matchesUrl(it, url) }

    // Keep parent account/package callbacks outside this lock: account switching revokes while
    // holding PluginServiceAccounts.lock. Inside, compare immutable identity and session epoch.
    private fun <T> use(operation: PluginOperation, grant: String, entry: Entry, active: () -> Unit, block: () -> T): T {
        operation.requireActive(); active()
        val result = synchronized(lock) {
            prune()
            if (grants[grant] !== entry || entry.key.binding != binding(operation)) denied("动态网络授权已撤销")
            block()
        }
        operation.requireActive(); active()
        return result
    }

    private class ScopedCookies(private val operation: PluginOperation, private val grant: String,
        private val entry: Entry, private val active: () -> Unit) : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> = use(operation, grant, entry, active) {
            if (!url.isHttps || !allowedUrl(entry, url)) emptyList() else {
                entry.bank.entries.removeAll { it.value.expiresAt <= System.currentTimeMillis() }
                entry.bank.filter { (key, cookie) -> key.origin == origin(url) && cookie.matches(url) }
                    .values.sortedByDescending { it.path.length }
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = use(operation, grant, entry, active) {
            if (url.isHttps && allowedUrl(entry, url)) store(entry, url, cookies)
        }
    }

    /** Intersect server cookie paths with consent, retaining the response's exact origin/port. */
    private fun store(entry: Entry, url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.size > MAX_COOKIES) limit("Cookie 数量超过上限")
        val now = System.currentTimeMillis()
        val next = entry.bank.filterValues { it.expiresAt > now }.toMutableMap()
        for (cookie in cookies) {
            if (!validPath(cookie.path) || (cookie.domain != url.host &&
                    (cookie.hostOnly || !url.host.endsWith("." + cookie.domain)))) continue
            val paths = entry.rules.filter { it.getString("origin") == origin(url) }.mapNotNull { rule ->
                val prefix = rule.getString("pathPrefix")
                when {
                    under(cookie.path, prefix) -> cookie.path
                    under(prefix, cookie.path) -> prefix
                    else -> null
                }
            }.distinct()
            for (path in paths) {
                val key = CookieKey(origin(url), cookie.name, path)
                if (cookie.expiresAt <= now) { next.remove(key); continue }
                val confined = Cookie.Builder().name(cookie.name).value(cookie.value)
                    .hostOnlyDomain(url.host).path(path).secure()
                    .apply { if (cookie.httpOnly) httpOnly(); if (cookie.persistent) expiresAt(cookie.expiresAt) }.build()
                next[key] = confined
            }
        }
        if (next.size > MAX_COOKIES) limit("会话 Cookie 数量超过上限")
        entry.bank.clear(); entry.bank.putAll(next)
    }

    private fun prune() = remove { it.session.retired || it.session.epoch != it.key.binding.sessionEpoch }
    private fun remove(predicate: (Entry) -> Boolean) {
        grants.entries.removeAll { predicate(it.value) }
        val retained = grants.values.map { it.key }.toSet()
        banks.entries.removeAll { (key, bank) -> if (key !in retained) { bank.clear(); true } else false }
    }

    private fun objects(values: JSONArray): List<JSONObject> = (0 until values.length()).map {
        values.opt(it) as? JSONObject ?: invalid("数组成员必须是对象")
    }
    private fun strings(values: JSONArray): List<String> = (0 until values.length()).map {
        values.opt(it) as? String ?: invalid("数组成员必须是字符串")
    }
    private fun denied(message: String): Nothing = throw PluginException(PluginErrorCode.PERMISSION_DENIED, message)
    private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
    private fun limit(message: String): Nothing = throw PluginException(PluginErrorCode.RESOURCE_LIMIT, message)
}
