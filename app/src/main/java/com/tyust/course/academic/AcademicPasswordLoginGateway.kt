package com.tyust.course.academic

import com.tyust.course.login.PasswordLoginCallback
import com.tyust.course.login.PasswordLoginGateway
import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Bridges the new suspend adapter to the existing login screen contract. */
class AcademicPasswordLoginGateway(private val school: SchoolConfig) : PasswordLoginGateway {
    @Volatile private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var adapter: AcademicProtocolAdapter? = null
    var studentName: String = ""
        private set
    var studentId: String = ""
        private set
    var completedPlugin: com.tyust.course.academic.plugin.PluginAcademicAdapter? = null
        private set

    override fun login(school: SchoolConfig, username: String, password: String, callback: PasswordLoginCallback) {
        clearSensitiveState()
        studentName = ""; studentId = ""
        completedPlugin = null
        // Keep this key identical to UserManager.currentAccountStorageKey's
        // sanitized school::username representation.
        val key = AcademicGatewayFactory.accountKey(school, username)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requestScope = scope
        requestScope.launch {
            val result = loginResult {
                if (school.academicSystem == AcademicSystem.AUTO.id && !com.tyust.course.academic.plugin.AcademicProviderRegistry.hasBinding(school) && AcademicGatewayFactory.detect(school, key) == null)
                    throw AcademicException(AcademicStatus.PAGE_CHANGED, "无法识别教务系统，请在学校配置中手动选择")
                val created = AcademicGatewayFactory.create(school, key)
                currentCoroutineContext().ensureActive()
                adapter = created
                created.login(Credentials(username, password))
            }
            currentCoroutineContext().ensureActive()
            if (scope !== requestScope) return@launch
            val current = adapter
            if (current != null) deliver(current, result, callback)
            else callback.onError(result.message.ifBlank { "登录初始化失败，请重新选择教务系统" })
        }
    }

    override fun submitCaptcha(captchaCode: String, callback: PasswordLoginCallback) {
        val current = adapter ?: return callback.onError("登录会话已失效，请重新登录")
        val captchaLogin = current as? AcademicCaptchaLogin
            ?: return callback.onError("登录会话已失效，请重新登录")
        val requestScope = scope
        requestScope.launch {
            val result = loginResult { captchaLogin.submitCaptcha(captchaCode.trim()) }
            currentCoroutineContext().ensureActive()
            if (scope === requestScope && adapter === current) deliver(current, result, callback, submittingCaptcha = true)
        }
    }

    override fun refreshCaptcha(callback: (ByteArray?) -> Unit) {
        val current = adapter as? AcademicCaptchaLogin ?: return callback(null)
        val requestScope = scope
        requestScope.launch {
            val image = try { current.refreshCaptcha()?.image }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            if (scope === requestScope && adapter === current) callback(image)
        }
    }

    private suspend fun loginResult(block: suspend () -> LoginResult): LoginResult = try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: AcademicException) { LoginResult(e.status, message = e.message.orEmpty()) }
        catch (_: Exception) { LoginResult(AcademicStatus.PAGE_CHANGED, message = "登录失败，请重新登录或使用教务网页登录") }

    private fun deliver(current: AcademicProtocolAdapter, result: LoginResult, callback: PasswordLoginCallback, submittingCaptcha: Boolean = false) {
        if (result.status !in setOf(AcademicStatus.CAPTCHA_REQUIRED, AcademicStatus.HUMAN_VERIFICATION_REQUIRED)) (current as? AcademicCaptchaLogin)?.clearLoginState()
        when (result.status) {
            AcademicStatus.SUCCESS -> {
                completedPlugin = current as? com.tyust.course.academic.plugin.PluginAcademicAdapter
                studentName = result.studentName; studentId = result.studentId
                callback.onSuccess((current as? SessionBackedAdapter)?.cookieHeader().orEmpty())
            }
            AcademicStatus.INVALID_CREDENTIALS -> callback.onInvalidCredentials()
            AcademicStatus.CAPTCHA_REQUIRED -> {
                val image = result.captcha?.image
                if (image != null && image.isNotEmpty()) callback.onCaptchaRequired(image)
                else if (submittingCaptcha) callback.onCaptchaInvalid()
                else callback.onError("未能获取验证码图片，请重新登录")
            }
            AcademicStatus.HUMAN_VERIFICATION_REQUIRED -> callback.onWebLoginRequired(result.message.ifBlank { "请在教务网页完成验证或统一认证" })
            else -> callback.onError(result.message.ifBlank { result.status.name })
        }
    }

    override fun clearSensitiveState() {
        scope.cancel()
        (adapter as? AcademicCaptchaLogin)?.clearLoginState()
        adapter = null
    }

    val webLogin: org.json.JSONObject? get() = (adapter as? com.tyust.course.academic.plugin.PluginAcademicAdapter)?.webLogin
    suspend fun resumeWebLogin(cookie: String, pageUrl: String): LoginResult {
        val current = adapter as? com.tyust.course.academic.plugin.PluginAcademicAdapter
            ?: throw AcademicException(AcademicStatus.SESSION_EXPIRED, "网页登录会话已失效")
        val expected = current.webLogin?.getString("completionUrl") ?: throw AcademicException(AcademicStatus.SESSION_EXPIRED, "没有待完成的网页登录")
        val actual = pageUrl.toHttpUrlOrNull()
        val completion = expected.toHttpUrlOrNull()
        if (actual == null || completion == null || actual.scheme != completion.scheme || actual.host != completion.host || actual.port != completion.port || actual.encodedPath != completion.encodedPath)
            throw AcademicException(AcademicStatus.UNTRUSTED_URL, "请在指定的完成页面结束登录")
        AcademicGatewayFactory.importCookie(school, current.session.key.accountKey, cookie, replace = false)
        return current.resumeWebLogin().also { result ->
            if (result.status == AcademicStatus.SUCCESS) {
                completedPlugin = current
                studentName = result.studentName
                studentId = result.studentId
                current.clearLoginState()
            }
        }
    }
}

/** Optional capability used by the bridge; adapters keep cookie ownership private. */
internal interface SessionBackedAdapter {
    fun cookieHeader(): String
}
