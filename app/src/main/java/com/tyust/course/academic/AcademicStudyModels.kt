package com.tyust.course.academic

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
    val sourceId: String = ""
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
    val seat: String = "", val examName: String = "", val teacher: String = ""
)

interface AcademicStudyAdapter {
    suspend fun catalog(): AcademicStudyCatalog
    suspend fun schedule(term: AcademicTerm): List<AcademicScheduleEntry>
    suspend fun grades(term: AcademicTerm? = null): AcademicGradeReport
    suspend fun exams(term: AcademicTerm): List<AcademicExam>
    suspend fun calendar(term: AcademicTerm): org.json.JSONObject? = null
    suspend fun gradeDetails(grade: AcademicGrade): String = grade.detail
}
