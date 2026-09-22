package com.tyust.course.academic

import java.util.UUID

/** Owns native tokens and protocol fields; public models carry only opaque snapshot references. */
internal class BuiltinAcademicProvider(private val native: AcademicProtocolAdapter, private val session: AcademicSession,
    private val system: AcademicSystem) : AcademicProtocolAdapter, AcademicCaptchaLogin, SessionBackedAdapter {
    private val contexts = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<CourseContext, CourseContext>())
    private fun save(value: Any): Map<String, String> = synchronized(snapshots) {
        val records = snapshots.getOrPut(session) { linkedMapOf() }
        val id = UUID.randomUUID().toString()
        records[id] = value
        while (records.size > 4096) records.remove(records.keys.first())
        mapOf("academic_source" to id, "academic_system" to system.id)
    }
    private fun source(raw: Map<String, String>): Any? = synchronized(snapshots) { snapshots[session]?.get(raw["academic_source"]) }
    private fun offer(value: CourseOffer) = value.copy(raw = save(value), publicIdentity = value.identity.copy(selectionId = value.identity.sectionId))
    private fun section(value: CourseSection) = value.copy(raw = save(value), selectionId = value.stableId,
        identityKnown = if (system == AcademicSystem.ZF) value.raw["jxb_id"] == value.stableId else value.stableId.isNotBlank())
    override fun restoreOffer(snapshot: Map<String, String>): CourseOffer? = (source(snapshot) as? CourseOffer)?.let(::offer)
    suspend fun <T> inSession(block: suspend () -> T): T = if (system.serial) session.withProtocolLock(block) else block()
    override suspend fun login(credentials: Credentials) = native.login(credentials)
    override suspend fun validateSession() = native.validateSession()
    override suspend fun submitCaptcha(code: String) = (native as? AcademicCaptchaLogin)?.submitCaptcha(code) ?: stale()
    override suspend fun refreshCaptcha() = (native as? AcademicCaptchaLogin)?.refreshCaptcha()
    override fun clearLoginState() { (native as? AcademicCaptchaLogin)?.clearLoginState() }
    override fun cookieHeader() = (native as? SessionBackedAdapter)?.cookieHeader().orEmpty()
    override suspend fun loadCourseContext(): CourseContext {
        val value = native.loadCourseContext()
        val neutral = value.copy(scopes = value.scopes.map { CourseScope(it.id, it.name, it.term, requiresHumanVerification = it.requiresHumanVerification) })
        contexts[neutral] = value
        return neutral
    }
    override suspend fun listCourses(context: CourseContext, query: CourseQuery) = native.listCourses(contexts[context] ?: stale(), query).map(::offer)
    override suspend fun listSections(course: CourseOffer) = native.listSections(source(course.raw) as? CourseOffer ?: stale()).map(::section)
    override suspend fun selected(context: CourseContext) = native.selected(contexts[context] ?: stale()).map { it.copy(raw = save(it), publicPresentation = it.presentation.copy(selectionId = it.sectionId)) }
    override suspend fun select(target: SelectionTarget) = native.select(SelectionTarget(source(target.course.raw) as? CourseOffer ?: stale(), source(target.section.raw) as? CourseSection ?: stale(), target.confirmed))
    override suspend fun drop(target: SelectionTarget): OperationResult {
        val record = source(target.course.raw) as? SelectedCourse ?: stale()
        return native.drop(SelectionTarget(CourseOffer(record.courseId, record.name, record.teacher, scopeId = "", raw = record.raw),
            CourseSection(record.sectionId.ifBlank { record.stableId }, record.courseId, raw = record.raw,
                selectionId = record.presentation.selectionId.ifBlank { record.sectionId.ifBlank { record.stableId } }), target.confirmed))
    }
    private fun stale(): Nothing = throw AcademicException(AcademicStatus.PAGE_CHANGED, "课程数据已过期，请刷新后重试")
    companion object { private val snapshots = java.util.WeakHashMap<AcademicSession, LinkedHashMap<String, Any>>() }
}
