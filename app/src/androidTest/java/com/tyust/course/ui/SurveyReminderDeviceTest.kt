package com.tyust.course.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import com.tyust.course.survey.*
import com.tyust.course.ui.screen.SurveyReminder
import com.tyust.course.ui.system.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurveyReminderDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: SurveyRepository
    private val foreground = mutableStateOf(true)
    private val updateVisible = mutableStateOf(true)
    private var opened: String? = null

    @Before fun prepare() {
        assumeTrue(BuildConfig.UI_PREVIEW)
        val now = System.currentTimeMillis()
        fun item(id: String, title: String) = Survey(id, title, url = "https://www.wjx.cn/vm/fictional")
        val samples = listOf(item("dining", "虚构食堂体验问卷"), item("library", "虚构图书馆问卷").copy(important = true),
            item("completed", "已完成的虚构问卷"), item("expired", "已过期的虚构问卷").copy(endsAt = now - 1000),
            item("future", "未开始的虚构问卷").copy(startsAt = now + 86_400_000))
        val store = object : SurveyStore {
            var saved = SurveySavedData()
            override suspend fun read() = saved
            override suspend fun write(data: SurveySavedData) { saved = data }
        }
        val transport = object : SurveyTransport {
            override suspend fun list(schoolHost: String) = SurveyFeedResult(samples, System.currentTimeMillis())
            override suspend fun detail(id: String, schoolHost: String) = SurveyDetailResult(samples.first { it.id == id }, System.currentTimeMillis())
            override suspend fun click(id: String, schoolHost: String, requestId: String) = Unit
        }
        repository = SurveyRepository(store, transport, "fictional.example", scope)
        runBlocking { repository.refresh(); repository.setCompleted(samples[2], true) }
        SurveyVisitTracker.budget.beginVisit()
        compose.setContent {
            CourseSelectorTheme {
                val host = rememberDialogHostState()
                CompositionLocalProvider(LocalDialogHost provides host) {
                    Box(Modifier.fillMaxSize()) {
                        Text("虚构课表页面")
                        if (updateVisible.value) SystemDialog(ownerKey = "update-fixture", onDismissRequest = { updateVisible.value = false },
                            title = { Text("发现新版本") }, confirmButton = {
                                TextButton(onClick = { updateVisible.value = false }) { Text("暂不更新") }
                            }) { Text("本地弹窗优先级验收") }
                        SurveyReminder(repository, canPresent = !updateVisible.value && !host.hasBlockingSurfaceExcept("survey-reminder"),
                            foreground = foreground.value, onOpen = { opened = it })
                        DialogHost(host)
                    }
                }
            }
        }
    }
    @After fun cleanup() { scope.cancel() }
    private fun awaitText(value: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun showReminder() {
        compose.onNodeWithText("暂不更新").performClick()
        awaitText("有 2 份问卷待填写")
    }

    @Test fun aggregatesOnlyPendingSurveysAfterUpdateAndDoesNotRepeatInSameDay() {
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithText("发现新版本").assertIsDisplayed()
        compose.onNodeWithText("有 2 份问卷待填写").assertDoesNotExist()
        assertEquals(2, repository.reminderCandidates().size)
        showReminder()
        compose.onNodeWithText("虚构食堂体验问卷").assertIsDisplayed()
        compose.onNodeWithText("虚构图书馆问卷").assertIsDisplayed()
        for (text in listOf("已完成的虚构问卷", "已过期的虚构问卷", "未开始的虚构问卷")) compose.onNodeWithText(text).assertDoesNotExist()
        compose.waitUntil(5000) { repository.reminderCandidates().isEmpty() }
        compose.onNodeWithText("稍后").performClick()
        compose.runOnIdle { foreground.value = false }
        compose.runOnIdle { SurveyVisitTracker.budget.beginVisit(); foreground.value = true }
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithText("有 2 份问卷待填写").assertDoesNotExist()
        assertTrue(repository.reminderCandidates().isEmpty())
    }

    @Test fun completionRemovesVisibleSurveyAndOpenKeepsRemainingOneUncompleted() {
        showReminder()
        runBlocking { repository.setCompleted(repository.state.value.survey("dining")!!, true) }
        awaitText("有一份问卷待填写")
        compose.onNodeWithText("虚构食堂体验问卷").assertDoesNotExist()
        compose.onNodeWithText("查看问卷").performClick()
        compose.runOnIdle { assertEquals("library", opened) }
        assertNull(repository.state.value.local("library").completedAt)
    }

    @Test fun reminderSwitchPersistsAndStopsFutureReminders() {
        showReminder()
        compose.onNodeWithText("关闭问卷提醒").performClick()
        compose.waitUntil(5000) { !repository.state.value.saved.remindersEnabled }
        compose.onNodeWithText("有 2 份问卷待填写").assertDoesNotExist()
        assertTrue(repository.reminderCandidates().isEmpty())
    }
}
