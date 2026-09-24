package com.tyust.course.schedule

import android.app.Application
import android.os.Looper
import com.tyust.course.academic.AcademicException
import com.tyust.course.academic.AcademicStatus
import com.tyust.course.academic.AcademicTerm
import com.tyust.course.manager.RequestFeedback
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import com.tyust.course.ui.system.SessionNoticeState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ScheduleNetworkTest {
    private val term = AcademicTerm("2026-2027-1")

    private fun withServer(block: (MockWebServer, SchoolConfig, UserManager) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val user = UserManager.getInstance().apply { init(context) }
        CourseApiClient.getInstance().init(context)
        val account = user.sessionState.token.accountStorageKey
        MockWebServer().use { server ->
            server.start()
            val url = server.url("/")
            val school = SchoolConfig("schedule-network", "测试学校", "${url.host}:${url.port}", "http")
            user.sessionState.replace("schedule-network-test")
            try { block(server, school, user) }
            finally {
                shadowOf(Looper.getMainLooper()).idle()
                user.sessionState.replace(account)
            }
        }
    }

    @Test fun emptyTimetableIsValidButHtmlAndServerErrorsAreNotCachedAsEmptyCourses() = withServer { server, school, user ->
        val token = user.sessionState.token
        server.enqueue(MockResponse().setBody("{\"kbList\":[]}"))
        val empty = runBlocking { withTimeout(5_000) { fetchLegacySchedule(school, term, term, token, RequestFeedback.Silent) } }
        assertTrue(requireNotNull(ScheduleJson.parse(empty.json)).isEmpty())
        assertEquals(term, empty.term)
        for (response in listOf(MockResponse().setBody("<html>维护中</html>"), MockResponse().setResponseCode(503))) {
            server.enqueue(response)
            val error = runBlocking { runCatching {
                withTimeout(5_000) { fetchLegacySchedule(school, term, term, token, RequestFeedback.Silent) }
            }.exceptionOrNull() }
            assertEquals(AcademicStatus.PAGE_CHANGED, (error as AcademicException).status)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(user.sessionState.state.value.expired)
    }

    @Test fun automaticExpiryStaysSilentUntilAnInteractiveRequestAlsoExpires() = withServer { server, school, user ->
        val token = user.sessionState.token
        val notices = SessionNoticeState()
        for (feedback in listOf(RequestFeedback.Silent, RequestFeedback.Interactive)) {
            // Exercise the HTTP interceptor, including propagation of the per-request
            // policy before the timetable coroutine receives its parsed failure.
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"notLogin\":true}"))
            val error = runBlocking { runCatching {
                withTimeout(5_000) { fetchLegacySchedule(school, term, term, token, feedback) }
            }.exceptionOrNull() }
            assertEquals(AcademicStatus.SESSION_EXPIRED, (error as AcademicException).status)
            shadowOf(Looper.getMainLooper()).idle()
            val state = user.sessionState.state.value
            assertTrue(state.expired)
            assertEquals(feedback, state.expiryFeedback)
            notices.update(state, true, true)
            assertEquals(feedback == RequestFeedback.Interactive, notices.state.value.visible)
        }
        assertEquals(2, server.requestCount)
    }
}
