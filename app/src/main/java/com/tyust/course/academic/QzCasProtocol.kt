package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.jsoup.Jsoup
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

internal data class QzCasEntry(val login: HttpUrl, val service: HttpUrl) {
    val loginWithService: HttpUrl get() = login.newBuilder().setQueryParameter("service", service.toString()).build()
    companion object {
        fun forSchool(school: SchoolConfig): QzCasEntry? {
            val base = school.fullBasePath.toHttpUrlOrNull() ?: return null
            if (base.host != "jwxt.hut.edu.cn" || base.port !in listOf(80, 443) || school.basePath.trimEnd('/') != "/jsxsd") return null
            // This HTTP service is the school's registered CAS callback, not a general downgrade rule.
            return QzCasEntry("https://mycas.hut.edu.cn/cas/login".toHttpUrl(),
                "http://jwxt.hut.edu.cn/jsxsd/sso.jsp".toHttpUrl())
        }
    }
}

internal object QzCasProtocol {
    data class Page(val fields: Map<String, String>, val captchaRequired: Boolean, val mfaEnabled: Boolean)

    fun page(html: String, failedAttempts: Int = 0): Page {
        val document = Jsoup.parse(html)
        val form = document.select("form#fm1, el-form#fm1").first()
            ?: throw AcademicException(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, "请在统一认证网页完成登录")
        val fields = form.select("input[name]").filter {
            it.attr("type") !in listOf("submit", "button", "checkbox") && !it.hasAttr("disabled")
        }.associate { it.attr("name") to it.attr("value") }
        if (fields["execution"].isNullOrBlank() || !fields.containsKey("username") || !fields.containsKey("password"))
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "统一认证登录参数已变化，请重新登录")
        val scripts = document.select("script:not([src])").joinToString("\n") { it.data() }
        fun flag(name: String, default: Boolean = false): Boolean {
            val value = Regex("\\b(?:var|let|const)\\s+$name\\s*=\\s*([^;\\r\\n]+)").find(scripts)?.groupValues?.get(1)?.trim()
                ?: return default
            if (value == "true" || value == "'true'" || value == "\"true\"") return true
            val comparison = Regex("""['"](true|false)['"]\s*={2,3}\s*['"](true|false)['"]""").matchEntire(value)
            return comparison?.let { it.groupValues[1] == it.groupValues[2] } ?: false
        }
        if (!flag("encryptEnabled")) throw AcademicException(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, "统一认证加密方式已变化，请使用网页登录")
        val skip = Regex("""\bcaptchaSkipN\s*=\s*['"]?(\d+)""").find(scripts)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val captcha = flag("casServerCaptchaEnabled") &&
            (!flag("casServerCaptchaSkipN") || flag("casServerCaptchaShow") || failedAttempts >= skip)
        return Page(fields, captcha, flag("mfaEnabled"))
    }

    fun encrypt(password: String, publicKey: String): String {
        try {
            val pem = publicKey.trim().removeSurrounding("\"")
                .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                .replace(Regex("\\s+"), "")
            val der = pem.decodeBase64()?.toByteArray() ?: error("Invalid public key")
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return "__RSA__" + cipher.doFinal(password.toByteArray(Charsets.UTF_8)).toByteString().base64()
        } catch (_: Exception) {
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "统一认证密码加密失败，请重新登录")
        }
    }

    fun redirect(html: String, base: HttpUrl): HttpUrl? = Regex(
        """(?:window\.|top\.|parent\.)?location(?:\.href)?\s*=\s*['"]([^'"\r\n]+)['"]""")
        .find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.let(base::resolve)

    fun failure(html: String): AcademicStatus? {
        val doc = Jsoup.parse(html)
        val text = doc.select("#msg, #errormsg, #error, .alert-danger, .alert-error, [role=alert]").text()
        val scripts = doc.select("script:not([src])").joinToString("\n") { it.data() }
        // Only inspect actual error values; templates also contain unused error-message labels.
        val errors = Regex("""\b(?:errors|errorMessage|errorMsg)\s*=\s*([^;\r\n]+)""")
            .findAll(scripts).joinToString(" ") { it.groupValues[1] }
        val message = "$text $errors"
        return when {
            Regex("(?i:invalid.*flow.*execution|flow.*execution.*expired)|登录流程.{0,20}(失效|过期)").containsMatchIn(message) -> AcademicStatus.SESSION_EXPIRED
            Regex("验证码.{0,20}(错误|失效|不正确|过期)|(?i:invalid.*captcha)").containsMatchIn(message) -> AcademicStatus.CAPTCHA_REQUIRED
            Regex("密码.{0,20}(错误|不正确|有误)|用户名或密码|账号或密码|用户不存在|认证失败|(?i:invalid.*credentials)").containsMatchIn(message) -> AcademicStatus.INVALID_CREDENTIALS
            Regex("多因素|二次认证|动态口令|(?i:multifactor|mfa-required)").containsMatchIn(message) -> AcademicStatus.HUMAN_VERIFICATION_REQUIRED
            else -> null
        }
    }
}
