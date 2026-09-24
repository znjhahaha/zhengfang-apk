package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Opt-in local diagnostic. The supplied file and its secrets must stay outside the repository. */
class AcademicLiveSmokeTest {
    @Test fun verifyReadOnlySchoolChains() = runBlocking {
        val path = System.getenv("ACADEMIC_SMOKE_FILE")
        assumeTrue("Live credentials were not supplied", !path.isNullOrBlank())
        val configs = JSONArray(File(path!!).readText(Charsets.UTF_8))
        val results = JSONArray()
        for (i in 0 until configs.length()) {
            val config = configs.getJSONObject(i)
            val report = JSONObject().put("system", config.getString("system")).put("host", config.getString("domain"))
            try {
                val school = SchoolConfig(config.getString("id"), config.optString("name"), config.getString("domain"), config.getString("protocol")).apply {
                    basePath = config.getString("basePath")
                    academicSystem = config.getString("system")
                }
                val adapter = AcademicGatewayFactory.create(school, AcademicGatewayFactory.accountKey(school, config.getString("username")))
                val login = adapter.login(Credentials(config.getString("username"), config.getString("password")))
                report.put("login", login.status.name)
                if (login.status == AcademicStatus.SUCCESS) {
                    report.put("identityVerified", login.studentId.isNotBlank())
                    val study = AcademicGatewayFactory.createStudy(school, AcademicGatewayFactory.accountKey(school, config.getString("username")))
                    val term = study.catalog().currentTerm
                    report.put("currentTerm", term.id)
                    report.put("schedule", study.schedule(term).size)
                    report.put("grades", study.grades(null).grades.size)
                    report.put("exams", study.exams(term).size)
                    val context = adapter.loadCourseContext()
                    report.put("scopes", context.scopes.size)
                    val courses = adapter.listCourses(context, CourseQuery(pageSize = 100))
                    report.put("courses", courses.size)
                    report.put("sections", courses.firstOrNull()?.let { adapter.listSections(it).size } ?: 0)
                    report.put("selected", adapter.selected(context).size)
                    report.put("readOnlyChain", "SUCCESS")
                }
            } catch (e: Exception) {
                report.put("error", (e as? AcademicException)?.status?.name ?: e.javaClass.simpleName)
                report.put("message", e.message.orEmpty().take(180))
            }
            results.put(report)
            println(report.toString())
        }
        System.getenv("ACADEMIC_SMOKE_REPORT")?.let { File(it).writeText(results.toString(2), Charsets.UTF_8) }
        for (i in 0 until results.length()) {
            val report = results.getJSONObject(i)
            if (report.getString("system") == "qz") assertEquals("SUCCESS", report.optString("readOnlyChain", report.optString("error")))
        }
    }
}
