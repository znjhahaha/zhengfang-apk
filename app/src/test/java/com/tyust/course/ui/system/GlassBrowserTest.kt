package com.tyust.course.ui.system

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tyust.course.manager.AppearanceSettingsManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Layout/state/semantics evidence with no optical backdrop. No device/GPU claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w840dp-h900dp")
class GlassBrowserTest {
    @get:Rule val compose = createComposeRule()
    private val width = mutableStateOf(360.dp)
    private val height = mutableStateOf(640.dp)
    private val font = mutableFloatStateOf(1f)
    private val dark = mutableStateOf(false)
    private val reduced = mutableStateOf(true)
    private val count = mutableIntStateOf(100)
    private val failed = mutableStateOf(false)
    private lateinit var first: LazyListState
    private lateinit var second: LazyListState
    private var activePanel: BrowserPanel? = null
    private var requests = 0
    private var installs = 0
    @After fun reset() { AppearanceSettingsManager.updateGlassEffect(true) }

    @Composable private fun Harness() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var panel by rememberSaveable { mutableStateOf<BrowserPanel?>(null) }
        var query by rememberSaveable { mutableStateOf("") }
        var onlySchool by rememberSaveable { mutableStateOf(false) }
        var detail by rememberSaveable { mutableStateOf(false) }
        val a = rememberLazyListState(); val b = rememberLazyListState()
        SideEffect { first = a; second = b; activePanel = panel }
        LaunchedEffect(Unit) { requests++ }
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font.floatValue)) {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                GlassOverlayHost(Modifier.requiredSize(width.value, height.value)) {
                    val search: @Composable () -> Unit = {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("query"), label = { Text("关键词") })
                    }
                    val filters: @Composable () -> Unit = {
                        FilterChip(onlySchool, { onlySchool = !onlySchool }, label = { Text("仅本校") })
                    }
                    CollapsingGlassBrowser("插件中心", "学校教务与校园服务", if (tab == 0) a else b, {},
                        listOf("发现", "已安装"), tab, { tab = it }, panelActive = panel != null,
                        actions = {
                            GlassSearchFilterPanel(panel, { panel = it }, reduceMotion = reduced.value, search = search, filters = filters)
                            TopBarActionRail { action(0, Icons.Outlined.MoreHoriz, "更多", {}) }
                        }, expandedControls = { filters(); search() }) {
                        if (failed.value) item("error") {
                            Text("目录加载失败"); SystemDialogButton({ failed.value = false; requests++ }) { Text("重试目录") }
                        }
                        if (count.intValue == 0) item("empty") { Text("没有匹配的插件") }
                        items((0 until count.intValue).toList(), key = { "entry-$it" }) { item ->
                            Column(Modifier.fillMaxWidth().heightIn(min = 90.dp).testTag("entry-$item")) {
                                Text("模拟插件 $item")
                                Row { TextButton({ detail = true }) { Text("详情 $item") }
                                    TextButton({ installs++ }) { Text("安装 $item") } }
                            }
                        }
                    }
                    if (detail) SystemDialog({ detail = false }, confirmButton = { SystemDialogButton({ detail = false }) { Text("关闭详情") } }) { Text("详情内容") }
                }
            }
        }
    }
    private fun setUp() { compose.setContent { Harness() } }
    private fun scroll(state: LazyListState, index: Int, offset: Int = 0) = compose.runOnIdle {
        runBlocking { state.scrollToItem(index, offset) }
    }

    @Test fun collapsedListHasSeventyPercentOfCompactWindowAndAllActions() {
        setUp(); scroll(first, 10)
        val list = compose.onNodeWithTag("plugin-list").fetchSemanticsNode().boundsInRoot
        val window = compose.onNodeWithTag("browser-window").fetchSemanticsNode().boundsInRoot
        assertTrue("List ratio ${list.height / window.height}", list.height >= window.height * .70f)
        for (label in listOf("返回", "搜索插件", "筛选插件", "更多")) compose.onNodeWithContentDescription(label).assertIsDisplayed()
        compose.onNodeWithText("发现").assertIsDisplayed(); compose.onNodeWithText("已安装").assertIsDisplayed()
        compose.onNodeWithTag("browser-expanded").assertDoesNotExist()
    }

    @Test fun everyWindowSizeKeepsActionsAndLargeTextInsideBounds() {
        setUp()
        for (w in listOf(360, 411, 600, 840)) for (scale in listOf(1f, 1.8f)) {
            compose.runOnIdle { width.value = w.dp; height.value = 480.dp; font.floatValue = scale }
            scroll(first, 15)
            for (label in listOf("返回", "搜索插件", "筛选插件", "更多")) {
                compose.onNodeWithContentDescription(label).assertIsDisplayed().assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
            }
            compose.onNodeWithTag("plugin-list").assertHeightIsAtLeast(240.dp)
            val tabs = compose.onNodeWithTag("plugin-tabs").fetchSemanticsNode().boundsInRoot
            for (label in listOf("发现", "已安装")) {
                val bounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.top >= tabs.top && bounds.bottom <= tabs.bottom)
            }
        }
    }

    @Test fun tabScrollAndDetailReturnDoNotReloadOrInstall() {
        setUp(); scroll(first, 20, 7)
        compose.onNodeWithText("已安装").performClick(); scroll(second, 5, 11)
        compose.onNodeWithText("发现").performClick()
        compose.runOnIdle { assertEquals(20, first.firstVisibleItemIndex); assertEquals(7, first.firstVisibleItemScrollOffset) }
        compose.onNodeWithText("详情 19").performClick()
        compose.onNodeWithText("关闭详情").performClick()
        compose.runOnIdle { assertEquals(20, first.firstVisibleItemIndex); assertEquals(1, requests); assertEquals(0, installs) }
        compose.onNodeWithText("已安装").performClick()
        compose.runOnIdle { assertEquals(5, second.firstVisibleItemIndex); assertEquals(11, second.firstVisibleItemScrollOffset) }
    }

    @Test fun recreationRestoresBothListsSearchFilterAndOpenPanel() {
        val restore = StateRestorationTester(compose)
        restore.setContent { Harness() }; scroll(first, 20, 5)
        compose.onNodeWithText("已安装").performClick(); scroll(second, 7, 9)
        compose.onNodeWithContentDescription("搜索插件").performClick()
        compose.onNodeWithTag("query").performTextInput("图书馆")
        compose.onNodeWithContentDescription("筛选插件").performClick()
        compose.onNodeWithText("仅本校").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("仅本校").assertIsSelected()
        compose.runOnIdle { assertEquals(BrowserPanel.Filters, activePanel); assertEquals(20, first.firstVisibleItemIndex); assertEquals(7, second.firstVisibleItemIndex) }
        compose.onNodeWithContentDescription("搜索插件").performClick()
        compose.onNodeWithTag("query").assertTextContains("图书馆")
    }

    @Test fun closingAndReopeningPanelRetainsConditionsAndHidesUnderlyingSemantics() {
        setUp(); scroll(first, 10)
        compose.onNodeWithContentDescription("筛选插件").performClick()
        compose.onNodeWithText("仅本校").performClick()
        compose.onNodeWithTag("plugin-list").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭面板").performClick()
        compose.onNodeWithTag("plugin-list").assertIsDisplayed()
        compose.onNodeWithContentDescription("筛选插件").performClick()
        compose.onNodeWithText("仅本校").assertIsSelected()
        compose.runOnIdle { assertEquals(1, requests); assertEquals(0, installs) }
    }

    @Test fun shortWindowPanelScrollsAndReducedMotionClosesImmediately() {
        compose.runOnIdle { height.value = 340.dp; font.floatValue = 1.8f }
        setUp(); scroll(first, 5)
        compose.onNodeWithContentDescription("筛选插件").performClick()
        compose.onNodeWithText("仅本校").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("关闭面板").performScrollTo().performClick()
        compose.onNodeWithTag("browser-panel").assertDoesNotExist()
    }

    @Test fun emptyAndErrorRowsStayReachableAndRetryOnlyOnClick() {
        count.intValue = 0; failed.value = true
        setUp()
        compose.onNodeWithTag("plugin-list").performScrollToNode(hasText("没有匹配的插件"))
        compose.onNodeWithText("没有匹配的插件").assertIsDisplayed()
        compose.onNodeWithText("重试目录").performClick()
        compose.runOnIdle { assertEquals(2, requests); assertEquals(0, installs) }
        compose.onNodeWithText("目录加载失败").assertDoesNotExist()
    }


    @Test fun openingPanelKeepsToolbarAnchorsInPlaceAtEveryWidth() {
        setUp()
        for (w in listOf(360, 411, 600, 840)) {
            compose.runOnIdle { width.value = w.dp }
            scroll(first, 10)
            val before = compose.onNodeWithContentDescription("搜索插件").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("筛选插件").performClick()
            val after = compose.onNodeWithContentDescription("搜索插件").fetchSemanticsNode().boundsInRoot
            assertEquals("anchor x at $w", before.center.x, after.center.x, 1f)
            assertEquals("anchor y at $w", before.center.y, after.center.y, 1f)
            compose.onNodeWithContentDescription("关闭面板").performClick()
        }
    }

    @Test fun rapidPanelReversalSettlesWithOneForegroundAndNoActionReplay() {
        reduced.value = false; setUp(); scroll(first, 8)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("搜索插件").performClick(); compose.mainClock.advanceTimeBy(96)
        compose.onNodeWithContentDescription("筛选插件").performClick(); compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithContentDescription("筛选插件").performClick(); compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithContentDescription("搜索插件").performClick(); compose.mainClock.advanceTimeBy(1500)
        compose.onAllNodesWithTag("browser-panel").assertCountEquals(1)
        compose.onNodeWithTag("query").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("关闭面板").performClick()
        compose.onNodeWithTag("browser-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requests); assertEquals(0, installs) }
    }

    @Test fun reverseScrollAndThemeGlassTogglesKeepLabelsReachable() {
        setUp()
        for (isDark in listOf(false, true)) for (glass in listOf(false, true)) {
            compose.runOnIdle { dark.value = isDark; AppearanceSettingsManager.updateGlassEffect(glass) }
            scroll(first, 15); scroll(first, 0, 40); scroll(first, 10); scroll(first, 0)
            compose.onNodeWithTag("browser-expanded").assertIsDisplayed()
            compose.onNodeWithTag("query").assertIsDisplayed()
            compose.onNodeWithText("发现").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(1, requests) }
    }
}
