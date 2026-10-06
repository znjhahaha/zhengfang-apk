package com.tyust.course.schedule

import com.tyust.course.academic.AcademicException
import com.tyust.course.academic.AcademicStatus
import com.tyust.course.manager.*
import com.tyust.course.ui.system.SessionNoticeState
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ScheduleLoadIssueTest {
    @Test fun expiryIsSilentForCachedDataAndHasExplicitLoginActionWithoutCache() {
        val issue = ScheduleLoadIssue.from(AcademicException(AcademicStatus.SESSION_EXPIRED, "expired"))
        assertFalse(issue.visibleWithCache())
        assertEquals("重新登录", issue.actionLabel)
        assertTrue(issue.message.contains("登录已过期"))
    }
    @Test fun errorsAreNeverConvertedIntoEmptySuccessOrExpiry() {
        for ((error, kind) in listOf(IOException("offline") to ScheduleLoadIssue.Kind.Network,
            AcademicException(AcademicStatus.PAGE_CHANGED, "invalid") to ScheduleLoadIssue.Kind.InvalidResponse,
            IllegalStateException("failed") to ScheduleLoadIssue.Kind.Other)) {
            val issue = ScheduleLoadIssue.from(error)
            assertEquals(kind, issue.kind); assertTrue(issue.visibleWithCache()); assertEquals(error.message, issue.message)
        }
    }
    @Test fun silentEpisodeCanBecomeInteractiveOnceAndBeRequestedAgainByUser() {
        val sessions = SessionStateStore(); val token = sessions.replace("account")
        val notices = SessionNoticeState()
        sessions.expire(token, RequestFeedback.Silent)
        notices.update(sessions.state.value, true, true)
        assertFalse(notices.state.value.visible)
        sessions.expire(token, RequestFeedback.Interactive)
        notices.update(sessions.state.value, true, true)
        assertTrue(notices.state.value.visible)
        notices.dismiss(token); notices.update(sessions.state.value, true, true)
        assertFalse(notices.state.value.visible)
        notices.request(token); assertTrue(notices.state.value.visible)
        val newToken = sessions.replace("other")
        assertFalse(sessions.expire(token, RequestFeedback.Interactive))
        notices.update(sessions.state.value, false, true)
        assertEquals(newToken, notices.state.value.token); assertFalse(notices.state.value.visible)
    }
}
