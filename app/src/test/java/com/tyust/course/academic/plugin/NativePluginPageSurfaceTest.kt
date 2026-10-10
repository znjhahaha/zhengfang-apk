package com.tyust.course.academic.plugin

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w360dp-h640dp")
class NativePluginPageSurfaceTest {
    @get:Rule val compose = createComposeRule()

    private fun page(name: String, depth: Int) = NativeUiSnapshot(
        instance = "session", navigation = NativePageNavigation(name, name, depth, if (depth > 0) "back" else null),
        view = JSONObject().put("id", "items").put("type", "list").put("weight", 1).put("children",
            JSONArray((1..60).map { JSONObject().put("id", "row$it").put("type", "text").put("height", 56).put("text", "$name $it") }))
    )

    @Test fun animatedPagesCanReuseNodeIdsAndBackRestoresTheParentScrollPosition() {
        val snapshot = mutableStateOf(page("parent", 0))
        val events = mutableListOf<JSONObject>()
        val files = NativePluginFiles(ApplicationProvider.getApplicationContext(), "navigation-test")
        compose.setContent { MaterialTheme {
            NativePluginPageSurface(snapshot.value, files, "Plugin", false, Modifier.fillMaxSize(),
                onExit = { error("Back from a child must stay inside the plugin") }, onRetry = {}, emit = { event, _ ->
                    events += event
                    snapshot.value = page("parent", 0)
                })
        } }
        compose.onNodeWithTag("native-node-items").performScrollToIndex(50)
        compose.onNodeWithText("parent 51").assertIsDisplayed()
        compose.runOnIdle { snapshot.value = page("child", 1) }
        compose.onNodeWithText("child 1").assertIsDisplayed()
        compose.onNodeWithTag("native-node-items").performScrollToIndex(30)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("parent 51").assertIsDisplayed()
        assertEquals(1, events.size)
        assertEquals("back", events.single().getString("name"))
        assertEquals("click", events.single().getString("type"))
        assertFalse(events.single().has("nodeId"))
        compose.runOnIdle { snapshot.value = page("child", 1) }
        compose.onNodeWithText("child 31").assertIsDisplayed()
    }
}
