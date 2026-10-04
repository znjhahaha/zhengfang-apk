package com.tyust.course.ui.system

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InitialPageLoadTest {
    @get:Rule val compose = createComposeRule()

    private fun awaitWork(condition: () -> Boolean) {
        compose.waitForIdle()
        compose.waitUntil(5_000, condition)
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test fun blockedPreparationShowsDestinationAndDoesNotBlockSwitchingTabs() {
        val started = CompletableDeferred<Boolean>()
        val cancelled = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val page = mutableStateOf("课程")
        compose.setContent { MaterialTheme { Column {
            TextButton(onClick = { page.value = "设置" }) { Text("切换设置") }
            InitialPageLoad(page.value, page.value, true, true, prepare = {
                if (page.value == "课程") {
                    started.complete(Looper.myLooper() != Looper.getMainLooper())
                    try { gate.await() } finally { cancelled.complete(Unit) }
                }
            }) { Text("${page.value}内容") }
        } } }
        compose.onNodeWithTag("page-loading:课程").assertIsDisplayed()
        awaitWork { started.isCompleted }
        assertTrue(started.getCompleted())
        compose.onNodeWithText("课程内容").assertDoesNotExist()
        compose.onNodeWithText("切换设置").performClick()
        awaitWork { cancelled.isCompleted }
        awaitText("设置内容")
        gate.complete(Unit)
        compose.onNodeWithText("课程内容").assertDoesNotExist()
    }

    @Test fun preparedContentWaitsForTransitionCompletion() {
        val finished = mutableStateOf(false)
        compose.setContent { MaterialTheme {
            InitialPageLoad("courses", "课程", true, finished.value, {}) { Text("课程内容") }
        } }
        compose.onNodeWithTag("page-loading:课程").assertIsDisplayed()
        compose.onNodeWithText("课程内容").assertDoesNotExist()
        compose.runOnIdle { finished.value = true }
        awaitText("课程内容")
    }

    @Test fun revisitingEnteredPageDoesNotRepeatPreparation() {
        val store = PageDataState()
        val visible = mutableStateOf(true)
        val attempts = AtomicInteger()
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalPageDataState provides store) {
            if (visible.value) InitialPageLoad("courses", "课程", true, true,
                { attempts.incrementAndGet() }) { Text("课程内容") }
        } } }
        awaitWork { attempts.get() == 1 }
        awaitText("课程内容")
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("课程内容").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        awaitText("课程内容")
        assertEquals(1, attempts.get())
    }

    @Test fun newAccountCannotReusePreviousAccountsPreparedState() {
        val store = mutableStateOf(PageDataState())
        val gate = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalPageDataState provides store.value) {
            InitialPageLoad("courses", "课程", true, true, {
                if (attempts.incrementAndGet() > 1) gate.await()
            }) { Text("课程内容") }
        } } }
        awaitWork { attempts.get() == 1 }
        awaitText("课程内容")
        compose.runOnIdle { store.value = PageDataState() }
        compose.onNodeWithTag("page-loading:课程").assertIsDisplayed()
        compose.onNodeWithText("课程内容").assertDoesNotExist()
        gate.complete(Unit)
        awaitWork { attempts.get() == 2 }
        awaitText("课程内容")
    }

    @Test fun failureCanRetryWithoutRepeatingSuccessfulEntry() {
        val attempts = AtomicInteger()
        compose.setContent { MaterialTheme {
            InitialPageLoad("courses", "课程", true, true, {
                if (attempts.incrementAndGet() == 1) error("synthetic failure")
            }) { Text("课程内容") }
        } }
        awaitWork { attempts.get() == 1 }
        awaitText("页面准备失败，请重试")
        compose.onNodeWithText("重试").performClick()
        awaitWork { attempts.get() == 2 }
        awaitText("课程内容")
    }

    @Test fun inactiveDestinationDoesNotStartPreparation() {
        val active = mutableStateOf(false)
        val attempts = AtomicInteger()
        compose.setContent { MaterialTheme {
            InitialPageLoad("courses", "课程", active.value, true,
                { attempts.incrementAndGet() }) { Text("课程内容") }
        } }
        compose.onNodeWithTag("page-loading:课程").assertExists()
        assertEquals(0, attempts.get())
        compose.runOnIdle { active.value = true }
        awaitWork { attempts.get() == 1 }
        awaitText("课程内容")
    }

    @Test fun mountedPageWaitsForTerminalDataWithoutDuplicatingItsRequest() {
        val ready = mutableStateOf(false)
        val mounts = AtomicInteger()
        compose.setContent { MaterialTheme {
            InitialPageLoad("courses", "课程", true, true, {}, awaitContent = true) {
                LaunchedEffect(Unit) { mounts.incrementAndGet() }
                ReportInitialPageReady(ready.value)
                Text("已加载课程")
            }
        } }
        awaitWork { mounts.get() == 1 }
        compose.onNodeWithTag("page-loading:课程").assertExists()
        compose.onNodeWithText("已加载课程").assertDoesNotExist()
        compose.runOnIdle { ready.value = true }
        awaitText("已加载课程")
        compose.waitForIdle()
        compose.onNodeWithTag("page-loading:课程").assertDoesNotExist()
        compose.runOnIdle { ready.value = false }
        compose.onNodeWithText("已加载课程").assertIsDisplayed()
        assertEquals(1, mounts.get())
    }

    @Test fun terminalErrorAndEmptyResultBothLeaveSkeleton() {
        val terminal = mutableStateOf(false)
        val failed = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            InitialPageLoad("grades", "成绩", true, true, {}, awaitContent = true) {
                ReportInitialPageReady(terminal.value)
                Text(if (failed.value) "学校响应超时" else "本学期没有成绩")
            }
        } }
        compose.onNodeWithTag("page-loading:成绩").assertExists()
        compose.runOnIdle { terminal.value = true }
        awaitText("学校响应超时")
        compose.runOnIdle { failed.value = false }
        awaitText("本学期没有成绩")
        compose.onNodeWithTag("page-loading:成绩").assertDoesNotExist()
    }

    @Test fun cachedDataRevealsWithoutWaitingForSecondaryRequests() {
        val waitingForDetails = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            InitialPageLoad("courses", "课程", true, true, {}, awaitContent = true) {
                ReportInitialPageReady(true)
                Text("缓存课程")
                if (waitingForDetails.value) Text("教学班加载中")
            }
        } }
        awaitText("缓存课程")
        compose.onNodeWithText("教学班加载中").assertIsDisplayed()
    }
}
