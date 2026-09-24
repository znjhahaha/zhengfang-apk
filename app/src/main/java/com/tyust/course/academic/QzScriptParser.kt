package com.tyust.course.academic

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

internal data class QzCategoryConfig(
    val listUrl: String,
    val submitUrl: String,
    val submitMethod: String,
    val queryFields: Map<String, String>,
    val bodyFields: Set<String>,
    val requiresVerification: Boolean,
    val columns: List<String> = emptyList(),
    val submitFields: Map<String, String> = emptyMap(),
    val defaults: Map<String, String> = emptyMap(),
    val dropUrl: String = "",
    val dropMethod: String = "GET",
    val dropFields: Map<String, String> = emptyMap()
)

/** Reads a small, explicit subset of school JavaScript. Never evaluates downloaded code. */
internal object QzScriptParser {
    private val property = Regex("""["']?sAjaxSource["']?\s*[:=]\s*([^\r\n]+)""", RegexOption.IGNORE_CASE)

    fun parse(html: String, baseUrl: String, query: CourseQuery = CourseQuery()): QzCategoryConfig? {
        val document = Jsoup.parse(html, baseUrl)
        val values = linkedMapOf<String, String>()
        document.select("input[id], select[id]").forEach {
            values[it.id()] = when {
                it.attr("type").equals("checkbox", true) -> it.hasAttr("checked").toString()
                it.tagName() == "select" -> (it.selectFirst("option[selected]") ?: it.selectFirst("option"))?.attr("value").orEmpty()
                else -> it.attr("value")
            }
        }
        values["kcxx"] = query.keyword
        values["skls"] = query.teacher
        val listMatch = property.find(html) ?: return null
        val beforeList = html.substring(0, listMatch.range.first)
        val listValues = assignments(beforeList, values, false)
        val listExpression = listMatch.groupValues[1].trim().trimEnd(',')
        val list = expression(listExpression, listValues, false) ?: return null
        val operation = function(html, "xsxkOper")
        val opArgs = Regex("""function\s+xsxkOper\s*\(([^)]*)\)""").find(html)?.groupValues?.get(1)?.split(',')?.map(String::trim).orEmpty()
        val defaults = linkedMapOf<String, String>()
        Regex("""\bxsxkOper\s*\(([^)]*)\)""").findAll(codeMask(html)).forEach { call ->
            val arguments = html.substring(call.groups[1]!!.range)
            split(arguments, ',').forEachIndexed { index, argument ->
                if (index < opArgs.size && argument.trim().firstOrNull() in listOf('\'', '"'))
                    expression(argument, emptyMap(), false)?.let { defaults[opArgs[index]] = it }
            }
        }
        val submit = ajax(operation, baseUrl, values + opArgs.associateWith { token(it) } + defaults)
        val drop = ajax(function(html, "xstkOper"), baseUrl, mapOf("jx0404id" to token("jx0404id")))
        val verification = document.select("#sfyzmxk, input[name=sfyzmxk]").any { it.attr("value") == "1" } ||
            AcademicHtml.firstScriptValue(html, "sfyzmxk") == "1"
        val columns = Regex("""["']mDataProp["']\s*:\s*["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }.toList()
        return QzCategoryConfig(
            resolve(baseUrl, list), submit?.url.orEmpty(), submit?.method ?: "GET", emptyMap(),
            submit?.fields?.keys.orEmpty(), verification, columns, submit?.fields.orEmpty(), defaults,
            drop?.url.orEmpty(), drop?.method ?: "GET", drop?.fields.orEmpty()
        )
    }

    data class Ajax(val url: String, val method: String, val fields: Map<String, String>)

    data class DropControl(val function: String, val field: String, val id: String)

    fun dropControl(element: Element): DropControl? = element.select("a[href], [onclick]")
        .filter(AcademicHtml::isEnabledControl).firstNotNullOfOrNull { control ->
            val script = control.attr("onclick") + ";" + control.attr("href")
            val match = Regex("""\b(xstkOper|doQxtk)\s*\(\s*['"]([^'"]+)['"]\s*\)""").find(script)
                ?: return@firstNotNullOfOrNull null
            DropControl(match.groupValues[1], if (match.groupValues[1] == "doQxtk") "jx0501id" else "jx0404id", match.groupValues[2])
        }

    fun dropOperation(html: String, baseUrl: String, name: String = "xstkOper"): Ajax? {
        if (name !in setOf("xstkOper", "doQxtk")) return null
        val fields = AcademicHtml.hiddenFields(Jsoup.parse(html, baseUrl))
        val script = function(html, name)
        val argument = Regex("""function\s+""" + Regex.escape(name) + """\s*\(\s*(\w+)\s*\)""")
            .find(html)?.groupValues?.get(1) ?: return null
        val field = if (name == "doQxtk") "jx0501id" else "jx0404id"
        return ajax(script, baseUrl, fields + (argument to token(field)))
    }

    private fun ajax(script: String, baseUrl: String, initial: Map<String, String>): Ajax? {
        val urlMatch = Regex("""\burl\s*:\s*([^\r\n]+)""").find(script) ?: return null
        val values = assignments(script.substring(0, urlMatch.range.first), initial, true)
        val url = expression(urlMatch.groupValues[1].trim().trimEnd(','), values, true) ?: return null
        val fields = linkedMapOf<String, String>()
        val data = Regex("""\bdata\s*:\s*""").find(script)
        val objectData = Regex("""\bdata\s*:\s*\{([^}]*)\}""").find(script)
        if (data != null && objectData?.range?.first != data.range.first) return null
        objectData?.groupValues?.get(1)?.takeIf(String::isNotBlank)?.let { body ->
            split(body, ',').forEach { item ->
                val colon = item.indexOf(':')
                if (colon <= 0) return null
                val resolved = expression(item.substring(colon + 1), values, true) ?: return null
                fields[item.substring(0, colon).trim().trim('\'', '"')] = resolved
            }
        }
        val method = Regex("""\btype\s*:\s*["'](GET|POST)["']""", RegexOption.IGNORE_CASE).find(script)?.groupValues?.get(1)?.uppercase() ?: "GET"
        return Ajax(resolve(baseUrl, url), method, fields)
    }

    fun function(script: String, name: String): String {
        val match = Regex("""function\s+""" + Regex.escape(name) + """\s*\([^)]*\)\s*\{""").find(script) ?: return ""
        val start = match.range.last
        var depth = 1
        var quote: Char? = null
        var escaped = false
        for (i in start + 1 until script.length) {
            val c = script[i]
            if (quote != null) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
            } else when (c) {
                '\'', '"', '\u0060' -> quote = c
                '{' -> depth++
                '}' -> if (--depth == 0) return script.substring(start + 1, i)
            }
        }
        return ""
    }

    private fun assignments(script: String, initial: Map<String, String>, placeholders: Boolean): Map<String, String> {
        val values = initial.toMutableMap()
        Regex("""(?:\bvar\s+|\blet\s+|\bconst\s+|(?<=[;\n]))\s*(\w+)\s*=\s*([^;\r\n]+)""").findAll(script).forEach {
            expression(it.groupValues[2], values, placeholders)?.let { value -> values[it.groupValues[1]] = value }
        }
        return values
    }

    /** Retains offsets while hiding strings and comments, including generated button HTML. */
    private fun codeMask(script: String): String {
        val result = script.toCharArray()
        var index = 0
        while (index < script.length) {
            val start = index
            val character = script[index]
            val next = script.getOrNull(index + 1)
            when {
                character == '\'' || character == '"' || character == '`' -> {
                    index++
                    while (index < script.length) {
                        if (script[index] == '\\') { index = (index + 2).coerceAtMost(script.length); continue }
                        if (script[index++] == character) break
                    }
                }
                character == '/' && next == '/' -> {
                    index += 2
                    while (index < script.length && script[index] != '\n' && script[index] != '\r') index++
                }
                character == '/' && next == '*' -> {
                    val end = script.indexOf("*/", index + 2)
                    index = if (end < 0) script.length else end + 2
                }
                else -> { index++; continue }
            }
            for (position in start until index) result[position] = ' '
        }
        return String(result)
    }

    private fun expression(raw: String, values: Map<String, String>, placeholders: Boolean): String? {
        val parts = split(raw.trim(), '+')
        if (parts.size > 1) return parts.map { expression(it, values, placeholders) ?: return null }.joinToString("")
        val value = raw.trim().trimEnd(';')
        if (value.length >= 2 && value.first() in listOf('\'', '"') && value.last() == value.first())
            return value.substring(1, value.length - 1).replace("\\/", "/").replace("\\'", "'").replace("\\\"", "\"")
        Regex("""(?:encodeURI|encodeURIComponent)\(([\s\S]*)\)""").matchEntire(value)?.let {
            return expression(it.groupValues[1], values, placeholders)?.let(::encode)
        }
        Regex("""\$\(["']#([^"']+)["']\)\.(?:val\(\)|is\(["']:checked["']\))""").matchEntire(value)?.let {
            return values[it.groupValues[1]] ?: ""
        }
        Regex("""document\.getElementById\(["']([^"']+)["']\)\.value""").matchEntire(value)?.let {
            return values[it.groupValues[1]] ?: ""
        }
        values[value]?.let { return it }
        if (value in setOf("true", "false", "null") || value.toDoubleOrNull() != null) return value
        if (placeholders && value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return token(value)
        return null
    }

    private fun split(value: String, separator: Char): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var quote: Char? = null
        var depth = 0
        var escaped = false
        value.forEachIndexed { index, c ->
            if (quote != null) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
            } else when {
                c == '\'' || c == '"' -> quote = c
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == separator && depth == 0 -> { result += value.substring(start, index); start = index + 1 }
            }
        }
        result += value.substring(start)
        return result
    }

    fun expand(template: String, values: Map<String, String>, url: Boolean = false): String =
        Regex("__ACADEMIC_([A-Za-z0-9_]+)__").replace(template) { match ->
            val value = values[match.groupValues[1]]
                ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校页面缺少提交参数，需重新读取课程")
            if (url) encode(value) else value
        }

    /** Only menu items actually rendered by the school may become course categories. */
    fun categoryPages(html: String, baseUrl: String): List<Pair<String, String>> {
        val document = Jsoup.parse(html, baseUrl)
        val cases = Regex("""case\s+["']([^"']+)["']\s*:([\s\S]*?)(?=\bcase\b|$)""").findAll(function(html, "jpbxxk")).associate {
            val url = Regex("""\.attr\(\s*["']src["']\s*,\s*["']([^"']+)["']""").find(it.groupValues[2])?.groupValues?.get(1)
            it.groupValues[1] to url
        }
        val result = mutableListOf<Pair<String, String>>()
        document.select("[onclick]").forEach { element ->
            val key = Regex("""jpbxxk\(['"]([^'"]+)['"]\)""").find(element.attr("onclick"))?.groupValues?.get(1)
            val url = cases[key]
            if (url != null && url.contains("/xsxkkc/")) result += element.text() to resolve(baseUrl, url)
        }
        document.select("a[href]").forEach {
            val href = it.attr("href")
            if (href.contains("/xsxkkc/") && !href.contains("Oper", true) && !href.startsWith("javascript:"))
                result += it.text() to resolve(baseUrl, href)
        }
        return result.distinctBy { it.second }
    }

    fun selectedPage(html: String, baseUrl: String): String? =
        Regex("""['"]([^'"]*/xsxkjg/(?:comeXkjglb|xsxkXkjglb)[^'"]*)['"]""").find(html)?.groupValues?.get(1)?.let { resolve(baseUrl, it) }

    fun externalScriptUrls(html: String, baseUrl: String): List<String> =
        Jsoup.parse(html, baseUrl).select("script[src]").mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }

    private fun token(name: String) = "__ACADEMIC_" + name + "__"
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun resolve(base: String, value: String): String = URI(base).resolve(value).toString()
}
