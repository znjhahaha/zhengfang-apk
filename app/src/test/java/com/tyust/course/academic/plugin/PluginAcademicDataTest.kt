package com.tyust.course.academic.plugin

import android.app.Application
import android.content.Context
import com.tyust.course.academic.*
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.schedule.ScheduleRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class PluginAcademicDataTest {
    private lateinit var app: Application
    private lateinit var user: UserManager
    private val school = SchoolConfig("synthetic-study", "Synthetic", "school.test", "https").apply { academicSystem = "zf" }
    private val term = AcademicTerm("2026-2027-1")
    private val caller = PluginPackage(PluginManifest(JSONObject("""{"id":"test.study","name":"Study","kind":"native","version":"1.0.0","apiVersion":3,"permissions":[],"network":[],"capabilities":[],"contributes":{"pages":[],"entries":[]}}""")), "", "a".repeat(64), true)
    private var clock = 1_800_000_000_000L
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication(); user = UserManager.getInstance(); user.init(app)
        AcademicProviderRegistry.initialize(app)
        user.currentSchool = school; user.studentId = "synthetic-student"; user.saveCookieLogin("SYNTHETIC=fixture")
        ScheduleSettingsManager.getInstance().init(app)
        val settings = ScheduleSettingsManager.getInstance()
        settings.getCustomCourses(user.currentAccountStorageKey).forEach { settings.removeCustomCourse(it.id, user.currentAccountStorageKey) }
    }
    private fun entry(id: String = "imported", day: Int = 2) = JSONObject().put("id", id).put("name", "Synthetic course").put("teacher", "Fixture teacher")
        .put("location", "Fixture room").put("day", day).put("startPeriod", 1).put("endPeriod", 2).put("weeks", JSONArray(listOf(1,2)))
    private fun input() = JSONObject().put("source", "synthetic fixture").put("schedule", JSONObject().put("termId", term.id).put("maxWeeks", 25).put("entries", JSONArray().put(entry())))
    private fun data(active: () -> Boolean = { true }) = PluginAcademicData(app, caller, active, now = { clock })
    @Test fun confirmedImportIsIdempotentAndKeepsManualCourses() {
        val account = user.currentAccountStorageKey
        ScheduleSettingsManager.getInstance().addCustomCourse(ScheduleSettingsManager.CustomCourse("manual", "Manual", "", "", 5, 3, 4, "1-2周"), account)
        val data = data(); val preview = data.preview(input()); val id = preview.getString("previewId")
        assertEquals(1, preview.getInt("manualPreserved")); assertEquals(1, preview.getInt("added"))
        assertEquals(data.confirm(id).toString(), data.confirm(id).toString())
        val rows = ScheduleRepository(app).snapshot(account, school.id, term.id).courses
        assertEquals(2, rows.size); assertEquals(1, rows.count { it.custom })
        val repeated = data.preview(input()); assertEquals(0, repeated.getInt("added")); assertEquals(1, repeated.getInt("duplicates"))
    }
    @Test fun expiredOrAccountReplacedPreviewsCannotCommit() {
        val data = data(); val id = data.preview(input()).getString("previewId")
        clock += 1_800_001
        assertThrows(PluginException::class.java) { data.confirm(id) }
        val current = data(); val next = current.preview(input()).getString("previewId")
        user.sessionState.replace("another-synthetic-account")
        assertThrows(PluginException::class.java) { current.confirm(next) }
    }
    @Test fun concurrentCacheChangeRequiresANewPreview() {
        val data = data(); val id = data.preview(input()).getString("previewId")
        val json = AcademicStudyBridge.scheduleJson(listOf(AcademicScheduleEntry("Changed", "", "", 3, 1, 2, "1周", "changed")))
        app.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE).edit().putString("schedule_${user.currentAccountStorageKey}_${school.id}_${term.id}", json).commit()
        assertEquals(PluginErrorCode.CONFLICT, assertThrows(PluginException::class.java) { data.confirm(id) }.code)
    }
    @Test fun callerDigestAndActivityRevocationInvalidatePreview() {
        var active = true; val data = data { active }; val id = data.preview(input()).getString("previewId")
        active = false; assertThrows(PluginException::class.java) { data.describe(id) }
        val updated = PluginAcademicData(app, caller.copy(digest = "b".repeat(64)), { true }, now = { clock })
        assertThrows(PluginException::class.java) { updated.confirm(id) }
    }
    @Test fun refreshPopulatesCommonCacheAndKeepsImportedCourses() = runBlocking {
        val existing = data(); existing.confirm(existing.preview(input()).getString("previewId"))
        val reader = object : AcademicStudyAdapter {
            override suspend fun catalog() = AcademicStudyCatalog(listOf(term), term)
            override suspend fun schedule(term: AcademicTerm) = listOf(AcademicScheduleEntry("Remote fixture", "", "", 1, 3, 4, "1-2周", "remote"))
            override suspend fun grades(term: AcademicTerm?) = AcademicGradeReport(emptyList(), "", "")
            override suspend fun gradeDetails(grade: AcademicGrade) = ""
            override suspend fun exams(term: AcademicTerm) = emptyList<AcademicExam>()
        }
        val data = PluginAcademicData(app, caller, { true }, now = { clock }, readerFactory = { _, _, _ -> reader })
        val result = data.read(JSONObject().put("termId", term.id), true)
        assertEquals(2, result.getJSONObject("schedule").getJSONArray("entries").length())
        assertEquals(2, ScheduleRepository(app).snapshot(user.currentAccountStorageKey, school.id, term.id).courses.size)
    }
    @Test fun aResponseAfterAccountSwitchNeverReplacesCache() = runBlocking {
        val account = user.currentAccountStorageKey
        val reader = object : AcademicStudyAdapter {
            override suspend fun catalog() = AcademicStudyCatalog(listOf(term), term)
            override suspend fun schedule(term: AcademicTerm): List<AcademicScheduleEntry> { user.sessionState.replace("another-account"); return emptyList() }
            override suspend fun grades(term: AcademicTerm?) = AcademicGradeReport(emptyList(), "", "")
            override suspend fun gradeDetails(grade: AcademicGrade) = ""
            override suspend fun exams(term: AcademicTerm) = emptyList<AcademicExam>()
        }
        val data = PluginAcademicData(app, caller, { true }, readerFactory = { _, _, _ -> reader })
        try { data.read(JSONObject().put("termId", term.id), true); fail("Expected stale account") } catch (e: PluginException) { assertEquals(PluginErrorCode.STALE_CONTEXT, e.code) }
        assertFalse(app.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE).contains("schedule_${account}_${school.id}_${term.id}"))
    }
}
