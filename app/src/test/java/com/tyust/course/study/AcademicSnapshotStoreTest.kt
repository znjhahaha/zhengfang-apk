package com.tyust.course.study

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.tyust.course.manager.SessionStateStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AcademicSnapshotStoreTest {
    private val sessions = SessionStateStore()
    private lateinit var store: AcademicSnapshotStore
    private val scope = AcademicDataScope("a", "school", "https://school.test/jsxsd", "qz", "standard")
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("academic_snapshots", Context.MODE_PRIVATE).edit().clear().commit()
        store = AcademicSnapshotStore.from(context, sessions)
        sessions.replace("a")
    }

    @Test fun newStoreInstanceRestoresLatestPayloadAndValidEmptyResultReplacesIt() {
        assertTrue(store.save(scope, "grades", "term", JSONObject().put("rows", "old"), sessions.token))
        val next = AcademicSnapshotStore.from(ApplicationProvider.getApplicationContext(), sessions)
        assertEquals("old", next.read(scope, "grades", "term")!!.data.getString("rows"))
        assertTrue(next.save(scope, "grades", "term", JSONObject().put("rows", ""), sessions.token))
        assertEquals("", store.read(scope, "grades", "term")!!.data.getString("rows"))
    }

    @Test fun accountSchoolEndpointProviderAndTermAreIsolated() {
        store.save(scope, "grades", "term", JSONObject(), sessions.token)
        for (other in listOf(scope.copy(account = "b"), scope.copy(school = "other"),
            scope.copy(endpoint = "https://other.test"), scope.copy(provider = "other")))
            assertNull(store.read(other, "grades", "term"))
        assertNull(store.read(scope, "grades", "other-term"))
        assertNull(store.read(scope, "exams", "term"))
    }

    @Test fun unchangedProviderConfigurationChangesDetachEveryRetainedPageState() {
        val school = com.tyust.course.model.SchoolConfig("school", "School", "school.test", "https")
        val before = AcademicDataScope.forSchool("a", school, "installed-provider", "standard")
        val pages = com.tyust.course.ui.system.PageDataState()
        val keys = listOf("overall", "overall.loaded", "terms", "reports", "exams:term", "exams.loaded:term")
        val previous = keys.map { pages.state(before.pageCacheKey("same-package") + ":" + it) { "old" } }
        school.userAgent = "new configuration"
        val after = AcademicDataScope.forSchool("a", school, "installed-provider", "standard")
        assertNotEquals(before.pageCacheKey("same-package"), after.pageCacheKey("same-package"))
        val fresh = keys.map { pages.state(after.pageCacheKey("same-package") + ":" + it) { "empty" } }
        previous.forEach { it.value = "late detail" }
        fresh.forEach { assertEquals("empty", it.value) }
        store.save(before, "overall", "", JSONObject().put("value", "old"), sessions.token)
        assertNull(store.read(after, "overall"))
        assertEquals(before.manualScope(), after.manualScope())
        assertNotEquals(after.pageCacheKey("same-package"), after.copy(account = "b").pageCacheKey("same-package"))
    }

    @Test fun replacedSessionCannotOverwritePreviouslySavedResult() {
        val old = sessions.token
        store.save(scope, "grades", "term", JSONObject().put("value", "before"), old)
        sessions.replace("b")
        assertFalse(store.save(scope, "grades", "term", JSONObject().put("value", "late"), old))
        assertEquals("before", store.read(scope, "grades", "term")!!.data.getString("value"))
    }

    @Test fun deletionPreventsLateResponseFromRecreatingCache() {
        val old = sessions.token
        store.save(scope, "grades", "term", JSONObject(), old)
        store.removeAccount("a")
        assertFalse(store.save(scope, "grades", "term", JSONObject(), old))
        assertNull(store.read(scope, "grades", "term"))
        sessions.replace("a")
        assertTrue(store.save(scope, "grades", "term", JSONObject(), sessions.token))
    }

    @Test fun corruptStoredDataIsACacheMissInsteadOfCrashingThePage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("academic_snapshots", Context.MODE_PRIVATE)
        store.save(scope, "grades", "term", JSONObject(), sessions.token)
        preferences.edit().putString(preferences.all.keys.single(), "{broken").commit()
        assertNull(store.read(scope, "grades", "term"))
    }

    @Test fun legacyParserAcceptsAuthoritativeEmptyListsButRejectsErrorsAndMalformedRows() {
        assertTrue(StudyDataJson.validLegacyList("{\"items\":[]}"))
        assertTrue(StudyDataJson.validLegacyList("[]"))
        assertTrue(StudyDataJson.validLegacyList("{\"items\":[{\"kcmc\":\"数学\"}]}"))
        for (text in listOf("", "<html>login</html>", "{}", "{\"error\":\"failed\"}", "{\"items\":[{}]}"))
            assertFalse(StudyDataJson.validLegacyList(text))
    }

    @Test fun cancelledRequestWaitingForTheSessionLockCannotCommit() {
        val expected = sessions.token
        var allowed = true
        val started = java.util.concurrent.CountDownLatch(1)
        var result = true
        val thread = Thread { started.countDown(); result = store.save(scope, "grades", "term", JSONObject(), expected) { allowed } }
        synchronized(sessions) {
            thread.start()
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            allowed = false
        }
        thread.join(2_000)
        assertFalse(thread.isAlive)
        assertFalse(result)
        assertNull(store.read(scope, "grades", "term"))
    }

    @Test fun localManualTermsRemainDiscoverableAndConfigurationChangesDoNotBorrowAutomaticData() {
        val school = com.tyust.course.model.SchoolConfig("synthetic", "学校", "school.test", "https")
        val first = AcademicDataScope.forSchool("a", school, "qz")
        store.save(first, "grades", "term", JSONObject(), sessions.token)
        store.saveManual(first, "old-term", listOf(ExamRecord.manual("old-term",
            com.tyust.course.ui.screen.ExamItemUi("数学", "2026-01-01", "", "", "", ""))), sessions.token)
        school.gradesPath = "/new-query"
        val changed = AcademicDataScope.forSchool("a", school, "qz")
        assertNull(store.read(changed, "grades", "term"))
        assertEquals(listOf("old-term"), store.manualTerms(changed))
    }
}
