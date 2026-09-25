package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.AcademicGatewayFactory
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A grant belongs to a caller release and the current school login, never its author. */
internal class PluginAcademicSession(
    private val app: Context, private val caller: PluginPackage, private val active: () -> Boolean,
    private val callerCurrent: () -> Boolean = {
        AcademicProviderRegistry.isCurrentPackage(caller.manifest.id, caller.digest) &&
            UserManager.getInstance().currentSchool?.let { AcademicProviderRegistry.isEnabled(caller.manifest.id, it) } == true
    }
) {
    private val user = UserManager.getInstance()
    private val token = user.sessionState.token
    private val school = user.currentSchool?.let { SchoolConfig.fromJson(it.toJson()) }
    private val account = user.currentAccountStorageKey
    val session = school?.let { AcademicGatewayFactory.sharedSession(it, account) }
        ?: throw PluginException(PluginErrorCode.SESSION_EXPIRED, "请先登录本校教务账号")
    private val epoch = session.epoch
    private val base = session.baseUrl.toHttpUrlOrNull()
        ?: throw PluginException(PluginErrorCode.UNTRUSTED_URL, "学校地址无效")
    private val provider = school?.let(AcademicProviderRegistry::resolve)
    private val sourceDigest = provider?.manifest?.id?.let { AcademicProviderRegistry.knownPackage(it)?.digest }
    private val prefs = app.getSharedPreferences("native-plugin-permissions", Context.MODE_PRIVATE)
    private val key = prefix(caller.manifest.id) + PluginJson.sha256(listOf(
        caller.digest, caller.official.toString(), account, school?.toJson().toString(), provider?.digest,
        sourceDigest, token.generation.toString(), session.instanceId, epoch.toString()
    ).joinToString("\u0000").toByteArray())

    fun requireCurrent() {
        if ("academic.session" !in caller.manifest.permissions || "network" !in caller.manifest.permissions)
            denied("插件需要声明教务登录共享和网络权限")
        if (!active() || !callerCurrent() || school == null || !user.isLoggedIn || user.sessionState.state.value.expired ||
            account.isBlank() || account != user.currentAccountStorageKey || !user.sessionState.isCurrent(token) ||
            school.toJson().toString() != user.currentSchool?.toJson()?.toString() || session.retired || session.epoch != epoch ||
            AcademicGatewayFactory.sharedSession(school, account) !== session ||
            !AcademicProviderRegistry.matches(caller, school) ||
            (AcademicProviderRegistry.resolve(school)?.digest != provider?.digest) ||
            provider != null && !AcademicProviderRegistry.isCurrentPackage(provider.manifest.id, sourceDigest.orEmpty()))
            throw PluginException(PluginErrorCode.STALE_CONTEXT, "教务账号、学校或插件已改变，请重新授权")
    }

    fun authorized(): Boolean { requireCurrent(); return !prefs.getString(key, null).isNullOrBlank() }

    fun description(): String {
        requireCurrent()
        val ranges = caller.manifest.network.filter { rule ->
            val origin = rule.optString("origin").toHttpUrlOrNull()
            origin != null && PluginAuthScope.origin(origin) == PluginAuthScope.origin(base)
        }.joinToString("\n") { it.getString("origin") + it.getString("pathPrefix") }
        if (ranges.isBlank()) denied("插件未声明当前教务站点的网络范围")
        val label = user.username.ifBlank { user.studentId.orEmpty() }.let { if (it.length > 4) it.take(2) + "••••" + it.takeLast(2) else it }
        return "允许 ${caller.manifest.name} 使用 ${school!!.name} 的已登录账号 $label？\n\n" +
            "访问范围：$ranges\n共享限于 ${base.toString().trimEnd('/')} 下的教务请求。提交操作仍需逐次确认。可在插件详情中撤销。"
    }

    /** Called only after the host confirmation completes; recheck every captured identity. */
    fun authorize(): JSONObject = synchronized(user.sessionState) { synchronized(grantLock) {
        requireCurrent()
        val grant = prefs.getString(key, null) ?: UUID.randomUUID().toString().let { value ->
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith(prefix(caller.manifest.id)) && it != key }.forEach {
                edit.remove(it); running.remove(it)?.forEach(PluginOperation::close)
            }
            check(edit.putString(key, value).commit()); value
        }
        JSONObject().put("grant", grant).put("schoolName", school!!.name).put("baseUrl", base.toString())
    } }

    fun requireGrant(grant: String) {
        requireCurrent()
        if (grant.isBlank() || prefs.getString(key, null) != grant) denied("教务登录授权已撤销或不属于当前插件和账号")
    }

    fun requireRequest(grant: String, url: HttpUrl, method: String, purpose: String, form: JSONObject?) {
        requireGrant(grant)
        val prefix = base.encodedPath.trimEnd('/')
        if (purpose !in setOf("query", "mutation") || PluginAuthScope.origin(url) != PluginAuthScope.origin(base) ||
            url.encodedPath != prefix && !url.encodedPath.startsWith("$prefix/"))
            throw PluginException(PluginErrorCode.UNTRUSTED_URL, "共享登录仅可用于当前学校教务地址范围")
        PluginNetworkPolicy(caller.manifest.network).requireAllowed(url, method, purpose, form)
    }

    fun cookies(grant: String): CookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> { requireGrant(grant); return session.cookies.loadForRequest(url) }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { requireGrant(grant); session.cookies.saveFromResponse(url, cookies) }
    }

    fun track(grant: String, operation: PluginOperation) = synchronized(grantLock) {
        requireGrant(grant); running.getOrPut(key) { ConcurrentHashMap.newKeySet() }.add(operation); Unit
    }
    fun untrack(operation: PluginOperation) = synchronized(grantLock) { running[key]?.remove(operation); Unit }

    companion object {
        private val grantLock = Any()
        private val running = ConcurrentHashMap<String, MutableSet<PluginOperation>>()
        private fun prefix(id: String) = "$id:academic-session:"
        fun revoke(app: Context, id: String) = synchronized(grantLock) {
            val prefs = app.getSharedPreferences("native-plugin-permissions", Context.MODE_PRIVATE)
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith(prefix(id)) }.forEach { key ->
                edit.remove(key); running.remove(key)?.forEach(PluginOperation::close)
            }
            check(edit.commit())
        }
        private fun denied(message: String): Nothing = throw PluginException(PluginErrorCode.PERMISSION_DENIED, message)
    }
}
