package com.tyust.course.academic

/** One provider call at a time, even when the UI requests all open scopes. */
internal data class CoursePosition(val scope: Int = 0, val cursor: String? = null)
internal data class ScopedCoursePage(val items: List<CourseOffer>, val next: CoursePosition?)

internal suspend fun AcademicProtocolAdapter.scopedCoursePage(
    context: CourseContext, query: CourseQuery, position: CoursePosition = CoursePosition()
): ScopedCoursePage {
    val scopes = context.scopes.filter { query.scopeId.isBlank() || it.id == query.scopeId }
    if (scopes.isEmpty()) return ScopedCoursePage(emptyList(), null)
    val scope = scopes.getOrNull(position.scope) ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "选课分类已变化，请刷新")
    val page = coursePage(context, query.copy(scopeId = scope.id), position.cursor)
    val next = page.nextCursor?.let { CoursePosition(position.scope, it) }
        ?: if (position.scope + 1 < scopes.size) CoursePosition(position.scope + 1) else null
    return ScopedCoursePage(page.items.filter { query.scopeId.isBlank() || it.scopeId == scope.id }, next)
}

/** Empty filtered pages are valid; repeated nonempty pages/cursors are not. */
internal class CoursePageHistory {
    private val positions = mutableSetOf<CoursePosition>()
    private val identities = mutableSetOf<Triple<String, String, String>>()
    fun accept(position: CoursePosition, page: ScopedCoursePage): List<CourseOffer> {
        if (position in positions || page.next == position || page.next in positions)
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "插件分页游标重复，请刷新课程")
        val fresh = page.items.distinctBy { Triple(it.scopeId, it.stableId, it.identity.sectionId) }
            .filter { Triple(it.scopeId, it.stableId, it.identity.sectionId) !in identities }
        if (page.items.isNotEmpty() && fresh.isEmpty())
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校重复返回同一页课程，请刷新")
        if (identities.size + fresh.size > 10000 || positions.size >= 100)
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "课程分页达到上限，请缩小查询范围")
        positions += position
        fresh.forEach { identities += Triple(it.scopeId, it.stableId, it.identity.sectionId) }
        return fresh
    }
}
