package com.tyust.course.academic.plugin

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JVM Compose interaction tests: no emulator, account or network is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w360dp-h640dp")
class NativeGesturePatternTest {
    @get:Rule val compose = createComposeRule()
    private val value = mutableStateOf("")
    private val enabled = mutableStateOf(true)
    private val emitted = mutableListOf<String>()

    private fun content() {
        compose.setContent { MaterialTheme {
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("outer-scroll")) {
                val node = JSONObject().put("id", "gesture").put("type", "pattern").put("label", "绘制签到手势")
                    .put("value", value.value).put("event", "gesture").put("enabled", enabled.value)
                NativePluginNode(node, null, Modifier.fillMaxWidth()) { event, gesture ->
                    assertEquals("input", event.getString("type")); assertTrue(gesture)
                    value.value = event.getString("value"); emitted += value.value
                }
                Spacer(Modifier.height(900.dp))
            }
        } }
    }

    private fun TouchInjectionScope.dot(cell: Int): Offset = PluginGesturePattern.center(cell).let { Offset(it.x * width, it.y * height) }

    @Test fun swipingDrawsThePatternWithoutAKeyboardOrSubmittingWhileMoving() {
        content()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        val pad = compose.onNodeWithTag("native-gesture-pad")
        val before = pad.fetchSemanticsNode().boundsInRoot
        pad.performTouchInput { down(dot(1)); moveTo(dot(3), 160); moveTo(dot(9), 160) }
        compose.runOnIdle { assertTrue(emitted.isEmpty()) }
        pad.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf("12369"), emitted) }
        assertEquals(before, pad.fetchSemanticsNode().boundsInRoot)
        pad.performTouchInput { down(dot(3)); moveTo(dot(1), 160); moveTo(dot(7), 160); up() }
        compose.runOnIdle { assertEquals("32147", value.value) }
        compose.onNodeWithText("清空重画").performClick()
        compose.runOnIdle { assertEquals("", value.value) }
        compose.onNodeWithText("清空重画").assertIsNotEnabled()
    }

    @Test fun cancellingAndDisablingDoNotReplaceTheLastCompletedPattern() {
        value.value = "12369"
        content()
        compose.onNodeWithTag("native-gesture-pad").performTouchInput { down(dot(9)); moveTo(dot(7), 160); cancel() }
        compose.runOnIdle { assertEquals("12369", value.value); assertTrue(emitted.isEmpty()); enabled.value = false }
        compose.onNodeWithTag("native-gesture-pad").performTouchInput { down(dot(1)); moveTo(dot(7), 160); up() }
        compose.runOnIdle { assertEquals("12369", value.value); assertTrue(emitted.isEmpty()) }
        compose.onNodeWithText("清空重画").assertIsNotEnabled()
    }

    @Test fun accessibilityActionsUseTheSamePatternAndScrollingWorksOutsideThePad() {
        content()
        for (cell in listOf(1, 3, 9)) compose.onNodeWithContentDescription("第 $cell 个点", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals("12369", value.value) }
        val before = compose.onNodeWithTag("native-gesture-pad").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("outer-scroll").performTouchInput { swipe(Offset(width - 2f, height - 20f), Offset(width - 2f, height / 2f), 300) }
        assertTrue(compose.onNodeWithTag("native-gesture-pad").fetchSemanticsNode().boundsInRoot.top < before)
    }
}
