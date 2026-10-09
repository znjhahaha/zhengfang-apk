package com.tyust.course.academic.plugin

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tyust.course.ui.theme.CourseSelectorTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Uses pointer clicks while the same native node changes its prompt nonce. */
class NativeScriptControlDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun consecutivePromptsWithIdenticalLabelsDeliverTheCurrentAction() {
        val number = mutableStateOf(0)
        val busy = mutableStateOf(false)
        val clicks = mutableListOf<String>()
        compose.setContent {
            CourseSelectorTheme {
                NativePluginNode(JSONObject().put("id", "script-response-0").put("type", "button")
                    .put("label", "确定并继续").put("event", "script-response:prompt-${number.value}:confirm")
                    .put("enabled", !busy.value), null, Modifier.fillMaxWidth()) { event, _ ->
                    clicks.add(event.getString("name"))
                }
            }
        }
        repeat(12) { index ->
            compose.runOnIdle { busy.value = true; number.value = index }
            compose.onNodeWithTag("native-node-script-response-0").assertIsNotEnabled()
            compose.runOnIdle { busy.value = false }
            compose.onNodeWithTag("native-node-script-response-0").assertIsEnabled().performTouchInput { click() }
            compose.runOnIdle {
                assertEquals(index + 1, clicks.size)
                assertEquals("script-response:prompt-$index:confirm", clicks.last())
            }
        }
    }
}
