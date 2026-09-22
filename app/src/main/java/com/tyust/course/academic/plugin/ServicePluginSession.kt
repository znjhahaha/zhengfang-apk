package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.*
import org.json.JSONObject

/** Owns only this service's cookies/state. Academic sessions and credentials never enter it. */
class ServicePluginSession(
    private val app: Context, val pkg: PluginPackage, private val accountScope: String,
    private val scopeStillActive: () -> Boolean = { true }
) {
    private val school = pkg.manifest.school
    private val baseUrl = "${school.getString("protocol")}://${school.getString("domain")}${school.getString("basePath")}".trimEnd('/') + "/"
    private var adapter = createAdapter("")
    private var closed = false
    val needsLogin: Boolean get() = pkg.manifest.service!!.getJSONObject("authentication").getString("mode") == "password"
    var authenticated = !needsLogin
        private set
    val session: AcademicSession get() = adapter.session

    init { require(pkg.manifest.isService) }

    private fun createAdapter(username: String): PluginAcademicAdapter {
        val scope = PluginJson.sha256((accountScope + "\u0000" + pkg.manifest.id + "\u0000" + username).toByteArray())
        val key = AcademicSessionKey(school.getString("id"), "service:$scope")
        return PluginAcademicAdapter(app.applicationContext, pkg, AcademicSession(key, baseUrl), scopeStillActive = { !closed && scopeStillActive() })
    }

    suspend fun login(username: String, password: String): LoginResult {
        ensureScope()
        require(needsLogin && username.isNotBlank() && password.isNotEmpty()) { "请填写服务账号与密码" }
        logout()
        adapter = createAdapter(username.trim())
        return adapter.login(Credentials(username.trim(), password)).also(::acceptLogin)
    }
    suspend fun submitCaptcha(code: String): LoginResult {
        ensureScope(); return adapter.submitCaptcha(code).also(::acceptLogin)
    }
    suspend fun refreshCaptcha(): CaptchaChallenge? { ensureScope(); return adapter.refreshCaptcha().also { ensureScope() } }
    private fun acceptLogin(result: LoginResult) {
        ensureScope()
        authenticated = result.status == AcademicStatus.SUCCESS
        if (result.status == AcademicStatus.HUMAN_VERIFICATION_REQUIRED) {
            logout()
            throw AcademicException(AcademicStatus.UNSUPPORTED, "此服务要求网页认证，请使用官方网页；当前校园插件支持账号密码和验证码登录")
        }
    }
    suspend fun page(id: String, params: JSONObject = JSONObject()): JSONObject = invoke("service.page", JSONObject().put("pageId", id).put("params", params))
    suspend fun action(id: String, params: JSONObject, confirmed: Boolean): JSONObject = invoke("service.action", JSONObject().put("actionId", id).put("params", params), confirmed)
    fun requireActive() { ensureScope(); session.requireActive(); if (!authenticated) throw AcademicException(AcademicStatus.SESSION_EXPIRED, "请先登录此服务") }
    suspend fun nativeResult(result: JSONObject): JSONObject {
        requireActive()
        val callback = ServiceNativePolicy.operation(pkg.manifest, result.getString("operationId")).getString("resultActionId")
        ServiceNativePolicy.validateResult(pkg.manifest, callback, result)
        return invoke("service.action", JSONObject().put("actionId", callback).put("nativeResult", result).put("params", JSONObject()))
    }
    private suspend fun invoke(method: String, args: JSONObject, confirmed: Boolean = false): JSONObject {
        ensureScope()
        if (!authenticated) throw AcademicException(AcademicStatus.SESSION_EXPIRED, "请先登录此服务")
        try { return adapter.invoke(method, args, confirmed).also { ensureScope() } }
        catch (error: AcademicException) { if (error.status == AcademicStatus.SESSION_EXPIRED) logout(); throw error }
    }
    fun logout() {
        adapter.clearLoginState(); adapter.session.retire()
        authenticated = !closed && !needsLogin
        if (!closed) adapter = createAdapter("")
    }
    fun close() { closed = true; authenticated = false; adapter.clearLoginState(); adapter.session.retire() }
    private fun ensureScope() {
        if (closed || !scopeStillActive()) { close(); throw AcademicException(AcademicStatus.SESSION_EXPIRED, "学校、账号或插件状态已改变，请重新打开校园服务") }
    }
}
