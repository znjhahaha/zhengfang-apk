package com.tyust.course.study

import com.tyust.course.academic.*
import com.tyust.course.ui.screen.GradeItemUi
import com.tyust.course.ui.screen.OverallStatsUi
import org.json.JSONArray
import org.json.JSONObject

object StudyDataJson {
    fun validLegacyList(text: String): Boolean = runCatching {
        val rows = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text).getJSONArray("items")
        (0 until rows.length()).all { index ->
            val row = rows.getJSONObject(index)
            row.optString("kcmc", row.optString("KCMC")).isNotBlank()
        }
    }.getOrDefault(false)
    private fun objects(rows: JSONArray) = (0 until rows.length()).map { rows.getJSONObject(it) }
    fun catalog(value: AcademicStudyCatalog) = JSONObject().put("current", value.currentTerm.toJson())
        .put("terms", JSONArray().apply { value.terms.forEach { put(it.toJson()) } })
    fun catalog(json: JSONObject) = AcademicStudyCatalog(objects(json.getJSONArray("terms")).map(AcademicTerm::fromJson),
        AcademicTerm.fromJson(json.getJSONObject("current")))
    fun report(value: AcademicGradeReport) = JSONObject().put("gpa", value.gradePointAverage).put("credits", value.totalCredits)
        .put("grades", JSONArray().apply { value.grades.forEach { g -> put(JSONObject()
            .put("name", g.name).put("score", g.score).put("credits", g.credits).put("point", g.gradePoint)
            .put("type", g.type).put("term", g.term).put("code", g.code).put("college", g.college)
            .put("section", g.sectionId).put("detail", g.detail).put("id", g.id)) } })
    fun report(json: JSONObject) = AcademicGradeReport(objects(json.getJSONArray("grades")).map { g ->
        AcademicGrade(g.getString("name"), g.getString("score"), g.getString("credits"), g.getString("point"),
            g.optString("type"), g.optString("term"), g.optString("code"), g.optString("college"),
            g.optString("section"), g.optString("detail"), g.optString("id"))
    }, json.optString("gpa"), json.optString("credits"))

    fun uiGrades(value: List<GradeItemUi>) = JSONObject().put("grades", JSONArray().apply {
        value.forEach { g -> put(JSONObject().put("name", g.courseName).put("score", g.grade)
            .put("credits", g.credits).put("point", g.gpa).put("type", g.courseType).put("year", g.year)
            .put("term", g.term).put("college", g.college).put("code", g.courseCode)
            .put("class", g.teachingClass).put("section", g.jxbId).put("detail", g.detail)) }
    })
    fun uiGrades(json: JSONObject) = objects(json.getJSONArray("grades")).map { g -> GradeItemUi(
        g.getString("name"), g.getString("score"), g.getString("credits"), g.getString("point"),
        g.getString("type"), g.optString("year"), g.optString("term"), g.optString("college"),
        g.optString("code"), g.optString("class"), g.optString("section"), g.optString("detail")) }
    fun stats(value: OverallStatsUi) = JSONObject().put("gpa", value.gpa).put("credits", value.credits)
        .put("count", value.courseCount).put("excellent", value.excellent).put("good", value.good)
        .put("medium", value.medium).put("pass", value.pass)
    fun stats(json: JSONObject) = OverallStatsUi(json.getString("gpa"), json.getString("credits"), json.getInt("count"),
        json.getInt("excellent"), json.getInt("good"), json.getInt("medium"), json.getInt("pass"))
}
