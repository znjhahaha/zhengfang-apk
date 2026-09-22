package com.tyust.course.academic

import com.tyust.course.model.Course
import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigInteger
import java.net.URI
import java.nio.charset.Charset
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import okio.ByteString.Companion.decodeBase64

internal object AcademicCrypto {
    fun rsaBase64(modulus: String, exponent: String, password: String): String {
        val n = BigInteger(1, modulus.decodeBase64()?.toByteArray() ?: ByteArray(0))
        val e = BigInteger(1, exponent.decodeBase64()?.toByteArray() ?: ByteArray(0))
        return rsa(RSAPublicKeySpec(n, e), password) { okio.ByteString.of(*it).base64() }
    }

    fun rsaHex(modulus: String, exponent: String, password: String): String {
        val n = BigInteger(modulus, 16)
        val e = BigInteger(exponent, 16)
        return rsa(RSAPublicKeySpec(n, e), password) { bytes -> bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } }
    }

    private fun rsa(spec: RSAPublicKeySpec, value: String, encode: (ByteArray) -> String): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(spec)
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key)
            encode(doFinal(value.toByteArray(Charsets.UTF_8)))
        }
    }
}

internal object AcademicJson {
    fun objects(body: String, vararg keys: String): List<JSONObject> {
        if (AcademicHtml.isLoginPage(body)) throw AcademicException(AcademicStatus.SESSION_EXPIRED, "登录已失效，请重新登录")
        val root = runCatching { JSONObject(body) }.getOrNull()
        val candidates = if (root == null) runCatching { JSONArray(body) }.getOrNull() else {
            val containers = listOfNotNull(root, root.optJSONObject("data"), root.optJSONObject("result"))
            containers.asSequence().flatMap { container -> (keys.toList() + listOf("data", "aaData", "rows", "list")).asSequence().mapNotNull { container.optJSONArray(it) } }.firstOrNull()
        } ?: throw AcademicException(status(body).takeUnless { it == AcademicStatus.SUCCESS || it == AcademicStatus.VALIDATION_FAILED }
            ?: AcademicStatus.PAGE_CHANGED, "教务返回了无法识别的列表，请重新进入页面")
        val rejected = root != null && ((root.has("success") && !root.optBoolean("success")) ||
            (root.has("code") && root.optString("code") !in setOf("0", "200")) ||
            (root.has("flag") && root.optString("flag") in setOf("false", "0")))
        if (rejected) throw AcademicException(status(body).takeUnless { it == AcademicStatus.SUCCESS } ?: AcademicStatus.VALIDATION_FAILED, message(body))
        return (0 until candidates.length()).mapNotNull { candidates.optJSONObject(it) }
    }

    fun string(json: JSONObject, vararg names: String): String = names.asSequence()
        .filterNot { json.isNull(it) }.map { json.optString(it, "") }.firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()

    fun int(json: JSONObject, vararg names: String): Int? = names.asSequence().mapNotNull { name ->
        if (!json.has(name) || json.isNull(name)) null else json.optString(name, "").toIntOrNull()
    }.firstOrNull()

    fun fields(json: JSONObject): Map<String, String> {
        val result = linkedMapOf<String, String>()
        json.keys().forEach { key -> if (!json.isNull(key)) result[key] = json.optString(key, "") }
        return result
    }

    fun hasSuccess(body: String): Boolean {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        if (json.has("success")) return json.optBoolean("success", false)
        return json.optString("flag") == "1" || (json.has("code") && json.optString("code") == "0")
    }

    fun status(body: String, code: Int = 200): AcademicStatus {
        if (code == 401 || code == 403) return AcademicStatus.SESSION_EXPIRED
        if (code == 429) return AcademicStatus.NETWORK_RETRYABLE
        if (code >= 500) return AcademicStatus.RESULT_UNKNOWN
        if (AcademicHtml.isLoginPage(body) || listOf("请先登录", "登录超时", "会话失效", "notLogin").any { body.contains(it, true) }) return AcademicStatus.SESSION_EXPIRED
        val detail = message(body)
        if (listOf("密码错误", "用户名或密码", "账号或密码").any(detail::contains)) return AcademicStatus.INVALID_CREDENTIALS
        if (detail.contains("验证码") || detail.contains("verifyCode", true)) return AcademicStatus.HUMAN_VERIFICATION_REQUIRED
        val conflictId = runCatching { JSONObject(body).optString("yxjx0404id") }.getOrDefault("")
        if (detail.contains("冲突") || conflictId !in setOf("", "null")) return AcademicStatus.CONFLICT
        if (listOf("学分上限", "超过学分", "超出学分", "学分限制").any(detail::contains)) return AcademicStatus.CREDIT_LIMIT
        if (listOf("未开始", "未开放", "已结束", "选课时间已过", "不在选课时间", "只可退课").any(detail::contains)) return AcademicStatus.ROUND_CLOSED
        if (listOf("已选该", "已经选", "重复选课").any(detail::contains)) return AcademicStatus.ALREADY_SELECTED
        if (listOf("已满", "无余量", "名额不足", "容量不足").any(detail::contains)) return AcademicStatus.NO_CAPACITY
        if (code !in 200..299) return AcademicStatus.VALIDATION_FAILED
        return if (hasSuccess(body)) AcademicStatus.SUCCESS else AcademicStatus.VALIDATION_FAILED
    }

    fun zfStatus(body: String, code: Int = 200): AcademicStatus {
        if (code in 200..299) {
            if (body.trim().trim('"') == "1") return AcademicStatus.SUCCESS
            val json = runCatching { JSONObject(body) }.getOrNull()
            // ZF's front end decodes this ASCII result as a class with no remaining places.
            if (json?.optString("flag") in setOf("-1", "0") &&
                Regex("\\d+,[0-9A-Za-z]+,\\d*,?").matches(json?.optString("msg").orEmpty().trim()))
                return AcademicStatus.NO_CAPACITY
        }
        return status(body, code)
    }

    fun message(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        return (json?.let { string(it, "message", "msg", "msgContent", "error") }?.takeIf(String::isNotBlank)
            ?: org.jsoup.Jsoup.parse(body).text()).take(180)
    }

    fun operationFields(json: JSONObject): Map<String, String> {
        val result = fields(json).toMutableMap()
        val oper = string(json, "czOper", "oper", "operation")
        val args = Regex("xsxkFun\\s*\\(([^)]*)\\)").find(oper)?.groupValues?.getOrNull(1)
            ?.let { Regex("['\\\"]([^'\\\"]*)['\\\"]").findAll(it).map { match -> match.groupValues[1] }.toList() }
            .orEmpty()
        if (args.size >= 1) result["jx0404id"] = args[0]
        if (args.size >= 2) result["kcid"] = args[1]
        if (args.size >= 3) result["cfbs"] = args[2]
        if (args.size >= 4) result["xqid"] = args[3]
        return result
    }
}

internal abstract class BaseAcademicAdapter(
    protected val school: SchoolConfig,
    protected val session: AcademicSession,
    protected val transport: AcademicHttpTransport,
    private val system: AcademicSystem
) : AcademicProtocolAdapter, SessionBackedAdapter {
    override fun cookieHeader(): String = session.cookieHeader()
    internal suspend fun <T> inSession(block: suspend () -> T): T = serial(block)
    override suspend fun validateSession(): LoginResult = serial {
        val path = when (system) {
            AcademicSystem.ZF -> "xtgl/index_cxYhxxIndex.html?gnmkdm=index"
            AcademicSystem.ZF_OLD -> "xs_main.aspx?xh=${java.net.URLEncoder.encode(session.username, "UTF-8")}"
            AcademicSystem.QZ -> "framework/xsMainV_new.htmlx?t1=1"
            else -> "framework/xsMain.jsp"
        }
        val response = transport.get(transport.appUrl(path))
        if (AcademicHtml.isLoginPage(response.text) || response.code !in 200..299) return@serial LoginResult(AcademicStatus.SESSION_EXPIRED)
        var identity = parseName(org.jsoup.Jsoup.parse(response.text, response.url))
        if (system == AcademicSystem.ZF && identity.second.isBlank()) identity = zfMenuIdentityFallback(identity)
        val authenticated = identity.first.isNotBlank() || identity.second.isNotBlank() ||
            listOf("xsxk.aspx", "xklc_list", "退出登录", "学期理论课表", "选课结果").any(response.text::contains)
        if (!authenticated) return@serial LoginResult(AcademicStatus.SESSION_EXPIRED, message = "无法验证教务登录状态")
        val studentId = identity.second.ifBlank { session.username }
        if (studentId.isNotBlank()) session.username = studentId
        LoginResult(AcademicStatus.SUCCESS, identity.first, studentId)
    }

    /**
     * ZF: 部分学校（如河北传媒学院）的 cxYhxx 页面是定制变体，仅含姓名不含学号。
     * 回退到框架首页 initMenu 读取隐藏域 sessionUserKey（正方 v5 标准结构）。
     */
    protected suspend fun zfMenuIdentityFallback(identity: Pair<String, String>): Pair<String, String> {
        val menu = try {
            transport.get(transport.appUrl("xtgl/index_initMenu.html"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return identity
        }
        if (AcademicHtml.isLoginPage(menu.text)) return identity
        val fallback = parseName(org.jsoup.Jsoup.parse(menu.text, menu.url))
        return identity.first.ifBlank { fallback.first } to fallback.second
    }
    protected fun check(context: CourseContext) {
        if (context.sessionEpoch != session.epoch) throw AcademicException(AcademicStatus.SESSION_EXPIRED, "Course context belongs to an expired session")
    }

    protected suspend fun <T> serial(block: suspend () -> T): T =
        if (system.serial) session.withProtocolLock(block) else block()

    protected fun scope(context: CourseContext, id: String): CourseScope =
        context.scopes.firstOrNull { it.id == id } ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "Course scope is no longer available")

    protected fun offer(json: JSONObject, scopeId: String, idNames: Array<String>): CourseOffer {
        val id = AcademicJson.string(json, *idNames).ifBlank { AcademicJson.string(json, "kch", "kcid", "jx0404id") }
        return CourseOffer(id, AcademicJson.string(json, "kcmc", "kc_mc", "fzmc", "ktmc"),
            AcademicJson.string(json, "jsxm", "skls", "xm", "jsxx"),
            AcademicJson.string(json, "sksj", "sksjmc"), AcademicJson.string(json, "jxdd", "skdd", "skddyw"),
            AcademicJson.string(json, "xf"), AcademicJson.int(json, "jxbrl", "capacity", "xkrs"),
            AcademicJson.int(json, "yxzrs", "yxrs", "selected"), scopeId, AcademicJson.operationFields(json) + mapOf("scopeId" to scopeId, "sessionEpoch" to session.epoch.toString(), "academic_system" to system.id))
    }

    protected fun section(json: JSONObject, course: CourseOffer): CourseSection {
        val id = if (system == AcademicSystem.ZF) AcademicJson.string(json, "jxb_id", "do_jxb_id")
            else AcademicJson.string(json, "do_jxb_id", "jxb_id", "jx0404id", "jx02id", "kcid", "kch")
        return CourseSection(id, course.stableId, AcademicJson.string(json, "jxbmc", "ktmc", "fzmc"),
            AcademicJson.string(json, "jsxm", "skls", "xm", "jsxx"), AcademicJson.string(json, "sksj", "sksjmc"),
            AcademicJson.string(json, "jxdd", "skdd"), AcademicJson.int(json, "jxbrl", "capacity"),
            AcademicJson.int(json, "yxzrs", "yxrs", "selected"), AcademicJson.fields(json),
            if (system == AcademicSystem.ZF) AcademicJson.string(json, "do_jxb_id", "jxb_id") else id)
    }

    protected fun selected(json: JSONObject): SelectedCourse = SelectedCourse(
        AcademicJson.string(json, "do_jxb_id", "jxb_id", "jx0404id", "jx0501id", "kch"),
        AcademicJson.string(json, "kcmc", "kc_mc", "kc_mc"), AcademicJson.string(json, "jsxm", "skls", "xm"),
        AcademicJson.string(json, "kch_id", "kcid", "jx02id", "kch"), AcademicJson.string(json, "jxb_id", "jx0404id", "jx0501id"), AcademicJson.fields(json)
    )
}

internal fun Document.firstForm(): Element? = select("form").firstOrNull()

internal fun parseName(document: Document): Pair<String, String> {
    if (AcademicHtml.isLoginPage(document.html())) return "" to ""
    document.select(".infoContentTitle, .userInfo, .user-info").forEach { element ->
        val match = Regex("^(.+?)[-\\[]([A-Za-z0-9]{6,})\\]?$").find(element.text().trim())
        if (match != null) return match.groupValues[1].trim() to match.groupValues[2]
    }
    val profileFields = document.select(".middletopdwxxtit").associate { label ->
        label.text().replace(" ", "").trimEnd(':', '：') to
            label.nextElementSibling()?.takeIf { it.hasClass("middletopdwxxcont") }?.text().orEmpty()
    }
    val name = document.select("input[name=xm], .media-heading, [name=studentName], #xhxm, .user-name, #studentName")
        .firstOrNull()?.let { element ->
            val raw = element.attr("value").ifBlank { element.text() }
            // input 的 value 是纯姓名，不做角色后缀剥离；文本节点（media-heading 等）可能带"学生/教师"标签
            cleanIdentityText(raw, stripRoleSuffix = element.tagName() != "input")
        }.orEmpty()
        .ifBlank { profileFields["学生姓名"].orEmpty() }
    val id = document.select("input[name=xh], input[name=studentId], #studentId, #sessionUserKey")
        .firstOrNull()?.let { it.attr("value").ifBlank { it.text() } }?.trim().orEmpty()
        .ifBlank { profileFields["学号"].orEmpty() }
    return name.trim() to id.trim()
}

/**
 * 归一空白；文本源可选剥离"同学/学生/教师/老师"身份后缀。
 * 兼容正方 v5 定制首页的 media-heading 变体："姓名&nbsp;&nbsp;学生"。
 */
private fun cleanIdentityText(raw: String, stripRoleSuffix: Boolean): String {
    val normalized = raw.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    return if (stripRoleSuffix) {
        normalized.removeSuffix("同学").removeSuffix("学生").removeSuffix("教师").removeSuffix("老师").trim()
    } else {
        normalized
    }
}

internal suspend fun <T> AcademicProtocolAdapter.inSession(block: suspend () -> T): T =
    if (this is BaseAcademicAdapter) inSession(block)
    else if (this is BuiltinAcademicProvider) inSession(block)
    else if (this is com.tyust.course.academic.plugin.PluginAcademicAdapter) session.withProtocolLock(block)
    else block()

internal fun String.withCharset(name: String): String = runCatching { toByteArray(Charsets.UTF_8).toString(Charset.forName(name)) }.getOrDefault(this)
