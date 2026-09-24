package com.tyust.course.academic

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.nio.charset.Charset
import okio.ByteString.Companion.encodeUtf8

object LoginEncoding {
    fun qzNew(username: String, password: String, scode: String, sxh: String): String {
        val code = "${b64(username)}%%%${b64(password)}%%%${b64(" ")}"
        return insert(code, scode, sxh, 55)
    }

    fun qzOldBase64(username: String, password: String): String = "${b64(username)}%%%${b64(password)}"

    fun qzOldShift(username: String, password: String, scode: String, sxh: String): String =
        insert("$username%%%$password", scode, sxh, 20)

    private fun b64(value: String): String = value.encodeUtf8().base64()

    private fun insert(code: String, source: String, sxh: String, limit: Int): String {
        var remaining = source
        val out = StringBuilder(code.length + source.length)
        for (i in code.indices) {
            if (i >= limit || i >= sxh.length) {
                out.append(code.substring(i))
                break
            }
            val count = sxh[i].digitToIntOrNull()?.takeIf { it in 1..9 } ?: 0
            out.append(code[i])
            out.append(remaining.take(count))
            remaining = remaining.drop(count)
        }
        return out.toString()
    }
}

object SystemDetector {
    fun classify(html: String): AcademicSystem? {
        val lower = html.lowercase()
        val qzForm = Jsoup.parse(html).select("form[action]").any { form ->
            form.attr("action").contains("LoginToXk", true) &&
                form.select("input[name=userAccount], input[name=userPassword], input[name=encoded]").size >= 3
        }
        return when {
            lower.contains("default2.aspx") && (lower.contains("txtkeymodulus") || lower.contains("checkcode")) -> AcademicSystem.ZF_OLD
            lower.contains("login_getpublickey") || (lower.contains("csrftoken") && lower.contains("xtgl")) -> AcademicSystem.ZF
            Regex("\\b(?:var|let|const)\\s+scode\\b").containsMatchIn(lower) && Regex("\\b(?:var|let|const)\\s+sxh\\b").containsMatchIn(lower) && lower.contains("logintoxk") -> AcademicSystem.QZ
            lower.contains("flag=sess") || ((qzForm || lower.contains("encodeinp")) && lower.contains("%%%")) -> AcademicSystem.QZ_OLD
            else -> null
        }
    }
}

object AcademicHtml {
    fun isEnabledControl(element: Element): Boolean =
        (listOf(element) + element.parents()).none {
            it.hasAttr("disabled") || it.hasAttr("hidden") || it.attr("aria-disabled").equals("true", true) ||
                Regex("""(?:^|;)\s*(?:display\s*:\s*none|visibility\s*:\s*hidden)\s*(?:!important)?\s*(?:;|$)""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(it.attr("style"))
        } && !element.attr("type").equals("hidden", true)

    fun isLoginPage(html: String): Boolean {
        val document = Jsoup.parse(html)
        val directCasRedirect = document.body().text().isBlank() && document.select("script").any {
            Regex("""^\s*(?:(?:top|window|parent)\.)?location(?:\.href)?\s*=\s*['"]https?://[^'"\s]+/authserver/login(?:\?[^'"]*)?['"]\s*;?\s*$""")
                .matches(it.data())
        }
        return document.select("input[type=password], input[name=mm], input[name=TextBox2]").isNotEmpty() ||
            Regex("(?:top\\.|window\\.|parent\\.)?location(?:\\.href)?\\s*=\\s*['\"][^'\"]*(?:login_slogin|default2\\.aspx|LoginToXk)", RegexOption.IGNORE_CASE).containsMatchIn(html) || directCasRedirect
    }
    fun parse(html: String, baseUrl: String, charset: Charset = Charsets.UTF_8) =
        Jsoup.parse(html, baseUrl)

    fun hiddenFields(document: org.jsoup.nodes.Document): Map<String, String> =
        document.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }

    fun controlValue(element: Element): String = when {
        element.tagName() == "option" && !element.hasAttr("value") -> element.text()
        element.tagName() == "input" && element.attr("type").lowercase() in setOf("checkbox", "radio") && !element.hasAttr("value") -> "on"
        else -> element.attr("value")
    }

    fun formFields(form: Element, clickedSubmit: Pair<String, String>? = null): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (element in form.select("input[name], button[name], select[name], textarea[name]")) {
            val name = element.attr("name")
            if (name.isBlank() || element.hasAttr("disabled")) continue
            when (element.tagName()) {
                "select" -> {
                    val selected = element.select("option[selected]").ifEmpty {
                        if (!element.hasAttr("multiple") && (element.attr("size").toIntOrNull() ?: 1) <= 1)
                            element.select("option").filter { !it.hasAttr("disabled") && it.parent()?.hasAttr("disabled") != true }.take(1)
                        else emptyList()
                    }.filter { !it.hasAttr("disabled") && it.parent()?.hasAttr("disabled") != true }
                    selected.forEach { result += name to controlValue(it) }
                }
                "textarea" -> result += name to element.text()
                else -> {
                    val type = element.attr("type").lowercase().ifBlank { if (element.tagName() == "button") "submit" else "text" }
                    if (type == "submit" || type == "button") {
                        if (clickedSubmit?.first == name && clickedSubmit.second == element.attr("value")) result += name to element.attr("value")
                    } else if ((type == "checkbox" || type == "radio") && !element.hasAttr("checked")) {
                        continue
                    } else if (type != "file" && type != "reset") {
                        result += name to controlValue(element)
                    }
                }
            }
        }
        return result
    }

    fun action(form: Element, baseUrl: String): String = URI(baseUrl).resolve(form.attr("action").ifBlank { baseUrl }).toString()

    /** Reads an explicitly assigned form action without executing school JavaScript. */
    fun queryFormAction(form: Element, html: String, baseUrl: String): String? {
        val declared = form.attr("action").takeIf(String::isNotBlank)
        val name = form.attr("name").ifBlank { form.id() }
        val assigned = if (name.isBlank()) null else {
            val target = """document\.forms\s*\[\s*['"]""" + Regex.escape(name) + """['"]\s*\]\.action\s*=\s*"""
            val literal = Regex(target + """['"]([^'"]+)['"]""").findAll(html).map { it.groupValues[1] }
            // Support the school's adjacent local literal assignment; no expressions or JS execution.
            val local = Regex("""\b(?:var|let|const)\s+([A-Za-z_$][\w$]*)\s*=\s*['"]([^'"]+)['"]\s*;\s*""" +
                target + """\1\s*;""").findAll(html).map { it.groupValues[2] }
            (literal + local).distinct().toList().singleOrNull()
        }
        return (declared ?: assigned)?.let { URI(baseUrl).resolve(it).toString() }
    }

    fun firstScriptValue(html: String, name: String): String? {
        val regex = Regex("(?:var\\s+)?${Regex.escape(name)}\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]")
        return regex.find(html)?.groupValues?.getOrNull(1)
    }

    fun scriptUrls(document: org.jsoup.nodes.Document, baseUrl: String): List<String> =
        document.select("script[src]").mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }
}
