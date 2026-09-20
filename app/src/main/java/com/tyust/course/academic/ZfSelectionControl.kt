package com.tyust.course.academic

import com.tyust.course.model.Course
import org.jsoup.Jsoup
import java.net.URLEncoder

/** These are independent server values. Never infer one from the other's length or contents. */
data class ZfSelectionControl(val id: String = "", val xh: String = "") {
    val isEmpty get() = id.isBlank() && xh.isBlank()
    val primaryKey get() = if (id.isNotBlank()) "xkkz_id" else "xkkz_xh"
    val primaryValue get() = id.ifBlank { xh }
    fun withReturned(values: Map<String, String>): ZfSelectionControl {
        val returned = from(values)
        if (conflictsWith(returned)) return this
        return ZfSelectionControl(id.ifBlank { returned.id }, xh.ifBlank { returned.xh })
    }
    fun fields(both: Boolean = true): Map<String, String> = buildMap {
        if (id.isNotBlank()) put("xkkz_id", id)
        if (xh.isNotBlank() && (both || id.isBlank())) put("xkkz_xh", xh)
    }
    fun applyTo(values: MutableMap<String, String>, both: Boolean = true) {
        listOf("xkkz_id", "xkkz_xh", "firstXkkzId", "firstXkkzXh").forEach(values::remove)
        values.putAll(fields(both))
    }
    fun applyTo(course: Course) {
        course._xkkz_id = id; course._xkkz_xh = xh
        applyTo(course.completeParams)
        if (!isEmpty) course.completeParams.remove("zf_refresh_controls")
    }
    companion object {
        @JvmStatic fun from(values: Map<String, String>?): ZfSelectionControl {
            fun value(key: String) = values?.get(key).orEmpty().trim()
            val current = ZfSelectionControl(value("xkkz_id"), value("xkkz_xh"))
            val first = ZfSelectionControl(value("firstXkkzId"), value("firstXkkzXh"))
            return current.fillFromSameControl(first)
        }
        @JvmStatic fun forCourse(course: Course, fallback: Map<String, String>? = null): ZfSelectionControl {
            if (course.completeParams["zf_refresh_controls"] == "true") return from(fallback)
            val current = ZfSelectionControl(course._xkkz_id.orEmpty(), course._xkkz_xh.orEmpty())
                .fillFromSameControl(from(course.completeParams))
            val sameCategory = course.kklxdm.isNullOrBlank() || fallback?.get("kklxdm").isNullOrBlank() ||
                course.kklxdm == fallback?.get("kklxdm")
            return if (sameCategory) current.fillFromSameControl(from(fallback)) else current
        }
        @JvmStatic fun appendToBody(body: String, values: Map<String, String>?, course: Course?, both: Boolean): String {
            val control = if (course != null) forCourse(course, values) else from(values)
            if (control.isEmpty) return body
            val existing = body.split('&').filter { it.substringBefore('=') !in setOf("xkkz_id", "xkkz_xh") }
            return (existing + control.fields(both).map { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }).joinToString("&")
        }
    }

    /** A missing half must never be borrowed from a different category's control pair. */
    private fun fillFromSameControl(other: ZfSelectionControl): ZfSelectionControl {
        if (isEmpty) return other
        if (conflictsWith(other)) return this
        val matches = id.isNotBlank() && id == other.id || xh.isNotBlank() && xh == other.xh
        return if (matches) ZfSelectionControl(id.ifBlank { other.id }, xh.ifBlank { other.xh }) else this
    }

    private fun conflictsWith(other: ZfSelectionControl): Boolean =
        (id.isNotBlank() && other.id.isNotBlank() && id != other.id) ||
            (xh.isNotBlank() && other.xh.isNotBlank() && xh != other.xh)
}

data class ZfCourseCategory(val category: String, val control: ZfSelectionControl, val grade: String, val major: String, val name: String = "") {
    fun merge(index: Map<String, String>, display: Map<String, String> = emptyMap()): MutableMap<String, String> = index.toMutableMap().apply {
        listOf("firstXkkzId", "firstXkkzXh", "xkkz_id", "xkkz_xh").forEach(::remove)
        putAll(display)
        control.withReturned(display).applyTo(this)
        put("kklxdm", category); put("njdm_id", grade); put("zyh_id", major)
        if (name.isNotBlank()) put("kklxmc", name)
    }
}

object ZfCourseCategories {
    @JvmStatic fun forSavedCategory(html: String, index: Map<String, String>, category: String): ZfCourseCategory? =
        if (category.isBlank()) null else parse(html, index).filter { it.category == category }.singleOrNull()

    @JvmStatic fun parse(html: String, index: Map<String, String>): List<ZfCourseCategory> {
        val first = ZfSelectionControl.from(mapOf("xkkz_id" to index["firstXkkzId"].orEmpty(), "xkkz_xh" to index["firstXkkzXh"].orEmpty()))
            .takeUnless { it.isEmpty } ?: ZfSelectionControl.from(index)
        val xhOnly = first.id.isBlank() && first.xh.isNotBlank()
        val calls = Regex("""queryCourse\s*\(\s*(?:this\s*,\s*)?((?:['"][^'"]*['"]\s*,\s*){3,4}['"][^'"]*['"])\s*\)""")
        val rows = calls.findAll(Jsoup.parse(html).html()).map { call ->
            val args = Regex("""['"]([^'"]*)['"]""").findAll(call.groupValues[1]).map { it.groupValues[1] }.toList()
            val control = if (xhOnly) ZfSelectionControl(xh = args[1]) else ZfSelectionControl(args[1],
                args.getOrNull(4).orEmpty().ifBlank { if (args[1] == first.id) first.xh else "" })
            ZfCourseCategory(args[0], control, args[2], args[3])
        }.distinct().toList()
        if (rows.isNotEmpty()) return rows
        if (first.isEmpty) return emptyList()
        fun value(primary: String, fallback: String) = index[primary]?.takeIf(String::isNotBlank) ?: index[fallback].orEmpty()
        return listOf(ZfCourseCategory(value("firstKklxdm", "kklxdm"), first,
            value("firstNjdmId", "njdm_id"), value("firstZyhId", "zyh_id"), value("firstKklxmc", "kklxmc")))
    }
}

enum class ZfRequestKind { DISPLAY, COURSES, SECTIONS, SECTIONS_LEGACY, DETAILS, SELECTION, SELECTED }

/** Endpoint-specific allowlists derived from zzxkYzb.js and the sanitized Issue #10 capture. */
object ZfRequestParams {
    private val common = "rwlx xklc xkly bklx_id sfkkjyxdxnxq kzkcgs xqh_id jg_id njdm_id zyh_id zyfx_id bh_id xbm xslbdm mzm xz ccdm xsbj gnjkxdnj sfkknj sfkkzy kzybkxy sfznkx zdkxms sfkxq sfkcfx kkbk kkbkdj bklbkcj xkxnm xkxqm kklxdm bbhzxjxb zxgbxkkg rlkz".split(' ')
    private val listFields = common + "njdm_id_1 zyh_id_1 njdm_id_xs zyh_id_xs bjgkczxbbjwcx bhbcyxkjxb sfkgbcx sfrxtgkcxd tykczgxdcs xkzgbj jxbzb zh kspage jspage".split(' ')
    private val sectionFields = common + "kch_id bhbcyxkjxb jxbzcxskg xkxskcgskg cxbj fxbj cxcykclxxskg rlzlkz txbsfrl cdrlkz".split(' ')
    private val selectionFields = "jxb_ids kch_id kcmc rwlx rlkz cdrlkz rlzlkz sxbj xxkbj qz cxbj njdm_id zyh_id kklxdm xklc xkxnm xkxqm jcxx_id xkly sfkxq".split(' ')
    private fun keys(kind: ZfRequestKind) = when (kind) {
        ZfRequestKind.DISPLAY -> "kklxdm xszxzt njdm_id zyh_id kspage jspage".split(' ')
        ZfRequestKind.COURSES -> listFields
        ZfRequestKind.SECTIONS -> sectionFields
        ZfRequestKind.SECTIONS_LEGACY -> listFields + "kch_id"
        ZfRequestKind.DETAILS -> "jxb_ids kch_id bklx_id kklxdm njdm_id rlkz xklc zyh_id".split(' ')
        ZfRequestKind.SELECTION -> selectionFields
        ZfRequestKind.SELECTED -> "xkxnm xkxqm xqh_id jg_id njdm_id zyh_id zyfx_id bh_id xz ccdm xkly".split(' ')
    }
    @JvmStatic fun build(kind: ZfRequestKind, source: Map<String, String>): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        keys(kind).forEach { key -> source[key]?.let { result[key] = it } }
        if (kind != ZfRequestKind.SELECTED) result.putAll(ZfSelectionControl.from(source).fields(kind in setOf(ZfRequestKind.COURSES, ZfRequestKind.SECTIONS_LEGACY)))
        if (kind in setOf(ZfRequestKind.COURSES, ZfRequestKind.SECTIONS, ZfRequestKind.SECTIONS_LEGACY, ZfRequestKind.SELECTED))
            source["jg_id_1"]?.takeIf(String::isNotBlank)?.let { result["jg_id"] = it }
        if (kind == ZfRequestKind.COURSES) source.filterKeys { it.startsWith("filter_list[") }.let(result::putAll)
        return result
    }

    /** Preserve the caller's encoding and filters while stripping unrelated hidden-page fields. */
    @JvmStatic fun filterBody(body: String, url: String): String {
        val kind = when {
            url.contains("zzxkyzb_cxZzxkYzbPartDisplay.html") -> ZfRequestKind.COURSES
            url.contains("zzxkyzbjk_cxJxbWithKchZzxkYzb.html") -> ZfRequestKind.SECTIONS
            url.contains("zzxkyzb_cxJxbWithKchZzxkYzb.html") -> ZfRequestKind.SECTIONS_LEGACY
            url.contains("zzxkyzb_cxZzxkYzbDisplay.html") -> ZfRequestKind.DISPLAY
            url.contains("zzxkyzb_cxZkcZzxkYzb.html") -> ZfRequestKind.DETAILS
            url.contains("zzxkyzbjk_xkBcZyZzxkYzb.html") -> ZfRequestKind.SELECTION
            url.contains("zzxkyzb_cxZzxkYzbChoosedDisplay.html") -> ZfRequestKind.SELECTED
            else -> return body
        }
        val pairs = body.split('&').filter(String::isNotBlank).associate { raw ->
            runCatching { java.net.URLDecoder.decode(raw.substringBefore('='), "UTF-8") }.getOrDefault(raw.substringBefore('=')) to raw
        }
        val hasId = pairs["xkkz_id"]?.substringAfter('=', "").orEmpty().isNotBlank()
        val both = kind in setOf(ZfRequestKind.COURSES, ZfRequestKind.SECTIONS_LEGACY)
        val allowed = keys(kind).toSet()
        return pairs.filter { (key, value) -> when (key) {
            "xkkz_id" -> kind != ZfRequestKind.SELECTED && value.substringAfter('=', "").isNotBlank()
            "xkkz_xh" -> kind != ZfRequestKind.SELECTED && (both || !hasId) && value.substringAfter('=', "").isNotBlank()
            else -> key in allowed || (kind == ZfRequestKind.COURSES && key.startsWith("filter_list["))
        } }.values.joinToString("&")
    }
}
