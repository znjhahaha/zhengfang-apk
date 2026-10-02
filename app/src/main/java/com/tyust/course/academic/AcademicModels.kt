package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig

enum class AcademicSystem(val id: String, val serial: Boolean) {
    ZF("zf", false),
    ZF_OLD("zf_old", true),
    QZ("qz", true),
    QZ_OLD("qz_old", true),
    JINZHI("jinzhi", true),
    CHENGFANG("chengfang", true),
    AUTO("auto", true), LEGACY_ZF("legacy_zf", false);

    companion object { fun fromId(id: String?): AcademicSystem? = values().firstOrNull { it.id == id } }
}

class Credentials(val username: String, val password: String) {
    override fun toString() = "Credentials(redacted)"
}

enum class AcademicStatus {
    SUCCESS, CAPTCHA_REQUIRED, HUMAN_VERIFICATION_REQUIRED, INVALID_CREDENTIALS,
    SESSION_EXPIRED, ROUND_CLOSED, NO_CAPACITY, CONFLICT, ALREADY_SELECTED,
    PAGE_CHANGED, NETWORK_RETRYABLE, RESULT_UNKNOWN, UNSUPPORTED, UNTRUSTED_URL,
    VALIDATION_FAILED, CREDIT_LIMIT
}

data class LoginResult(
    val status: AcademicStatus,
    val studentName: String = "",
    val studentId: String = "",
    val message: String = "",
    val captcha: CaptchaChallenge? = null
)

data class CaptchaChallenge(val image: ByteArray, val fieldName: String, val refreshUrl: String?)

data class CourseQuery(
    val keyword: String = "",
    val teacher: String = "",
    val start: Int = 0,
    val pageSize: Int = 50,
    val scopeId: String = "",
    val filters: CourseFilterValues? = null
)

/** Cursor is opaque to callers; null means the provider has finished this query. */
data class AcademicPage<T>(val items: List<T>, val nextCursor: String? = null)

data class CourseFilterOption(val value: String, val label: String)
data class CourseFilterGroup(val id: String, val label: String, val kind: String, val options: List<CourseFilterOption>)
data class CourseFilters(val roundId: String, val revision: String, val groups: List<CourseFilterGroup>)
data class CourseFilterValues(val revision: String, val values: Map<String, List<String>>) {
    fun toJson() = org.json.JSONObject().put("revision", revision).put("values",
        org.json.JSONObject().apply { values.forEach { (key, value) -> put(key, org.json.JSONArray(value)) } })
}

data class CourseContext(
    val sessionEpoch: Long,
    val scopes: List<CourseScope>,
    val createdAtMillis: Long = System.currentTimeMillis()
)

data class CourseScope(
    val id: String,
    val name: String,
    val term: String = "",
    val listUrl: String = "",
    val selectUrl: String = "",
    val params: Map<String, String> = emptyMap(),
    val requiresHumanVerification: Boolean = false
)

data class CourseOffer(
    val stableId: String,
    val name: String,
    val teacher: String = "",
    val time: String = "",
    val location: String = "",
    val credit: String = "",
    val capacity: Int? = null,
    val selected: Int? = null,
    val scopeId: String,
    val raw: Map<String, String> = emptyMap(),
    val publicIdentity: AcademicCourseIdentity? = null,
    val sectionCount: Int? = null
) { val identity: AcademicCourseIdentity get() = publicIdentity ?: BuiltinCourseData.identity(stableId, raw) }

data class CourseSection(
    val stableId: String,
    val courseStableId: String,
    val name: String = "",
    val teacher: String = "",
    val time: String = "",
    val location: String = "",
    val capacity: Int? = null,
    val selected: Int? = null,
    val raw: Map<String, String> = emptyMap(),
    /** A request token (e.g. do_jxb_id) can change while the teaching class stays the same. */
    val selectionId: String = stableId,
    val identityKnown: Boolean? = null
) { val knownIdentity: Boolean get() = identityKnown ?: if (raw.containsKey("do_jxb_id")) raw["jxb_id"] == stableId else stableId.isNotBlank() }

data class SelectionTarget(
    val course: CourseOffer,
    val section: CourseSection,
    val confirmed: Boolean = false
)

data class SelectedCourse(
    val stableId: String,
    val name: String,
    val teacher: String = "",
    val courseId: String = "",
    val sectionId: String = "",
    val raw: Map<String, String> = emptyMap(),
    val publicPresentation: AcademicEnrollmentPresentation? = null
) { val presentation: AcademicEnrollmentPresentation get() = publicPresentation ?: BuiltinCourseData.enrollment(raw) }

data class AcademicCourseIdentity(val courseId: String, val sectionId: String = "", val selectionId: String = sectionId,
    val sectionName: String = "", val sectionKnown: Boolean = false, val selected: Boolean = false, val flexibleSection: Boolean = false)
data class AcademicEnrollmentPresentation(val time: String = "", val location: String = "", val credit: String = "", val sectionName: String = "", val selectionId: String = "")

data class OperationResult(val status: AcademicStatus, val message: String = "", val confirmed: Boolean = false)
data class SelectionResult(val status: AcademicStatus, val message: String = "", val selected: SelectedCourse? = null)

data class AcademicResponse(val code: Int, val url: String, val text: String, val headers: Map<String, List<String>> = emptyMap())

class AcademicException(val status: AcademicStatus, message: String, cause: Throwable? = null) : Exception(message, cause)

data class AcademicSessionKey(val schoolId: String, val accountKey: String)

interface AcademicProtocolAdapter {
    val hasCourseFilters: Boolean get() = false
    suspend fun courseFilters(context: CourseContext, roundId: String): CourseFilters? = null
    fun restoreOffer(snapshot: Map<String, String>): CourseOffer? = null
    suspend fun login(credentials: Credentials): LoginResult
    suspend fun validateSession(): LoginResult
    suspend fun loadCourseContext(): CourseContext
    suspend fun listCourses(context: CourseContext, query: CourseQuery): List<CourseOffer>
    suspend fun coursePage(context: CourseContext, query: CourseQuery, cursor: String? = null): AcademicPage<CourseOffer> {
        val start = cursor?.toIntOrNull() ?: 0
        require(start >= 0)
        val rows = listCourses(context, query.copy(start = start))
        return AcademicPage(rows, if (rows.size >= query.pageSize) (start + rows.size).toString() else null)
    }
    suspend fun listSections(course: CourseOffer): List<CourseSection>
    suspend fun sectionPage(course: CourseOffer, cursor: String? = null): AcademicPage<CourseSection> {
        require(cursor == null)
        return AcademicPage(listSections(course))
    }
    suspend fun select(target: SelectionTarget): SelectionResult
    suspend fun selected(context: CourseContext): List<SelectedCourse>
    suspend fun drop(target: SelectionTarget): OperationResult
}

internal fun SchoolConfig.academicType(): AcademicSystem = AcademicSystem.fromId(academicSystem)
    ?: throw AcademicException(AcademicStatus.UNSUPPORTED, "未知教务类型，请重新选择")
