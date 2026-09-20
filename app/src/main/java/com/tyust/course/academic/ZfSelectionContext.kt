package com.tyust.course.academic

import com.tyust.course.model.Course
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import org.jsoup.Jsoup

/** Refresh ambiguous pre-v2 queues by their own category before using a saved teaching-class ID. */
object ZfSelectionContext {
    @JvmStatic @JvmOverloads
    fun refreshLegacy(school: SchoolConfig, account: String, course: Course, indexHtml: String? = null): Map<String, String>? {
        val api = CourseApiClient.getInstance()
        val html = indexHtml ?: api.fetchPageHiddenParamsSync(school, account) ?: return null
        if (html.isBlank() || AcademicHtml.isLoginPage(html)) return null
        val index = AcademicHtml.hiddenFields(Jsoup.parse(html))
        val category = ZfCourseCategories.forSavedCategory(html, index, course.kklxdm.orEmpty()) ?: return null
        val display = api.runWithAccount(account) {
            api.fetchCourseDisplayParamsSyncWithKey(school, category.control.primaryValue, category.category,
                category.grade, category.major, category.control.primaryKey)
        } ?: return null
        if (display.isBlank() || AcademicHtml.isLoginPage(display)) return null
        return restore(course, category, index, AcademicHtml.hiddenFields(Jsoup.parse(display)))
    }

    internal fun restore(course: Course, category: ZfCourseCategory, index: Map<String, String>, display: Map<String, String>): Map<String, String>? {
        if (course.kklxdm != category.category) return null
        val merged = category.merge(index, display)
        val control = ZfSelectionControl.from(merged)
        if (control.isEmpty) return null
        course.completeParams.clear()
        course.completeParams.putAll(merged)
        control.applyTo(course)
        course.njdm_id = category.grade
        course.zyh_id = category.major
        course._xklc = merged["xklc"].orEmpty()
        course._rwlx = merged["rwlx"].orEmpty()
        return merged
    }
}
