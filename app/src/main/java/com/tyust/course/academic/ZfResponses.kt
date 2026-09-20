package com.tyust.course.academic

import org.json.JSONObject
import org.jsoup.Jsoup

/** Preserve the school's actual failure instead of treating every HTML response as a broken list. */
internal object ZfResponses {
    fun requirePage(response: AcademicResponse) {
        if (AcademicHtml.isLoginPage(response.text))
            throw AcademicException(AcademicStatus.SESSION_EXPIRED, "登录已失效，请重新登录")
        if (response.code !in 200..299) throw AcademicException(
            if (response.code >= 500 || response.code == 429) AcademicStatus.NETWORK_RETRYABLE
            else if (response.code in setOf(401, 403)) AcademicStatus.SESSION_EXPIRED else AcademicStatus.PAGE_CHANGED,
            "教务请求失败（HTTP ${response.code}），请稍后重试")
        if (Jsoup.parse(response.text).select(".error_v5, .error_con, .error_text").isNotEmpty())
            throw AcademicException(if (AcademicJson.status(response.text) == AcademicStatus.ROUND_CLOSED)
                AcademicStatus.ROUND_CLOSED else AcademicStatus.PAGE_CHANGED, message(response.text))
    }

    fun list(response: AcademicResponse, vararg keys: String): List<JSONObject> {
        requirePage(response)
        return try { AcademicJson.objects(response.text.trim().removePrefix("\uFEFF"), *keys) }
        catch (e: AcademicException) {
            if (e.status == AcademicStatus.ROUND_CLOSED)
                throw AcademicException(e.status, "当前未开放选课，请在学校规定的选课时间重试")
            val json = runCatching { JSONObject(response.text) }.getOrNull()
            if (json != null && listOf("message", "msg", "msgContent", "error").any { !json.optString(it).isNullOrBlank() })
                throw AcademicException(e.status, message(response.text))
            throw e
        }
    }

    fun message(body: String): String {
        val doc = Jsoup.parse(body)
        val detail = doc.select(".error_text, .error_text0").eachText().filter(String::isNotBlank).joinToString("；")
        return detail.takeIf(String::isNotBlank)?.take(180) ?: AcademicJson.message(body)
            .ifBlank { "教务系统返回错误，请稍后重试" }
    }
}
