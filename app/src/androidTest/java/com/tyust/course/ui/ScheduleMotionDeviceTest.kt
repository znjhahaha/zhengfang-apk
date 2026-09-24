package com.tyust.course.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.ui.screen.ScheduleCourseSheet
import com.tyust.course.ui.screen.ScheduleCourseUi
import com.tyust.course.ui.screen.ScheduleScreen
import com.tyust.course.ui.screen.CourseListScreen
import com.tyust.course.ui.screen.ScheduleCourseEditor
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.model.CourseFilter
import com.tyust.course.ui.system.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import com.tyust.course.ui.theme.NavigationPages
import com.tyust.course.ui.theme.NavigationMotionState
import com.tyust.course.ui.theme.rememberNavigationMotionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local schedule fixtures; executed only against the isolated UI preview package. */
@RunWith(AndroidJUnit4::class)
class ScheduleMotionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatedNavigationPreservesSavedInputAndSettlesOnTheLatestPage() {
        val selected = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CourseSelectorTheme(darkTheme = false) {
                val motion = rememberNavigationMotionState(selected.intValue, "preview", false)
                val holder = rememberSaveableStateHolder()
                NavigationPages(motion) { page ->
                    holder.SaveableStateProvider(page) {
                        var input by rememberSaveable { mutableStateOf("") }
                        OutlinedTextField(input, { input = it }, Modifier.testTag("page-$page"))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("page-0").performTextInput("saved draft")
        compose.runOnIdle { selected.intValue = 4 }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { selected.intValue = 1 }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { selected.intValue = 0 }
        compose.mainClock.advanceTimeBy(1800)
        compose.onNodeWithTag("page-0").assertTextContains("saved draft")
        compose.onNodeWithTag("page-4").assertDoesNotExist()
    }

    @Test fun themeChangeKeepsEditingState() {
        val dark = mutableStateOf(false)
        compose.setContent {
            CourseSelectorTheme(darkTheme = dark.value) {
                var input by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(input, { input = it }, Modifier.testTag("draft"))
            }
        }
        compose.onNodeWithTag("draft").performTextInput("课程草稿")
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("draft").assertTextContains("课程草稿")
        compose.runOnIdle { dark.value = false }
        compose.onNodeWithTag("draft").assertTextContains("课程草稿")
    }

    @Test fun sheetHandleDragDismissesExactlyOnce() {
        var dismissed = 0
        compose.setContent {
            CourseSelectorTheme(darkTheme = true) {
                val host = rememberDialogHostState()
                var open by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalDialogHost provides host) {
                        if (open) ScheduleCourseSheet(
                            ScheduleCourseUi("测试课程", "教师", "A101", 1, 1, 2, "1-16周", Color.Blue),
                            "preview", "2026-2027-1", emptyList(),
                            onDismiss = { dismissed++; open = false }, onEdit = {}, onDelete = {}, onConfigureTime = {})
                    }
                    DialogHost(host)
                }
            }
        }
        compose.onNodeWithContentDescription("下拉关闭课程详情").performTouchInput {
            swipe(center, center + Offset(0f, 800f), 150)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun predictiveCancellationRestoresSheetWithoutDismissingIt() {
        lateinit var sheet: ScheduleBottomSheetState
        lateinit var scope: CoroutineScope
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CourseSelectorTheme {
                sheet = remember { ScheduleBottomSheetState().apply { height = 600f } }
                scope = rememberCoroutineScope()
                Text("课程详情", Modifier.size(200.dp))
            }
        }
        compose.runOnIdle { sheet.predictiveProgress(0.4f); assertTrue(sheet.offset > 0) }
        compose.runOnIdle { scope.launch { sheet.restore() } }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(0f, sheet.offset, 0.1f) }
    }

    @Test fun navigationDirectionAndDragReleaseStayContinuous() {
        lateinit var motion: NavigationMotionState
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CourseSelectorTheme {
                motion = rememberNavigationMotionState(0, "preview", false)
                NavigationPages(motion, Modifier.testTag("schedule-navigation-frame")) { Text("页面 $it") }
            }
        }
        compose.runOnIdle {
            motion.select(2, false, releasedPosition = 1.6f, releasedVelocity = 0.3f)
            assertEquals(1.6f, motion.position, 0.001f)
            assertTrue(motion.transform(2).x > 0f)
        }
        captureFrame("navigation-start")
        compose.mainClock.advanceTimeBy(100)
        captureFrame("navigation-middle")
        compose.runOnIdle {
            val drawn = motion.position
            motion.select(0, false)
            assertEquals(drawn, motion.position, 0.001f)
        }
        compose.mainClock.advanceTimeBy(1600)
        captureFrame("navigation-end")
        compose.runOnIdle { assertEquals(0f, motion.position, 0.001f); assertEquals(setOf(0), motion.pages.keys) }
    }

    @Test fun pagerDragAndRepeatedArrowsKeepVerticalScrollAndCommitTheSettledWeek() {
        val week = mutableIntStateOf(1)
        val courses = listOf(ScheduleCourseUi("数学", "教师", "A101", 1, 1, 2, "1-16周", Color.Blue))
        compose.setContent {
            CourseSelectorTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    ScheduleScreen(week.intValue, courses, false, onWeekChange = { week.intValue = it }, onCourseClick = {},
                        firstWeekDate = "2026-09-07", displayPreferences = com.tyust.course.schedule.ScheduleDisplayPreferences(dayView = false))
                }
            }
        }
        compose.onNodeWithTag("schedule-grid-1").performTouchInput { swipeUp() }
        val previousScroll = compose.onNodeWithTag("schedule-grid-1").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(previousScroll > 0f)
        compose.onNodeWithTag("schedule-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, week.intValue) }
        val nextScroll = compose.onNodeWithTag("schedule-grid-2").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(previousScroll, nextScroll, 2f)
        compose.onNodeWithContentDescription("选择日期与学期").performClick()
        repeat(3) { compose.onNodeWithContentDescription("下一周").performClick(); compose.waitForIdle() }
        compose.onNodeWithContentDescription("选择第 5 周").performClick()
        compose.runOnIdle { assertEquals(5, week.intValue) }
        compose.onNodeWithText("第 5 周").assertIsDisplayed()
    }

    @Test fun calendarResolutionKeepsTheRequestedWeekWhenThePagerIsRecreated() {
        val date = mutableStateOf<String?>(null)
        val week = mutableIntStateOf(1)
        compose.setContent {
            CourseSelectorTheme {
                LaunchedEffect(date.value) { if (date.value != null) week.intValue = 6 }
                Box(Modifier.size(360.dp, 640.dp)) {
                    ScheduleScreen(week.intValue, emptyList(), false, periodCount = 4,
                        onWeekChange = { week.intValue = it }, onCourseClick = {},
                        firstWeekDate = date.value, weekRequestKey = date.value,
                        displayPreferences = com.tyust.course.schedule.ScheduleDisplayPreferences(dayView = false))
                }
            }
        }
        compose.runOnIdle { date.value = "2026-09-07" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(6, week.intValue) }
        compose.onNodeWithText("第 6 周").assertIsDisplayed()
    }

    @Test fun restoredScrollSurvivesAnInitiallyShortLayout() {
        val periods = mutableIntStateOf(4)
        compose.setContent {
            CourseSelectorTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    ScheduleScreen(1, emptyList(), false, periodCount = periods.intValue,
                        onWeekChange = {}, onCourseClick = {}, firstWeekDate = "2026-09-07",
                        displayPreferences = com.tyust.course.schedule.ScheduleDisplayPreferences(dayView = false),
                        positionKey = "restore-fixture", positionCalendar = "calendar",
                        restoredPosition = com.tyust.course.schedule.ScheduleViewPosition(1, 1, 600, 0, "calendar"))
                }
            }
        }
        fun scrollOffset() = compose.onNodeWithTag("schedule-grid-1").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Initial viewport must clamp the saved position", scrollOffset() < 600f)
        compose.runOnIdle { periods.intValue = 12 }
        compose.waitForIdle()
        assertEquals(600f, scrollOffset(), 1f)
    }

    @Test fun timetableBottomAndScrollRangeStayStableAcrossContentRefreshAndReverseDrag() {
        val courses = mutableStateOf(listOf(ScheduleCourseUi("数学", "教师", "A101", 1, 1, 2, "1-16周", Color.Blue)))
        compose.setContent {
            CourseSelectorTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    ScheduleScreen(1, courses.value, false, onWeekChange = {}, onCourseClick = {},
                        firstWeekDate = "2026-09-07", displayPreferences = com.tyust.course.schedule.ScheduleDisplayPreferences(dayView = false))
                }
            }
        }
        fun range() = compose.onNodeWithTag("schedule-grid-1").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        repeat(5) { compose.onNodeWithTag("schedule-grid-1").performTouchInput { swipeUp() } }
        val bottom = range().maxValue()
        assertEquals(bottom, range().value(), 2f)
        compose.runOnIdle { courses.value = courses.value.map { it.copy(name = "很长的课程名称与跨学科联合研讨实验项目及综合实践", teacher = "张文博、李思远、王若宁",
            location = "新校区 明理教学楼 B302 多媒体教室", isCurrent = true) } }
        compose.waitForIdle()
        val expandedBottom = range().maxValue()
        assertTrue("Complete metadata may grow the grid", expandedBottom > bottom)
        assertEquals("Refresh must preserve the bottom anchor without another gesture", expandedBottom, range().value(), 2f)
        repeat(3) {
            compose.onNodeWithTag("schedule-grid-1").performTouchInput { swipeUp() }
            assertEquals(expandedBottom, range().maxValue(), 2f)
            assertEquals(expandedBottom, range().value(), 2f)
        }
        compose.onNodeWithTag("schedule-grid-1").performTouchInput { swipeDown() }
        assertTrue(range().value() < expandedBottom)
        val stopped = range().value()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(stopped, range().value(), 2f)
    }

    @Test fun userDragCancelsPendingRestorationBeforeADataLayoutGrows() {
        val periods = mutableIntStateOf(8)
        compose.setContent {
            CourseSelectorTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    ScheduleScreen(1, emptyList(), false, periodCount = periods.intValue,
                        onWeekChange = {}, onCourseClick = {}, firstWeekDate = "2026-09-07",
                        displayPreferences = com.tyust.course.schedule.ScheduleDisplayPreferences(dayView = false),
                        positionKey = "drag-fixture", positionCalendar = "calendar",
                        restoredPosition = com.tyust.course.schedule.ScheduleViewPosition(1, 1, 600, 0, "calendar"))
                }
            }
        }
        fun offset() = compose.onNodeWithTag("schedule-grid-1").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("schedule-grid-1").performTouchInput { swipeDown() }
        val dragged = offset()
        compose.runOnIdle { periods.intValue = 12 }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(dragged, offset(), 2f)
    }

    @Test fun todayCancelsAnOlderPendingScrollRestoration() {
        val periods = mutableIntStateOf(2)
        var returns = 0
        compose.setContent {
            CourseSelectorTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    ScheduleScreen(1, emptyList(), false, periodCount = periods.intValue,
                        onWeekChange = {}, onCourseClick = {}, firstWeekDate = "2026-09-07",
                        now = java.util.GregorianCalendar(2026, 8, 8).timeInMillis,
                        selectedDay = 1, onTodayClick = { returns++ },
                        displayPreferences = com.tyust.course.schedule.ScheduleDisplayPreferences(dayView = false),
                        positionKey = "today-fixture", positionCalendar = "calendar",
                        restoredPosition = com.tyust.course.schedule.ScheduleViewPosition(1, 1, 600, 0, "calendar"))
                }
            }
        }
        fun offset() = compose.onNodeWithTag("schedule-grid-1").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("The saved offset must still be waiting for the initial layout", offset() < 600f)
        compose.onNodeWithTag("schedule-today").performClick()
        assertEquals(0f, offset(), 1f)
        compose.runOnIdle { periods.intValue = 12 }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(0f, offset(), 1f)
        assertEquals(1, returns)
    }

    @Test fun numberCarryMonthChangeAndRapidReversalKeepTheirAllocatedWidth() {
        val value = mutableStateOf("9.99")
        val direction = mutableLongStateOf(1)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CourseSelectorTheme {
                Box(Modifier.size(320.dp, 160.dp).testTag("schedule-navigation-frame")) {
                    AnimatedNumberText(value.value, Modifier.testTag("number-test"), directionKey = direction.longValue,
                        style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
                }
            }
        }
        val width = compose.onNodeWithTag("number-test").fetchSemanticsNode().boundsInRoot.width
        captureFrame("number-carry-start")
        compose.runOnIdle { value.value = "10.01"; direction.longValue = 2 }
        compose.mainClock.advanceTimeBy(80)
        captureFrame("number-carry-middle")
        compose.runOnIdle { value.value = "9.99"; direction.longValue = 1 }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("9.99").assertIsDisplayed()
        assertEquals(width, compose.onNodeWithTag("number-test").fetchSemanticsNode().boundsInRoot.width, 1f)
        compose.runOnIdle { value.value = "9月30日"; direction.longValue = 30 }
        compose.mainClock.advanceTimeBy(300)
        val dateWidth = compose.onNodeWithTag("number-test").fetchSemanticsNode().boundsInRoot.width
        compose.runOnIdle { value.value = "10月1日"; direction.longValue = 31 }
        compose.mainClock.advanceTimeBy(80)
        captureFrame("number-month-middle")
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("10月1日").assertIsDisplayed()
        assertEquals(dateWidth, compose.onNodeWithTag("number-test").fetchSemanticsNode().boundsInRoot.width, 1f)
        captureFrame("number-month-end")
    }

    @Test fun nestedScrollHandsOnlyUnconsumedDownwardMovementToSheet() {
        lateinit var sheet: ScheduleBottomSheetState
        lateinit var scope: CoroutineScope
        var closes = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            sheet = remember { ScheduleBottomSheetState().apply { height = 600f; density = 1f } }
            scope = rememberCoroutineScope()
            Text("详情")
        }
        compose.runOnIdle {
            val nested = sheet.nestedScroll { closes++ }
            assertEquals(Offset.Zero, nested.onPreScroll(Offset(0f, 100f), NestedScrollSource.UserInput))
            nested.onPostScroll(Offset(0f, 100f), Offset.Zero, NestedScrollSource.UserInput)
            assertEquals(0f, sheet.offset, 0f)
            nested.onPostScroll(Offset.Zero, Offset(0f, 100f), NestedScrollSource.UserInput)
            assertEquals(100f, sheet.offset, 0f)
            scope.launch { sheet.finishDrag(100f) { closes++ } }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(0f, sheet.offset, 0.1f); assertEquals(0, closes) }
    }

    @Test fun courseEditorDraftSurvivesThemeAndSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val dark = mutableStateOf(false)
        restoration.setContent {
            CourseSelectorTheme(darkTheme = dark.value) {
                val host = rememberDialogHostState()
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalDialogHost provides host) {
                        GlassSubpage(onDismiss = {}) { close ->
                            ScheduleCourseEditor(ScheduleSettingsManager.CustomCourse("draft", "", "", "", 1, 1, 2, "1-16周"),
                                emptyList(), 12, true, close, {})
                        }
                    }
                    DialogHost(host)
                }
            }
        }
        compose.onNode(hasSetTextAction() and hasText("课程名称")).performTextInput("未保存的课程")
        compose.runOnIdle { dark.value = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("未保存的课程").assertExists()
    }

    @Test fun accountSwitchRemovesOldModalAndResetsNavigationMotion() {
        val account = mutableStateOf("a")
        lateinit var motion: NavigationMotionState
        compose.setContent {
            CourseSelectorTheme {
                motion = rememberNavigationMotionState(if (account.value == "a") 0 else 3, account.value, false)
                val host = key(account.value) { rememberDialogHostState() }
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalDialogHost provides host) {
                        if (account.value == "a") SystemDialog(onDismissRequest = {}, title = { Text("账号 A 的详情") }) { Text("旧数据") }
                    }
                    DialogHost(host)
                }
            }
        }
        compose.onNodeWithText("旧数据").assertIsDisplayed()
        compose.runOnIdle { account.value = "b" }
        compose.onNodeWithText("旧数据").assertDoesNotExist()
        compose.runOnIdle { assertEquals(3f, motion.position, 0f); assertEquals(setOf(3), motion.pages.keys) }
    }

    @Test fun filterSummaryCanScrollHorizontallyOnNarrowScreen() {
        compose.setContent {
            CourseSelectorTheme {
                Box(Modifier.size(320.dp, 560.dp)) {
                    CourseListScreen(emptyList(), false, {}, {}, {}, {}, activeFilter = CourseFilter(
                        kkbmIdList = listOf("计算机科学与技术学院", "外国语学院", "机械工程学院", "材料科学学院")))
                }
            }
        }
        val row = compose.onNodeWithTag("active-filter-summary")
        row.performTouchInput { swipeLeft() }
        val range = row.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(range.maxValue() > 0f)
        assertTrue(range.value() > 0f)
    }

    private fun captureFrame(name: String) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val folder = java.io.File(context.getExternalFilesDir(null), "motion-validation").apply { mkdirs() }
        java.io.File(folder, "$name.png").outputStream().use { output ->
            compose.onNodeWithTag("schedule-navigation-frame").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
