package com.tyust.course.ui.system

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real composition ownership/geometry; deliberately does not claim native GPU execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class, qualifiers = "w411dp-h891dp")
class GlassOverlayLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bodyNeverEntersSampledPageEvenDuringFirstMountOrDisposal() {
        val open = mutableStateOf(false)
        val present = mutableStateOf(true)
        val width = mutableStateOf(0.dp)
        var mounted = 0
        var disposed = 0
        compose.setContent { MaterialTheme { GlassOverlayHost {
            Box(Modifier.fillMaxSize().testTag("sampled-page")) {
                if (present.value) AnchoredGlassOverlay(open.value, 120.dp, 120.dp, 280.dp, { open.value = false }, { _, _ -> },
                    modifier = Modifier.width(width.value).height(48.dp), anchor = { Text("稳定按钮") }) {
                    assertTrue("Optical body composed outside overlay", LocalOverlayBody.current)
                    DisposableEffect(Unit) { mounted++; onDispose { disposed++ } }
                    Text("面板正文", Modifier.testTag("optical-body"))
                }
            }
        } } }
        compose.runOnIdle { open.value = true }
        compose.onNodeWithTag("optical-body").assertDoesNotExist()
        compose.runOnIdle { width.value = 48.dp }
        compose.onNodeWithTag("optical-body").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("optical-body") and hasAnyAncestor(hasTestTag("sampled-page"))).assertCountEquals(0)
        repeat(5) {
            compose.runOnIdle { open.value = false }
            compose.onNodeWithTag("optical-body").assertDoesNotExist()
            compose.runOnIdle { open.value = true }
            compose.onAllNodesWithTag("optical-body").assertCountEquals(1)
        }
        compose.runOnIdle { present.value = false }
        compose.onNodeWithTag("optical-body").assertDoesNotExist()
        compose.runOnIdle { assertEquals(mounted, disposed); assertEquals(6, mounted) }
    }

    @Test fun missingHostUsesDifferentWindowWithoutBorrowingOpticalSources() {
        val open = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalModalBackdrop provides rememberLayerBackdrop()) {
                AnchoredGlassOverlay(open.value, 120.dp, 120.dp, 280.dp, { open.value = false }, { _, _ -> },
                    modifier = Modifier.size(48.dp), anchor = { Text("按钮") }) {
                    assertNull(LocalModalBackdrop.current)
                    assertNull(LocalControlBackdrop.current)
                    TextButton({ open.value = false }) { Text("关闭回退") }
                }
            }
        } }
        compose.onNodeWithText("关闭回退").performClick()
        compose.onNodeWithText("关闭回退").assertDoesNotExist()
        compose.onNodeWithText("按钮").assertIsDisplayed()
    }

    @Test fun menuPreservesDisabledActionsAndRunsAcceptedActionOnceAfterClosing() {
        var calls = 0
        val enabled = mutableStateOf(false)
        compose.setContent { MaterialTheme { GlassOverlayHost {
            SystemActionMenu("更多", listOf(SystemMenuAction("刷新目录", Icons.Outlined.Refresh, { calls++ }, enabled = enabled.value)))
        } } }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("刷新目录").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, calls); enabled.value = true }
        compose.onNodeWithText("刷新目录").assertIsEnabled().performClick()
        compose.onNodeWithText("刷新目录").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun realTapsReachStableToolbarButCannotClickThroughDismissLayer() {
        var open by mutableStateOf(false)
        var bounds by mutableStateOf(Rect.Zero)
        var switches = 0
        var pageClicks = 0
        compose.setContent { MaterialTheme { GlassOverlayHost {
            Column {
                CompositionLocalProvider(LocalOverlayControls provides bounds) {
                    Row(Modifier.fillMaxWidth().height(48.dp).onGloballyPositioned {
                        bounds = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                    }) {
                        AnchoredGlassOverlay(open, 100.dp, 100.dp, 180.dp, { open = false }, { _, _ -> },
                            modifier = Modifier.width(100.dp).height(48.dp), anchor = {
                                TextButton({ open = !open }) { Text("打开筛选") }
                            }) { Text("面板正文") }
                        TextButton({ switches++ }) { Text("切换工具") }
                    }
                }
                Spacer(Modifier.height(260.dp))
                TextButton({ pageClicks++ }) { Text("列表操作") }
            }
        } } }
        compose.onNodeWithText("打开筛选").performTouchInput { click() }
        compose.onNodeWithText("面板正文").assertIsDisplayed()
        compose.onNodeWithText("切换工具").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, switches); assertTrue(open) }
        compose.onNodeWithText("列表操作").performTouchInput { click() }
        compose.runOnIdle { assertFalse(open); assertEquals(0, pageClicks) }
        compose.onNodeWithText("列表操作").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, pageClicks) }
    }

    @Test fun leavingPageDuringMenuCloseCancelsPendingAction() {
        val present = mutableStateOf(true)
        var calls = 0
        compose.setContent { MaterialTheme { GlassOverlayHost {
            if (present.value) SystemActionMenu("更多", listOf(SystemMenuAction("离页操作", Icons.Outlined.Refresh, { calls++ })))
        } } }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("离页操作").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("离页操作").performClick()
        compose.runOnIdle { present.value = false }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(0, calls); present.value = true }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun scheduleMoreRetainsAnchorAndExecutesSyncAfterClosing() {
        var syncs = 0
        compose.setContent { MaterialTheme { GlassOverlayHost {
            com.tyust.course.ui.screen.WeekHeaderCompact(5, {}, {}, onSyncClick = { syncs++ })
        } } }
        val before = compose.onNodeWithContentDescription("更多课表操作").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("更多课表操作").performTouchInput { click() }
        compose.onNodeWithText("同步课表").assertIsDisplayed()
        val after = compose.onNodeWithContentDescription("更多课表操作").fetchSemanticsNode().boundsInRoot
        assertEquals(before.center.x, after.center.x, 1f)
        assertEquals(before.center.y, after.center.y, 1f)
        compose.onNodeWithText("同步课表").performClick()
        compose.onNodeWithText("同步课表").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, syncs) }
    }

    @Test fun controlledMenuDoesNotClearReplacementFilterState() {
        var selected by mutableStateOf<BrowserPanel?>(BrowserPanel.More)
        compose.setContent { MaterialTheme { GlassOverlayHost {
            Row {
                SystemActionMenu("更多", emptyList(), expanded = selected == BrowserPanel.More,
                    onExpandedChange = { if (it) selected = BrowserPanel.More else if (selected == BrowserPanel.More) selected = null })
                GlassSearchFilterPanel(selected, { selected = it }, reduceMotion = true,
                    search = { Text("搜索内容") }, filters = { Text("筛选内容") })
            }
        } } }
        compose.runOnIdle { selected = BrowserPanel.Filters }
        compose.onNodeWithText("筛选内容").assertIsDisplayed()
        compose.runOnIdle { assertEquals(BrowserPanel.Filters, selected) }
    }
}
