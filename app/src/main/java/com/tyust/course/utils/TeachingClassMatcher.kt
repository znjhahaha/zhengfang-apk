package com.tyust.course.utils

import com.tyust.course.model.Course
import org.json.JSONArray
import org.json.JSONObject

/** Shared by the legacy queue and service so a manual class cannot fall back to another class. */
object TeachingClassMatcher {
    @JvmStatic
    fun matchesName(row: JSONObject, requested: String?): Boolean =
        requested.isNullOrBlank() || normalize(row.optString("jxbmc")).contains(normalize(requested))

    @JvmStatic
    fun matchesRequest(row: JSONObject, course: Course): Boolean {
        if (!course.hasTeachingClassFilter()) return true
        return matchesName(row, course.teachingClassFilter) &&
            (course.teacher.isNullOrBlank() || teacher(row).contains(course.teacher.trim(), ignoreCase = true)) &&
            (course.time.isNullOrBlank() || normalizeTime(row.optString("sksj")).contains(normalizeTime(course.time)))
    }

    @JvmStatic
    fun canUseSavedClass(course: Course): Boolean = !course.useExactMatch && !course.hasTeachingClassFilter()

    @JvmStatic
    fun selectRow(rows: JSONArray, course: Course): JSONObject? {
        val candidates = (0 until rows.length()).mapNotNull(rows::optJSONObject)
            .filter { matchesRequest(it, course) }
            .distinctBy { it.optString("jxb_id").ifBlank { it.optString("do_jxb_id") } }
        if (course.useExactMatch && !course.classId.isNullOrBlank()) {
            val exact = candidates.filter {
                it.optString("jxb_id") == course.classId || it.optString("do_jxb_id") == course.classId
            }
            if (exact.isNotEmpty()) return exact.singleOrNull()
            if (course.completeParams["academic_stable_section"] == "true") return null
            // Old queues can contain an expired operation token. Rebind only with
            // independent class-name plus teacher/time evidence, and only uniquely.
            if (!course.hasTeachingClassFilter()) return candidates.filter {
                !course.jxbmc.isNullOrBlank() && normalize(it.optString("jxbmc")) == normalize(course.jxbmc) &&
                    (!course.teacher.isNullOrBlank() || !course.time.isNullOrBlank()) &&
                    (course.teacher.isNullOrBlank() || normalize(teacher(it)) == normalize(course.teacher)) &&
                    (course.time.isNullOrBlank() || normalizeTime(it.optString("sksj")) == normalizeTime(course.time))
            }.singleOrNull()
        }
        if (course.useExactMatch) return if (course.hasTeachingClassFilter()) candidates.singleOrNull() else null
        if (!course.teacher.isNullOrBlank()) {
            candidates.firstOrNull { teacher(it).contains(course.teacher.trim(), ignoreCase = true) }
                ?.let { return it }
        }
        return candidates.firstOrNull()
    }

    @JvmStatic
    fun teacher(row: JSONObject): String = row.optString("jsxm").ifBlank {
        row.optString("jsxx").split(';').mapNotNull { it.split('/').getOrNull(1) }.joinToString("、")
    }

    private fun normalize(value: String) = CourseNameKit.normalizeBrackets(value).trim().lowercase(java.util.Locale.ROOT)
    private fun normalizeTime(value: String) = normalize(value).replace("星期", "周")
        .replace("礼拜", "周").replace("周天", "周日").replace(Regex("\\s+"), "")
}
