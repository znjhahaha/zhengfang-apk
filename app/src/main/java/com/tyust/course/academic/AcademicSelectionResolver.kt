package com.tyust.course.academic

import com.tyust.course.utils.CourseNameKit
import org.jsoup.Jsoup

internal data class ResolvedAcademicSelection(val course: CourseOffer, val section: CourseSection)

internal suspend fun AcademicProtocolAdapter.resolveSelection(
    context: CourseContext, courseId: String, sectionId: String, courseName: String,
    teacher: String = "", time: String = "", scopeId: String = "", sectionName: String = "",
    allowLegacyRebind: Boolean = false
): ResolvedAcademicSelection? {
    val candidates = resolveCandidates(context, courseId, sectionId, courseName, teacher, time, scopeId,
        sectionName, allowLegacyRebind)
    if (candidates.size > 1) throw AcademicException(AcademicStatus.PAGE_CHANGED,
        "找到多个符合条件的教学班，请从课程列表重新指定目标班级")
    return candidates.singleOrNull()
}

internal suspend fun AcademicProtocolAdapter.resolveCandidates(
    context: CourseContext, courseId: String, sectionId: String, courseName: String,
    teacher: String = "", time: String = "", scopeId: String = "", sectionName: String = "",
    allowLegacyRebind: Boolean = false
): List<ResolvedAcademicSelection> {
    if (scopeId.isNotBlank() && context.scopes.none { it.id == scopeId })
        throw AcademicException(AcademicStatus.ROUND_CLOSED, "目标课程所在轮次尚未开放或已经结束")
    val offers = mutableListOf<CourseOffer>()
    val seen = mutableSetOf<List<String>>()
    val pageSize = 50
    for (page in 0 until 20) {
        val rows = listCourses(context, CourseQuery(courseName, start = page * pageSize,
            pageSize = pageSize, scopeId = scopeId))
        val fresh = rows.filter { seen.add(listOf(it.scopeId, it.stableId, it.identity.sectionId)) }
        offers += fresh.filter {
            (scopeId.isBlank() || it.scopeId == scopeId) &&
                if (courseId.isNotBlank()) it.stableId == courseId else normalized(it.name) == normalized(courseName)
        }
        // Older adapters may return the entire list and ignore pagination.
        if (rows.size < pageSize || fresh.isEmpty()) break
        if (page == 19) throw AcademicException(AcademicStatus.PAGE_CHANGED,
            "课程结果过多，请从课程列表指定课程和选课分类后重试")
    }
    if (offers.map { it.scopeId to it.stableId }.distinct().size > 1)
        throw AcademicException(AcademicStatus.PAGE_CHANGED, "存在同名课程，请从课程列表指定目标课程和轮次")
    val matches = mutableListOf<ResolvedAcademicSelection>()
    val legacy = mutableListOf<ResolvedAcademicSelection>()
    for (offer in offers) {
        val rowSection = offer.identity.sectionId
        if (sectionId.isNotBlank() && rowSection.isNotBlank() && rowSection != sectionId && !offer.identity.flexibleSection) continue
        for (section in listSections(offer)) {
            if (section.stableId.isBlank()) continue
            val exact = if (sectionId.isNotBlank()) section.stableId == sectionId || section.selectionId == sectionId
                else (teacher.isBlank() || normalized(section.teacher.ifBlank { offer.teacher }).contains(normalized(teacher))) &&
                    (time.isBlank() || normalized(section.time.ifBlank { offer.time }).contains(normalized(time))) &&
                    (sectionName.isBlank() || normalized(section.name).contains(normalized(sectionName)))
            val resolved = ResolvedAcademicSelection(offer, section)
            if (exact) matches += resolved
            // Rebind only legacy ZF tokens with independent, complete class identity evidence.
            if (allowLegacyRebind && sectionId.isNotBlank() && offer.raw["academic_system"] == "zf" &&
                sectionName.isNotBlank() && (teacher.isNotBlank() || time.isNotBlank()) &&
                normalized(section.name) == normalized(sectionName) &&
                (teacher.isBlank() || normalized(section.teacher.ifBlank { offer.teacher }) == normalized(teacher)) &&
                (time.isBlank() || normalized(section.time.ifBlank { offer.time }) == normalized(time))) legacy += resolved
        }
    }
    return matches.ifEmpty { legacy }.distinctBy { Triple(it.course.scopeId, it.course.stableId, it.section.stableId) }
}

private fun normalized(value: String): String = CourseNameKit.normalizeBrackets(Jsoup.parse(value).text())
    .replace("星期", "周").replace("礼拜", "周").replace("周天", "周日")
    .filterNot(Char::isWhitespace).lowercase(java.util.Locale.ROOT)
