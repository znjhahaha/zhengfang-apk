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
                    time = selected.raw["sksj"] ?: selected.raw["sksjmc"].orEmpty()
                    location = selected.raw["skdd"] ?: selected.raw["jxdd"].orEmpty()
                    credit = selected.raw["xf"].orEmpty()
                    jxbmc = selected.raw["jxbmc"].orEmpty()
                    courseId = selected.courseId
                    classId = selected.sectionId.ifBlank { selected.stableId }
                    doJxbId = selected.sectionId
                    isSelected = true
                    completeParams = selected.raw.toMutableMap()
                    completeParams["academic_system"] = school.academicSystem
                    completeParams["academic_selected_id"] = selected.stableId
                }
            }
        }
    }

    suspend fun listSections(school: SchoolConfig, accountStorageKey: String, course: Course, expected: SessionToken = UserManager.getInstance().sessionState.token): List<Course> {
        val adapter = prepareSession(school, accountStorageKey, expected)
        if (school.academicType() in setOf(AcademicSystem.QZ, AcademicSystem.QZ_OLD)) return listOf(course)
        return adapter.inSession {
            // 课程行自带列表页快照的完整协议参数（kklxdm/xkkz_id/xklc 等），
            // 直接请求教学班即可。原先每次点击都要重走"入口页+Display+搜索定位"，
            // 对教学班粒度的列表（如河北传媒学院）一组几十行会串行几十轮请求，
            // 慢到像一直加载。
            val raw = course.completeParams
            val directOffer = raw.takeIf { it.containsKey("kklxdm") && !ZfSelectionControl.from(it).isEmpty }?.let {
                CourseOffer(
                    it["academic_course_id"].orEmpty().ifBlank { course.courseId },
                    course.name, course.teacher, course.time, course.location, course.credit,
                    scopeId = it["academic_scope_id"].orEmpty(), raw = it)
            }
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
            val section = CourseSection(enrolled.sectionId.ifBlank { enrolled.stableId }, enrolled.courseId, raw = enrolled.raw,
                selectionId = if (school.academicType() == AcademicSystem.ZF)
                    enrolled.raw["do_jxb_id"].orEmpty().ifBlank { enrolled.sectionId.ifBlank { enrolled.stableId } }
                else enrolled.sectionId.ifBlank { enrolled.stableId })
            prepareSession(school, accountStorageKey, expected)
            adapter.drop(SelectionTarget(offer, section, confirmed = true))
        }
    }
    private suspend fun findOffer(adapter: AcademicProtocolAdapter, context: CourseContext, course: Course): CourseOffer? {
        // Teacher labels can contain several names. Verify writes by the fresh stable IDs.
        val query = CourseQuery(course.name, scopeId = course.completeParams["academic_scope_id"].orEmpty())
        return adapter.listCourses(context, query).firstOrNull { offer ->
            val sameCourse = offer.stableId == course.courseId || offer.stableId == course.completeParams["academic_course_id"]
            val rowSection = offer.raw["jx0404id"].orEmpty().ifBlank { offer.raw["sectionId"].orEmpty() }
            val samePopup = !offer.raw["popupUrl"].isNullOrBlank() && offer.raw["popupUrl"] == course.completeParams["popupUrl"]
            sameCourse && (rowSection.isBlank() || course.classId.isBlank() || rowSection == course.classId || samePopup)
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
        courseId = offer.raw["kch_id"] ?: offer.raw["kcid"] ?: offer.stableId
        classId = section?.stableId ?: sequenceOf("jxb_id", "jx0404id", "sectionId", "do_jxb_id")
            .mapNotNull { offer.raw[it]?.takeIf(String::isNotBlank) }.firstOrNull().orEmpty()
        doJxbId = section?.selectionId ?: offer.raw["do_jxb_id"]?.takeIf(String::isNotBlank) ?: classId
        teacher = section?.teacher?.ifBlank { offer.teacher } ?: offer.teacher
        jxbmc = section?.name.orEmpty().ifBlank { offer.raw["jxbmc"].orEmpty() }
        time = section?.time?.ifBlank { offer.time } ?: offer.time
        location = section?.location?.ifBlank { offer.location } ?: offer.location
        credit = offer.credit
        capacity = section?.capacity ?: offer.capacity ?: 0
        selected = section?.selected ?: offer.selected ?: 0
        isSelected = offer.raw["isSelected"] == "true" || offer.raw["sfxz"] == "1"
        completeParams = offer.raw.toMutableMap().apply {
            if (section != null) putAll(section.raw)
            put("academic_stable_section", (classId.isNotBlank() &&
                (offer.raw["academic_system"] != "zf" || get("jxb_id") == classId)).toString())
            put("academic_system", offer.raw["academic_system"].orEmpty())
            put("academic_scope_id", offer.scopeId)
            put("academic_course_id", offer.stableId)
            put("academic_capacity_known", ((section?.capacity ?: offer.capacity) != null).toString())
            put("academic_selected_known", ((section?.selected ?: offer.selected) != null).toString())
        }
    }
}
