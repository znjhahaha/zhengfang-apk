package com.tyust.course.ui.system

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tyust.course.schedule.*
import com.tyust.course.ui.screen.ScheduleScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w360dp-h640dp")
class ScheduleFeedbackUiTest {
    @get:Rule val compose = createComposeRule()
    private var retries = 0
    private fun setup(cached: Boolean, initial: ScheduleLoadIssue?) {
        compose.setContent { MaterialTheme {
            var issue by remember { mutableStateOf(initial) }
            Box(Modifier.fillMaxSize().testTag("schedule-window")) {
                CompositionLocalProvider(LocalAppOverlayBottomInset provides 100.dp) {
                    ScheduleScreen(5, emptyList(), false, onWeekChange = {}, onCourseClick = {},
                        hasCachedSchedule = cached, loadIssue = issue, onRetry = { retries++ },
                        onDismissIssue = { issue = null }, now = 1791216000000L)
                }
            }
        } }
    }
    @Test fun expiredEmptyCacheIsStillContentAndShowsNoAutomaticBanner() {
        setup(true, ScheduleLoadIssue.expired())
        compose.onNodeWithText("登录已过期，重新登录后获取课表").assertDoesNotExist()
        compose.onNodeWithTag("schedule-refresh-notice").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多课表操作").assertIsDisplayed()
    }
    @Test fun missingCacheHasExplicitLoginInsteadOfEmptySchedule() {
        setup(false, ScheduleLoadIssue.expired())
        compose.onNodeWithText("登录已过期，重新登录后获取课表").assertIsDisplayed()
        compose.onNodeWithText("重新登录").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
    @Test fun networkFailureHasReachableRetryAndDismissWithoutDeletingContent() {
        setup(true, ScheduleLoadIssue(ScheduleLoadIssue.Kind.Network, "学校暂时无法连接"))
        val notice = compose.onNodeWithTag("schedule-refresh-notice").fetchSemanticsNode().boundsInRoot
        val window = compose.onNodeWithTag("schedule-window").fetchSemanticsNode().boundsInRoot
        assertTrue("Notice hidden beneath navigation", notice.bottom < window.bottom - 100f)
        compose.onNodeWithText("重试同步").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("schedule-refresh-notice").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多课表操作").assertIsDisplayed()
    }
    @Test fun validEmptyCacheIsNotAnError() {
        setup(true, null)
        compose.onNodeWithTag("schedule-refresh-notice").assertDoesNotExist()
        compose.onNodeWithText("重试同步").assertDoesNotExist()
    }
}
