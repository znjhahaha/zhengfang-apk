package com.tyust.course.ui.system

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real backdrop ownership/production toolbar and animation clock. Not GPU acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserFirstTapTest {
    @get:Rule val compose = createComposeRule()
    private val events = mutableListOf<BrowserPanel?>()
    private var calls = 0
    private var pageClicks = 0
    private fun setup() {
        compose.setContent { MaterialTheme {
            var panel by remember { mutableStateOf<BrowserPanel?>(null) }
            val wallpaper = rememberLayerBackdrop()
            val page = rememberLayerBackdrop()
            val modal = rememberCombinedBackdrop(wallpaper, page)
            val dialogs = rememberDialogHostState()
            CompositionLocalProvider(LocalAppBackdrop provides wallpaper, LocalModalBackdrop provides modal,
                LocalDialogHost provides dialogs) {
                GlassOverlayHost {
                    Canvas(Modifier.fillMaxSize().layerBackdrop(wallpaper)) { drawRect(Color(0xFFDDD5EE)) }
                    Box(Modifier.fillMaxSize().layerBackdrop(page).testTag("captured-page")) {
                        CollapsingGlassBrowser("插件中心", "模拟目录", rememberLazyListState(), {}, listOf("发现", "已安装"), 0, {},
                            panelActive = panel != null, actions = {
                                GlassSearchFilterPanel(panel, { events += it; panel = it }, reduceMotion = false,
                                    moreActions = listOf(SystemMenuAction("刷新目录", Icons.Outlined.Refresh, { calls++ })),
                                    search = { OutlinedTextField("", {}, label = { Text("搜索内容") }) },
                                    filters = { repeat(4) { GlassFilterCapsule("分类 $it", {}, selected = it == 0) } })
                            }, expandedControls = { Text("全部 · 全部学校") }) {
                            items((0..20).toList()) { TextButton({ pageClicks++ }) { Text("模拟条目 $it") } }
                        }
                    }
                    DialogHost(dialogs)
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }
    private fun frames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            // Compose time does not run Android layout/draw. Drain that frame too,
            // so onSpace/onSizeChanged can publish before the next animation tick.
            compose.waitForIdle()
        }
    }
    private fun settle() = frames(65)
    private fun openOnce(label: String, expected: String) {
        setup()
        val before = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription(label).performTouchInput { click() }
        // Advance actual animation frames without changing a second UI state.
        settle()
        compose.onNodeWithText(expected).assertIsDisplayed()
        val reveal = compose.onNodeWithTag("browser-panel-reveal").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("browser-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("Stuck in a thin strip", reveal.height > 48f)
        assertTrue("Final content remains clipped", reveal.height >= body.height - 2f)
        val after = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        compose.onAllNodes(hasTestTag("browser-panel") and hasAnyAncestor(hasTestTag("captured-page"))).assertCountEquals(0)
    }
    @Test fun coldSearchSinglePhysicalTap() { openOnce("搜索插件", "搜索内容") }
    @Test fun coldFiltersSinglePhysicalTap() { openOnce("筛选插件", "分类 0") }
    @Test fun coldMoreSinglePhysicalTapAndActionOnce() {
        openOnce("更多", "刷新目录")
        compose.onNodeWithText("刷新目录").performTouchInput { click() }
        settle()
        compose.onNodeWithTag("browser-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun rapidSwitchAndReverseCannotDismissReplacementOrReplay() {
        setup()
        compose.onNodeWithContentDescription("更多").performTouchInput { click() }
        frames(3)
        compose.onNodeWithContentDescription("筛选插件").performTouchInput { click() }
        frames(3)
        compose.onNodeWithContentDescription("搜索插件").performTouchInput { click() }
        settle()
        assertEquals(listOf(BrowserPanel.More, BrowserPanel.Filters, BrowserPanel.Search), events)
        compose.onNodeWithText("搜索内容").assertIsDisplayed()
        compose.onAllNodesWithTag("browser-panel").assertCountEquals(1)
        compose.onNodeWithContentDescription("搜索插件").performTouchInput { click() }
        frames(3)
        compose.onNodeWithContentDescription("筛选插件").performTouchInput { click() }
        settle()
        compose.onNodeWithText("分类 0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls); assertEquals(0, pageClicks) }
    }
}
