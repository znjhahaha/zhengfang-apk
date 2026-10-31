package com.tyust.course.academic

/** Content-derived protocol UI ids must never become backend association ids. */
internal fun examSourceIdentity(id: String, name: String, time: String, examName: String): String {
    if (id.startsWith("content:")) return ""
    // Recognize exact pre-fix bundled rowKey values when restoring saved overrides.
    return id.takeUnless { it == legacyExamKey(listOf(name, time)) || it == legacyExamKey(listOf(name, time, examName)) }.orEmpty()
}

private fun legacyExamKey(parts: List<String>): String {
    var hash = 2166136261L.toInt()
    val text = parts.joinToString("\u0000")
    var index = 0
    while (index < text.length) {
        hash = (hash xor text[index].code) * 16777619
        index += if (Character.isHighSurrogate(text[index]) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) 2 else 1
    }
    return Integer.toUnsignedString(hash, 16) + ":" + parts.filter(String::isNotEmpty).joinToString("|").take(96)
}

/** Recover original content only when the saved suffix verifies its complete old FNV key. */
internal fun legacyExamSourceParts(id: String): List<String>? {
    val separator = id.indexOf(':')
    if (separator < 1 || !id.substring(0, separator).matches(Regex("[0-9a-f]{1,8}"))) return null
    val parts = id.substring(separator + 1).split('|')
    if (parts.size !in 2..3 || parts.any(String::isBlank)) return null
    val candidates = if (parts.size == 2) listOf(parts, parts + "") else listOf(parts)
    return candidates.singleOrNull { legacyExamKey(it) == id }
}

data class AcademicTerm(
    val id: String, val name: String = id,
    val year: Int = id.takeIf { STANDARD.matches(it) }?.substringBefore('-')?.toIntOrNull() ?: 0,
    val semester: Int = id.takeIf { STANDARD.matches(it) }?.substringAfterLast('-')?.toIntOrNull() ?: 0,
    val order: Int? = null, val startDate: String? = null, val endDate: String? = null,
    val nextId: String? = null
) {
    fun next(): AcademicTerm = nextId?.let { AcademicTerm(it) }
        ?: if (STANDARD.matches(id)) {
            if (semester == 1) AcademicTerm("$year-${year + 1}-2") else AcademicTerm("${year + 1}-${year + 2}-1")
        } else throw AcademicException(AcademicStatus.UNSUPPORTED, "学校尚未提供下一学期")
    fun toJson() = org.json.JSONObject().put("id", id).put("name", name).put("year", year).put("semester", semester)
        .put("order", order).put("startDate", startDate).put("endDate", endDate).put("nextId", nextId)
    companion object {
        private val STANDARD = Regex("[0-9]{4}-[0-9]{4}-[12]")
        fun fromJson(json: org.json.JSONObject) = AcademicTerm(json.getString("id"), json.optString("name", json.getString("id")),
            json.optInt("year", 0), json.optInt("semester", 0), if (json.has("order")) json.getInt("order") else null,
            json.optString("startDate").takeIf(String::isNotBlank), json.optString("endDate").takeIf(String::isNotBlank),
            json.optString("nextId").takeIf(String::isNotBlank))
    }
}

data class AcademicStudyCatalog(val terms: List<AcademicTerm>, val currentTerm: AcademicTerm)

data class AcademicScheduleEntry(
    val name: String, val teacher: String, val location: String,
    val day: Int, val startPeriod: Int, val endPeriod: Int, val weeks: String,
    val sourceId: String = "",
    val details: List<com.tyust.course.schedule.ScheduleDetail> = emptyList()
)

data class AcademicGrade(
    val name: String, val score: String, val credits: String, val gradePoint: String,
    val type: String = "", val term: String = "", val code: String = "",
    val college: String = "", val sectionId: String = "", val detail: String = "", val id: String = ""
)

data class AcademicGradeReport(
    val grades: List<AcademicGrade>, val gradePointAverage: String = "", val totalCredits: String = ""
)

data class AcademicExam(
    val name: String, val time: String, val location: String,
    val seat: String = "", val examName: String = "", val teacher: String = "", val sourceId: String = ""
)

interface AcademicStudyAdapter {
    suspend fun catalog(): AcademicStudyCatalog
    suspend fun schedule(term: AcademicTerm): List<AcademicScheduleEntry>
    suspend fun grades(term: AcademicTerm? = null): AcademicGradeReport
    suspend fun exams(term: AcademicTerm): List<AcademicExam>
    suspend fun calendar(term: AcademicTerm): org.json.JSONObject? = null
    suspend fun gradeDetails(grade: AcademicGrade): String = grade.detail
}
