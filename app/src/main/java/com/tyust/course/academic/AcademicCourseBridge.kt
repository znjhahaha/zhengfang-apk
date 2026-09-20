package com.tyust.course.academic

import com.tyust.course.manager.UserManager
import com.tyust.course.manager.SessionToken
import com.tyust.course.model.Course
import com.tyust.course.model.SchoolConfig

data class AcademicCoursePage(val context: CourseContext, val courses: List<Course>)

/** Converts protocol-neutral course objects to the model used by the existing Compose screens. */
object AcademicCourseBridge {
    internal fun mergeSections(existing: List<Course>, requested: Course, sections: List<Course>): List<Course> {
        if (sections.isEmpty()) return existing
        val scopeId = requested.completeParams["academic_scope_id"]
        val refreshedIds = sections.map { it.classId }.toSet()
        fun replaced(course: Course): Boolean = course.run {
            courseId == requested.courseId && completeParams["academic_scope_id"] == scopeId &&
                (classId == requested.classId || classId in refreshedIds)
        }
        val insertion = existing.indexOfFirst(::replaced).takeIf { it >= 0 } ?: existing.size
        return existing.take(insertion) + sections + existing.drop(insertion).filterNot(::replaced)
    }

    suspend fun listCourses(school: SchoolConfig, accountStorageKey: String, query: CourseQuery = CourseQuery(), expected: SessionToken = UserManager.getInstance().sessionState.token): AcademicCoursePage {
        val adapter = prepareSession(school, accountStorageKey, expected)
        return adapter.inSession {
            val context = adapter.loadCourseContext()
            if (query.scopeId.isNotBlank() && context.scopes.none { it.id == query.scopeId })
                throw AcademicException(AcademicStatus.ROUND_CLOSED, "该轮次已结束，请切换其他轮次")
            val offers = adapter.listCourses(context, query)
            AcademicCoursePage(context, offers.map { toCourse(it).apply { completeParams["academic_system"] = school.academicSystem } })
        }
    }

    suspend fun selectedCourses(school: SchoolConfig, accountStorageKey: String, expected: SessionToken = UserManager.getInstance().sessionState.token): List<Course> {
        val adapter = prepareSession(school, accountStorageKey, expected)
        return adapter.inSession {
            val context = adapter.loadCourseContext()
            adapter.selected(context).map { selected ->
                Course().apply {
                    name = selected.name
                    teacher = selected.teacher
                    time = selected.presentation.time
                    location = selected.presentation.location
                    credit = selected.presentation.credit
                    jxbmc = selected.presentation.sectionName
                    courseId = selected.courseId
                    classId = selected.sectionId.ifBlank { selected.stableId }
                    doJxbId = selected.sectionId
                    isSelected = true
                    completeParams = selected.raw.filterKeys { it.startsWith("academic_") }.toMutableMap()
                    completeParams["academic_system"] = school.academicSystem
                    completeParams["academic_selected_id"] = selected.stableId
                }
            }
        }
    }

    suspend fun listSections(school: SchoolConfig, accountStorageKey: String, course: Course, expected: SessionToken = UserManager.getInstance().sessionState.token): List<Course> {
        val adapter = prepareSession(school, accountStorageKey, expected)
        return adapter.inSession {
            // Reuse the provider-owned snapshot when available; stale references
            // fall back to a fresh lookup before selecting a teaching class.
            val directOffer = adapter.restoreOffer(course.completeParams)
            val offer = directOffer ?: run {
                val context = adapter.loadCourseContext()
                findOffer(adapter, context, course)
            } ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "课程已不在当前轮次，请刷新课程列表")
            adapter.listSections(offer).map { section -> toCourse(offer, section).apply { completeParams["academic_system"] = school.academicSystem } }
        }
    }

    suspend fun select(school: SchoolConfig, accountStorageKey: String, course: Course, expected: SessionToken = UserManager.getInstance().sessionState.token): SelectionResult {
        val adapter = prepareSession(school, accountStorageKey, expected)
        return adapter.inSession {
            val context = adapter.loadCourseContext()
            val resolved = adapter.resolveSelection(context,
                course.completeParams["academic_course_id"].orEmpty().ifBlank { course.courseId },
                course.classId.ifBlank { course.doJxbId }, course.name, course.teacher, course.time,
                course.completeParams["academic_scope_id"].orEmpty(), course.jxbmc,
                allowLegacyRebind = course.completeParams["academic_stable_section"] != "true")
                ?: return@inSession SelectionResult(AcademicStatus.PAGE_CHANGED, "未找到目标教学班，请刷新课程后重新确认")
            val (offer, section) = resolved
            prepareSession(school, accountStorageKey, expected)
            val result = try { adapter.select(SelectionTarget(offer, section, confirmed = true)) }
                catch (e: AcademicException) { SelectionResult(e.status, e.message.orEmpty()) }
            if (result.status == AcademicStatus.RESULT_UNKNOWN) {
                val enrolled = try { adapter.selected(context) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                if (enrolled?.any { it.sectionId == section.stableId || it.stableId == section.stableId } == true)
                    return@inSession SelectionResult(AcademicStatus.SUCCESS, "已通过已选课程确认选课成功")
            }
            result
        }
    }
    suspend fun drop(school: SchoolConfig, accountStorageKey: String, course: Course, expected: SessionToken = UserManager.getInstance().sessionState.token): OperationResult {
        val adapter = prepareSession(school, accountStorageKey, expected)
        return adapter.inSession {
            val context = adapter.loadCourseContext()
            val selectedId = course.completeParams["academic_selected_id"].orEmpty().ifBlank { course.classId }
            val enrolled = adapter.selected(context).firstOrNull { it.stableId == selectedId || it.sectionId == selectedId }
                ?: return@inSession OperationResult(AcademicStatus.PAGE_CHANGED, "课程已不在当前已选列表")
            val offer = CourseOffer(enrolled.courseId, enrolled.name, enrolled.teacher, scopeId = "",
                raw = enrolled.raw)
            val section = CourseSection(enrolled.sectionId.ifBlank { enrolled.stableId }, enrolled.courseId, raw = enrolled.raw)
            prepareSession(school, accountStorageKey, expected)
            adapter.drop(SelectionTarget(offer, section, confirmed = true))
        }
    }
    private suspend fun findOffer(adapter: AcademicProtocolAdapter, context: CourseContext, course: Course): CourseOffer? {
        // Teacher labels can contain several names. Verify writes by the fresh stable IDs.
        val query = CourseQuery(course.name, scopeId = course.completeParams["academic_scope_id"].orEmpty())
        return adapter.listCourses(context, query).firstOrNull { offer ->
            val sameCourse = offer.stableId == course.courseId || offer.stableId == course.completeParams["academic_course_id"]
            val rowSection = offer.identity.sectionId
            sameCourse && (rowSection.isBlank() || course.classId.isBlank() || rowSection == course.classId || offer.identity.flexibleSection)
        }
    }

    private fun prepareSession(school: SchoolConfig, accountStorageKey: String, expected: SessionToken): AcademicProtocolAdapter {
        val user = UserManager.getInstance()
        return synchronized(user.sessionState) {
            if (!user.sessionState.isCurrent(expected) || expected.accountStorageKey != accountStorageKey || user.currentSchool?.id != school.id)
                throw kotlinx.coroutines.CancellationException("Session replaced")
            AcademicGatewayFactory.create(school, accountStorageKey)
        }
    }

    internal fun toCourse(offer: CourseOffer, section: CourseSection? = null): Course = Course().apply {
        name = offer.name
        courseId = offer.identity.courseId
        classId = section?.stableId ?: offer.identity.sectionId
        doJxbId = section?.selectionId ?: offer.identity.selectionId
        teacher = section?.teacher?.ifBlank { offer.teacher } ?: offer.teacher
        jxbmc = section?.name.orEmpty().ifBlank { offer.identity.sectionName }
        time = section?.time?.ifBlank { offer.time } ?: offer.time
        location = section?.location?.ifBlank { offer.location } ?: offer.location
        credit = offer.credit
        capacity = section?.capacity ?: offer.capacity ?: 0
        selected = section?.selected ?: offer.selected ?: 0
        isSelected = offer.identity.selected
        completeParams = offer.raw.filterKeys { it.startsWith("academic_") }.toMutableMap().apply {
            put("academic_stable_section", (if (section != null) section.knownIdentity
                else offer.identity.sectionKnown).toString())
            put("academic_system", offer.raw["academic_system"].orEmpty())
            put("academic_scope_id", offer.scopeId)
            put("academic_course_id", offer.stableId)
            put("academic_capacity_known", ((section?.capacity ?: offer.capacity) != null).toString())
            put("academic_selected_known", ((section?.selected ?: offer.selected) != null).toString())
        }
    }
}
