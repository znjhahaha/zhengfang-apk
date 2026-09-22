package com.tyust.course.academic.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyust.course.academic.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in device acceptance. Credentials live only in the debug app's private files and are deleted. */
@RunWith(AndroidJUnit4::class)
class PluginLiveSchoolDeviceTest {
    @Test fun suppliedSchoolsImportAndRunReadOnlyQueries() = runBlocking {
        val argument = InstrumentationRegistry.getArguments().getString("pluginLiveConfig")
        assumeTrue("No private live-test profile supplied", argument == "plugin-live/config.json")
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.filesDir, "plugin-live").apply { mkdirs() }
        val input = File(directory, "config.json")
        assumeTrue("No private live-test profile present", input.isFile)
        val profiles = JSONArray(input.readText())
        input.delete()
        val reports = JSONArray()
        val failures = mutableListOf<String>()
        fun save() { File(directory, "report.json").writeText(reports.toString(2)) }

        for (index in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(index)
            val slug = profile.getString("slug")
            require(slug.matches(Regex("[a-z0-9-]+")))
            val report = JSONObject().put("school", slug).put("readOnly", true)
            reports.put(report)
            val account = "dev:live:${UUID.randomUUID()}"
            var adapter: PluginAcademicAdapter? = null
            var school: com.tyust.course.model.SchoolConfig? = null
            suspend fun probe(name: String, block: suspend () -> Any) {
                try { report.put(name, JSONObject().put("status", "SUCCESS").put("result", withTimeout(60_000) { block() })) }
                catch (error: Exception) {
                    val status = (error as? AcademicException)?.status?.name ?: error.javaClass.simpleName
                    report.put(name, JSONObject().put("status", status))
                    if (status !in setOf("UNSUPPORTED", "ROUND_CLOSED", "NOT_OPEN")) failures += "$slug:$name:$status"
                }
                save()
                println("PLUGIN_LIVE $slug $name ${report.getJSONObject(name).getString("status")}")
            }
            try {
                val pkg = AcademicProviderRegistry.packages().install(File(directory, "$slug.eduplugin").readBytes(), true)
                AcademicProviderRegistry.reload()
                school = AcademicProviderRegistry.school(pkg)
                val current = AcademicGatewayFactory.create(school, account) as PluginAcademicAdapter
                adapter = current
                report.put("provider", pkg.manifest.id).put("version", current.version)
                var login = withTimeout(60_000) { current.login(Credentials(profile.getString("username"), profile.getString("password"))) }
                var attempts = 0
                while (login.status == AcademicStatus.CAPTCHA_REQUIRED && attempts < 2) {
                    val challenge = login.captcha ?: current.refreshCaptcha()
                    requireNotNull(challenge) { "No captcha image" }
                    File(directory, "$slug-captcha.png").writeBytes(challenge.image)
                    report.put("login", "CAPTCHA_REQUIRED"); save()
                    println("PLUGIN_LIVE $slug CAPTCHA_REQUIRED")
                    val answer = File(directory, "$slug-captcha.txt")
                    val code = withTimeout(180_000) {
                        while (!answer.isFile) delay(500)
                        answer.readText().trim().also { answer.delete() }
                    }
                    login = withTimeout(60_000) { current.submitCaptcha(code) }
                    attempts++
                }
                report.put("login", login.status.name).put("identityVerified", login.studentId.isNotBlank())
                save()
                println("PLUGIN_LIVE $slug LOGIN ${login.status.name}")
                if (login.status != AcademicStatus.SUCCESS) {
                    failures += "$slug:login:${login.status.name}"
                    continue
                }
                probe("session") { current.validateSession().status.name.also { require(it == "SUCCESS") } }
                var catalog: AcademicStudyCatalog? = null
                probe("terms") { current.catalog().also { catalog = it }.terms.size }
                catalog?.let { terms ->
                    probe("schedule") { current.schedule(terms.currentTerm).size }
                    probe("exams") { current.exams(terms.currentTerm).size }
                    probe("calendar") { current.calendar(terms.currentTerm) != null }
                }
                probe("grades") { current.grades(null).grades.size }
                probe("selectionCatalog") { current.loadCourseContext().scopes.size }
                probe("enrolled") { current.selected(current.loadCourseContext()).size }
            } catch (error: Exception) {
                val status = (error as? AcademicException)?.status?.name ?: error.javaClass.simpleName
                report.put("error", status)
                report.put("at", error.stackTrace.firstOrNull { it.className.startsWith("com.tyust.course.academic") }?.toString())
                failures += "$slug:$status"
                println("PLUGIN_LIVE $slug ERROR $status")
            } finally {
                adapter?.clearLoginState()
                school?.let { AcademicGatewayFactory.invalidate(it, account) }
                File(directory, "$slug-captcha.png").delete()
                File(directory, "$slug-captcha.txt").delete()
                save()
            }
        }
        assertTrue("Read-only acceptance failures: ${failures.joinToString()}", failures.isEmpty())
    }
}
