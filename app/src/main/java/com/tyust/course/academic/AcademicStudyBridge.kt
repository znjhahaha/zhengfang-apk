package com.tyust.course.academic

import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.screen.ExamItemUi
import com.tyust.course.ui.screen.GradeItemUi
import com.tyust.course.ui.screen.OverallStatsUi
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object AcademicStudyBridge {
    fun reader(school: SchoolConfig, account: String, expected: com.tyust.course.manager.SessionToken = UserManager.getInstance().sessionState.token): AcademicStudyAdapter {
        val user = UserManager.getInstance()
        return synchronized(user.sessionState) {
            if (!user.sessionState.isCurrent(expected) || expected.accountStorageKey != account || user.currentSchool?.id != school.id)
                throw kotlinx.coroutines.CancellationException("Session replaced")
            AcademicGatewayFactory.createStudy(school, account)
        }
    }

    fun grade(item: AcademicGrade) = GradeItemUi(
        courseName = item.name, grade = item.score, credits = item.credits, gpa = item.gradePoint,
        courseType = item.type, year = item.term.substringBefore('-'), term = item.term.substringAfterLast('-'),
        college = item.college, courseCode = item.code, jxbId = item.sectionId, detail = item.detail
    )

    fun exam(item: AcademicExam) = ExamItemUi(item.name, item.time, item.location, item.seat, item.examName, item.teacher)

    fun semesters(grades: List<AcademicGrade>): List<String> = grades.mapNotNull { it.term.takeIf(String::isNotBlank) }
        .distinct().sortedDescending()

    fun stats(report: AcademicGradeReport): OverallStatsUi {
        val graded = report.grades.mapNotNull { item ->
            val credit = item.credits.toDoubleOrNull() ?: return@mapNotNull null
            val point = item.gradePoint.toDoubleOrNull() ?: return@mapNotNull null
            credit to point
        }
        val weightedCredits = graded.sumOf { it.first }
        val gpa = report.gradePointAverage.ifBlank {
            if (weightedCredits > 0) String.format(Locale.ROOT, "%.2f", graded.sumOf { it.first * it.second } / weightedCredits) else "--"
        }
        val credits = report.totalCredits.ifBlank {
            val passed = report.grades.filter { (score(it.score) ?: -1.0) >= 60.0 }.mapNotNull { it.credits.toDoubleOrNull() }
            if (passed.isEmpty()) "--" else String.format(Locale.ROOT, "%.1f", passed.sum())
        }
        val scores = report.grades.mapNotNull { score(it.score) }
        return OverallStatsUi(gpa, credits, report.grades.size,
            scores.count { it >= 90 }, scores.count { it >= 80 && it < 90 },
            scores.count { it >= 70 && it < 80 }, scores.count { it >= 60 && it < 70 })
    }

    internal fun score(value: String): Double? = value.trim().toDoubleOrNull() ?: when (value.trim()) {
        "优秀", "优" -> 95.0; "良好", "良" -> 85.0; "中等", "中" -> 75.0
        "及格", "合格", "通过" -> 60.0; "不及格", "不合格", "未通过" -> 0.0; else -> null
    }

    fun scheduleJson(courses: List<AcademicScheduleEntry>): String = JSONObject().put("kbList", JSONArray().apply {
        courses.forEach { item -> put(JSONObject().apply {
            put("kcmc", item.name); put("xm", item.teacher); put("cdmc", item.location)
            put("xqj", item.day); put("jcs", "${item.startPeriod}-${item.endPeriod}"); put("zcd", item.weeks)
            put("schedule_source_id", item.sourceId)
        }) }
    }).toString()
}
