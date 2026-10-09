package com.tyust.course.ui.system

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.tyust.course.BottomNavItem
import com.tyust.course.ui.system.glass.DampedDragAnimation
import com.tyust.course.ui.system.glass.InteractiveOptics
import com.tyust.course.ui.system.glass.rememberInteractiveOptics
import com.tyust.course.ui.theme.CourseSelectorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap

/** Runs only local UI fixtures; no login, requests, or course-selection operations. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class LiquidInteractionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val destinations = listOf(
        BottomNavItem.Courses, BottomNavItem.Schedule, BottomNavItem.Grab,
        BottomNavItem.Grades, BottomNavItem.Settings
    )

    @Test fun passwordSymbolReversesFromItsCurrentFrameAndReturnsToItsOriginalShape() {
        val revealed = mutableStateOf(false)
        compose.setContent {
            CourseSelectorTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                VisibilitySymbol(revealed.value, Modifier.testTag("password-symbol"))
            } }
        }
        compose.mainClock.autoAdvance = false
        val initial = compose.onNodeWithTag("password-symbol").captureToImage().asAndroidBitmap()
        compose.runOnIdle { revealed.value = true }
        compose.mainClock.advanceTimeBy(112)
        val middle = compose.onNodeWithTag("password-symbol").captureToImage().asAndroidBitmap()
        assertFalse("The icon must draw intermediate geometry", initial.sameAs(middle))
        compose.runOnIdle { revealed.value = false }
        compose.mainClock.advanceTimeBy(600)
        val end = compose.onNodeWithTag("password-symbol").captureToImage().asAndroidBitmap()
        assertTrue("Reversal must settle back to the original shape", initial.sameAs(end))
        compose.onNodeWithContentDescription("显示密码").assertExists()
    }

    @Test fun queueSuccessDrawsIntermediateFramesThenSettlesToACheck() {
        val result = mutableStateOf(SymbolResult.None)
        compose.setContent {
            CourseSelectorTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RequestStateSymbol(result = result.value, queue = true, modifier = Modifier.testTag("queue-symbol"))
            } }
        }
        compose.mainClock.autoAdvance = false
        val initial = compose.onNodeWithTag("queue-symbol").captureToImage().asAndroidBitmap()
        compose.runOnIdle { result.value = SymbolResult.Success }
        compose.mainClock.advanceTimeBy(112)
        val middle = compose.onNodeWithTag("queue-symbol").captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(600)
        val end = compose.onNodeWithTag("queue-symbol").captureToImage().asAndroidBitmap()
        assertFalse(initial.sameAs(middle))
        assertFalse(middle.sameAs(end))
        assertFalse(initial.sameAs(end))
    }

    private fun navigation(onSelection: (Int) -> Unit = {}): MutableIntState {
        val selected = mutableIntStateOf(0)
        compose.setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    CapsuleNavigationBar(
                        items = destinations,
                        selectedTab = selected.intValue,
                        onTabSelect = { selected.intValue = it; onSelection(it) },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
        compose.waitForIdle()
        return selected
    }

    @Test fun pressingAgainDuringSelectionStillSettlesAndReleases() {
        lateinit var animation: DampedDragAnimation
        compose.setContent {
            val scope = rememberCoroutineScope()
            DisposableEffect(scope) {
                animation = DampedDragAnimation(scope, 0f, 0f..4f, 0.001f, 1f, 1.2f,
                    onDragStarted = {}, onDragStopped = {}, onDrag = { _, _ -> })
                onDispose { }
            }
            Box(Modifier.fillMaxSize())
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { animation.animateToValue(3f) }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { animation.press() }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { animation.release() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2000)
        compose.runOnIdle {
            assertEquals("selection must finish after a second tap", 3f, animation.value, 0.002f)
            assertEquals("press highlight must be released", 0f, animation.pressProgress, 0.002f)
            assertEquals(1f, animation.scaleX, 0.002f)
            assertEquals(1f, animation.scaleY, 0.002f)
        }
    }

    @Test fun directDragReversesWithoutWaitingForASpringOrLosingItsReleaseFrame() {
        lateinit var animation: DampedDragAnimation
        compose.setContent {
            val scope = rememberCoroutineScope()
            DisposableEffect(scope) {
                animation = DampedDragAnimation(scope, 0f, 0f..4f, 0.001f, 1f, 1.2f,
                    onDragStarted = {}, onDragStopped = {}, onDrag = { _, _ -> })
                onDispose { }
            }
            Box(Modifier.fillMaxSize())
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            animation.press(1000)
            for ((index, position) in listOf(0.2f, 0.8f, 1.9f, 1.2f, 0.9f, 0.3f).withIndex()) {
                animation.updateValue(position, 1016L + 16L * index)
                assertEquals("A pointer update must be visible before the next animation frame", position, animation.value, 0f)
            }
            assertTrue("Velocity must follow the reverse gesture", animation.positionVelocity < 0f)
            animation.animateToValue(0f)
            assertEquals("Release must start at the last finger position", 0.3f, animation.value, 0.001f)
        }
        compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle {
            assertEquals(0f, animation.value, 0.001f)
            assertEquals(1f, animation.scaleX, 0.001f)
            animation.setReducedMotion(true)
            animation.press(2000)
            animation.updateValue(2.7f, 2016)
            assertEquals(2.7f, animation.value, 0f)
            animation.animateToValue(3f)
            assertEquals(3f, animation.value, 0f)
            assertEquals(0f, animation.pressProgress, 0f)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun cancelledNavigationDragDoesNotCommitIntermediatePage() {
        val commits = mutableListOf<Int>()
        val selected = navigation { commits.add(it) }
        compose.onNodeWithTag("main-navigation").performTouchInput {
            down(Offset(width * 0.1f, centerY))
            moveTo(Offset(width * 0.8f, centerY), delayMillis = 120)
            cancel()
        }
        compose.runOnIdle {
            assertEquals(0, selected.intValue)
            assertTrue("cancel must not load another page", commits.isEmpty())
        }
        compose.onNode(hasText("课程") and isSelectable()).assertIsSelected()
    }

    @Test fun secondPointerCancelsNavigationTap() {
        val commits = mutableListOf<Int>()
        val selected = navigation { commits.add(it) }
        compose.onNodeWithTag("main-navigation").performTouchInput {
            down(0, Offset(width * 0.7f, centerY))
            advanceEventTime(24)
            down(1, Offset(width * 0.3f, centerY))
            advanceEventTime(24)
            up(0)
            up(1)
        }
        compose.runOnIdle {
            assertEquals("multi-touch cancellation must retain the current page", 0, selected.intValue)
            assertTrue(commits.isEmpty())
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun segmentedControlCancelsDragAndSupportsKeyboardActivation() {
        val selected = mutableIntStateOf(0)
        compose.setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    LiquidSegmentedControl(listOf("左侧", "右侧"), selected.intValue,
                        { selected.intValue = it }, Modifier.align(Alignment.Center).testTag("segments"))
                }
            }
        }
        compose.onNodeWithTag("segments").performTouchInput {
            down(Offset(width * 0.25f, centerY))
            moveTo(Offset(width * 0.8f, centerY), delayMillis = 100)
            cancel()
        }
        compose.runOnIdle { assertEquals(0, selected.intValue) }
        compose.onNode(hasText("右侧") and isSelectable())
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNode(hasText("右侧") and isSelectable()).assertIsSelected()
    }

    @Test fun cancelledSwitchGestureDoesNotToggle() {
        var calls = 0
        compose.setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    LiquidSwitch(checked = false, onCheckedChange = { calls++ },
                        modifier = Modifier.align(Alignment.Center).testTag("switch"))
                }
            }
        }
        compose.onNodeWithTag("switch").performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.8f, centerY), delayMillis = 100)
            cancel()
        }
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun accessibilitySettingsUpdateWithoutRecreatingTheWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val originalMotion = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        val originalContrast = Settings.Secure.getString(resolver, "high_text_contrast_enabled")
        var mode: GlassAccessibilityMode? = null
        lateinit var optics: InteractiveOptics
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            Settings.Secure.putInt(resolver, "high_text_contrast_enabled", 0)
            compose.setContent {
                val current = rememberGlassAccessibilityMode()
                optics = rememberInteractiveOptics()
                SideEffect { mode = current }
                Box(Modifier.fillMaxSize().testTag("press-target")
                    .then(if (current.reduceMotion) Modifier else optics.gestureModifier))
            }
            compose.waitUntil(5000) { mode == GlassAccessibilityMode(false, false) }
            compose.onNodeWithTag("press-target").performTouchInput { down(center) }
            compose.runOnIdle { assertTrue(optics.isPressed) }
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            Settings.Secure.putInt(resolver, "high_text_contrast_enabled", 1)
            compose.waitUntil(5000) { mode == GlassAccessibilityMode(true, true) }
            compose.runOnIdle { assertFalse("removing gestures must release their state", optics.isPressed) }
            compose.onNodeWithTag("press-target").performTouchInput { cancel() }
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            Settings.Secure.putInt(resolver, "high_text_contrast_enabled", 0)
            compose.waitUntil(5000) { mode == GlassAccessibilityMode(false, false) }
        } finally {
            Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, originalMotion)
            Settings.Secure.putString(resolver, "high_text_contrast_enabled", originalContrast)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun anUnpinnedPluginHasNoIndicatorAndReturningToTabsKeepsTheLensInBounds() {
        val selected = mutableIntStateOf(-1)
        val minimized = mutableStateOf(true)
        lateinit var motion: com.tyust.course.ui.theme.NavigationMotionState
        compose.setContent {
            CourseSelectorTheme {
                motion = com.tyust.course.ui.theme.rememberNavigationMotionState(selected.intValue, "unlisted-plugin", false)
                CompositionLocalProvider(com.tyust.course.ui.theme.LocalNavigationMotion provides motion) {
                    GlassWindowHost {
                        CapsuleNavigationBar(destinations, selected.intValue, { selected.intValue = it },
                            minimized = minimized.value, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        compose.onNodeWithTag("main-navigation").assertIsDisplayed()
        compose.onNodeWithTag("navigation-selection-indicator").assertDoesNotExist()
        compose.onAllNodes(isSelected()).assertCountEquals(0)
        compose.runOnIdle { minimized.value = false }
        for (index in listOf(4, 1, 3)) {
            compose.onNodeWithTag("main-navigation").performTouchInput {
                click(Offset(width * (index + 0.5f) / destinations.size, centerY))
            }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(index, selected.intValue) }
            compose.onAllNodes(isSelected()).assertCountEquals(1)
            val bar = compose.onNodeWithTag("main-navigation").fetchSemanticsNode().boundsInRoot
            val indicator = compose.onNodeWithTag("navigation-selection-indicator").fetchSemanticsNode().boundsInRoot
            assertTrue("Selected lens must remain inside the navigation track", indicator.center.x in bar.left..bar.right)
            compose.runOnIdle { selected.intValue = -1 }
            compose.onNodeWithTag("navigation-selection-indicator").assertDoesNotExist()
            compose.onAllNodes(isSelected()).assertCountEquals(0)
            compose.runOnIdle { assertEquals(index.toFloat(), motion.position, 0.002f) }
        }
        val file = java.io.File(compose.activity.getExternalFilesDir(null), "plugin-ui/navigation-unlisted.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun thirtyRapidSelectionsEndOnLastPageWithOneSelectedTab() {
        val selected = navigation()
        compose.mainClock.autoAdvance = false
        repeat(30) { iteration ->
            val index = (iteration * 3 + 1) % destinations.size
            compose.onNodeWithTag("main-navigation").performTouchInput {
                click(Offset(width * (index + 0.5f) / destinations.size, centerY))
            }
            compose.mainClock.advanceTimeBy(32)
        }
        compose.mainClock.advanceTimeBy(2000)
        compose.runOnIdle { assertEquals(3, selected.intValue) }
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        compose.onNode(hasText("成绩") and isSelectable()).assertIsSelected()
    }

    @Test fun bottomPickerOpensUpWithoutMovingFollowingContent() {
        compose.setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
                        Spacer(Modifier.weight(1f))
                        LiquidPicker(
                            options = listOf(LiquidPickerOption("第一学期"), LiquidPickerOption("第二学期")),
                            selectedIndex = 0,
                            onSelect = {},
                            modifier = Modifier.testTag("picker-anchor")
                        )
                        Text("固定的下方内容", modifier = Modifier.testTag("following-content"))
                    }
                }
            }
        }
        val contentTop = compose.onNodeWithTag("following-content").fetchSemanticsNode().boundsInRoot.top
        val anchorTop = compose.onNodeWithTag("picker-anchor").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("picker-anchor").performTouchInput { click() }
        compose.onNodeWithText("第二学期").assertIsDisplayed()
        assertTrue("menu must fit above its low anchor",
            compose.onNodeWithText("第二学期").fetchSemanticsNode().boundsInRoot.bottom < anchorTop)
        assertEquals(contentTop,
            compose.onNodeWithTag("following-content").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("第二学期").assertIsNotDisplayed()
        assertEquals(contentTop,
            compose.onNodeWithTag("following-content").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun finishingOldDialogExitCannotCloseItsReplacement() {
        lateinit var host: DialogHostState
        var firstClosed = 0
        var secondClosed = 0
        compose.setContent {
            CourseSelectorTheme {
                GlassWindowHost { host = requireNotNull(LocalDialogHost.current) }
            }
        }
        lateinit var first: DialogHandle
        compose.runOnIdle { first = host.show({ firstClosed++ }) { Text("第一个弹窗") } }
        compose.onNodeWithText("第一个弹窗").assertIsDisplayed()
        compose.runOnIdle {
            host.dismiss(first)
            host.show({ secondClosed++ }) { Text("第二个弹窗") }
        }
        compose.onNodeWithText("第二个弹窗").assertIsDisplayed()
        compose.onNodeWithText("第一个弹窗").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, firstClosed)
            assertEquals(0, secondClosed)
            host.dismiss(first, notify = false)
        }
        compose.onNodeWithText("第二个弹窗").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, secondClosed) }
    }

    @Test fun backClosesPickerBeforeItsParentDialog() {
        var closed = 0
        compose.setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    SystemDialog(onDismissRequest = { closed++ }, title = { Text("父弹窗") }) {
                        LiquidPicker(
                            options = listOf(LiquidPickerOption("第一学期"), LiquidPickerOption("第二学期")),
                            selectedIndex = 0, onSelect = {}, modifier = Modifier.testTag("picker-anchor")
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("picker-anchor", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("第二学期").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("父弹窗").assertIsDisplayed()
        compose.onNodeWithText("第二学期").assertIsNotDisplayed()
        compose.runOnIdle { assertEquals(0, closed) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, closed) }
    }
}
