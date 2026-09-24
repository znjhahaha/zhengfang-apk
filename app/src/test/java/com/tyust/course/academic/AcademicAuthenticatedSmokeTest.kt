package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Uses a manually authenticated session; this test never selects or drops a course. */
class AcademicAuthenticatedSmokeTest {
    @Test fun verifyImportedSessionReadQueries() = runBlocking {
        val path = System.getenv("ACADEMIC_COOKIE_SMOKE_FILE")
        assumeTrue("No authenticated test session supplied", !path.isNullOrBlank())
        val profiles = JSONArray(File(path!!).readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        val failures = mutableListOf<String>()
        val reports = JSONArray()
        for (i in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(i)
            val school = SchoolConfig(profile.getString("id"), profile.optString("name"), profile.getString("domain"), profile.getString("protocol")).apply {
                academicSystem = profile.getString("system")
                basePath = profile.getString("basePath")
            }
            val username = profile.getString("username")
            val account = AcademicGatewayFactory.accountKey(school, username)
            AcademicGatewayFactory.importCookie(school, account, profile.getString("cookie"), username = username)
            val adapter = AcademicGatewayFactory.create(school, account)
            val study = AcademicGatewayFactory.createStudy(school, account)
            val report = JSONObject().put("system", school.academicSystem)
            // Optional, independently documented capability boundary. It cannot waive login,
            // networking or parser failures, and remains visible as UNSUPPORTED in the report.
            val unavailable = profile.optJSONObject("expectedUnavailable") ?: JSONObject()
            require(unavailable.keys().asSequence().all { it == "selected" && unavailable.getString(it) == "UNSUPPORTED" })
            if (unavailable.length() > 0) report.put("expectedUnavailable", unavailable)
            // One read transaction shares the same round context, as the App does.
            // Re-entering a school's selection landing page can replace its active round session.
            var contextResult: Result<CourseContext>? = null
            suspend fun context(): CourseContext = (contextResult ?: runCatching { adapter.loadCourseContext() }
                .also { contextResult = it }).getOrThrow()
            suspend fun probe(name: String, read: suspend () -> Any) {
                try {
                    report.put(name, read())
                    if (unavailable.has(name)) failures += "${school.academicSystem}:$name:CAPABILITY_CHANGED"
                }
                catch (e: Exception) {
                    val status = (e as? AcademicException)?.status?.name ?: e.javaClass.simpleName
                    report.put(name, status)
                    report.put(name + "Message", e.message.orEmpty().take(200))
                    report.put(name + "At", e.stackTrace.firstOrNull { it.className.startsWith("com.tyust.course.academic") }?.toString())
                    if (unavailable.optString(name) != status) failures += "${school.academicSystem}:$name:$status"
                }
            }
            probe("identity") {
                val result = adapter.validateSession()
                if (result.status != AcademicStatus.SUCCESS) throw AcademicException(result.status, "Imported login was not accepted")
                result.status.name
            }
            probe("courses") {
                val context = context()
                report.put("scopes", context.scopes.size)
                val courses = adapter.listCourses(context, CourseQuery(pageSize = 100))
                report.put("sections", courses.firstOrNull()?.let { adapter.listSections(it).size } ?: 0)
                courses.size
            }
            probe("selected") { adapter.selected(context()).size }
            probe("schedule") {
                val catalog = study.catalog()
                report.put("currentTerm", catalog.currentTerm.id)
                study.schedule(catalog.currentTerm).size
            }
            probe("grades") {
                val grades = study.grades(null).grades
                report.put("gradedTerms", grades.map { it.term }.distinct().size)
                grades.size
            }
            probe("exams") { study.exams(study.catalog().currentTerm).size }
            reports.put(report)
            println(report.toString())
        }
        System.getenv("ACADEMIC_COOKIE_SMOKE_REPORT")?.let { File(it).writeText(reports.toString(2)) }
        assertEquals("Read-only query failures", emptyList<String>(), failures)
    }
}
