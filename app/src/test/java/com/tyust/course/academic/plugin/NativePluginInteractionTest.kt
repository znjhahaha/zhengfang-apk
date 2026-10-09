package com.tyust.course.academic.plugin

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
class NativePluginInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val pages = listOf("课程", "作业", "签到", "考试", "任务", "账号")
    private fun tree(page: String) = JSONObject().put("id", "root").put("type", "column").put("children", JSONArray(listOf(
        JSONObject().put("id", "tabs").put("type", "segmented").put("size", "compact").put("value", page).put("event", "tab")
            .put("options", JSONArray(pages.map { JSONObject().put("value", it).put("label", it) })),
        JSONObject().put("id", "list" + pages.indexOf(page)).put("type", "list").put("weight", 1).put("children",
            JSONArray((1..41).map { JSONObject().put("id", "row" + it).put("type", "text").put("text", page + "内容" + it).put("height", 56) }))
    )))

    private fun setup(fontScale: Float = 1f) {
        compose.setContent { MaterialTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                var page by remember { mutableStateOf(pages[0]) }
                Box(Modifier.fillMaxSize()) {
                    NativePluginNode(tree(page), null, Modifier.fillMaxSize()) { event, _ -> page = event.getString("value") }
                }
            }
        } }
    }

    @Test fun repeatedPhysicalTabTapsUpdateContentAndPreserveEachListPosition() {
        setup()
        repeat(3) { for (page in pages) {
            compose.onNodeWithText(page, useUnmergedTree = true).performTouchInput { click() }
            compose.onNodeWithText(page + "内容1").assertIsDisplayed()
        } }
        compose.onNodeWithText(pages[0], useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithTag("native-node-list0").performScrollToIndex(40)
        compose.onNodeWithText("课程内容41").assertIsDisplayed()
        compose.onNodeWithText("账号", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("课程", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("课程内容41").assertIsDisplayed()
    }

    @Test fun largeTextKeepsTheLastItemAndTabBarWithinTheViewport() {
        setup(1.5f)
        repeat(14) { compose.onNodeWithTag("native-node-list0").performTouchInput { swipeUp() } }
        compose.onNodeWithText("课程内容41").assertIsDisplayed()
        compose.onNodeWithTag("native-node-tabs").assertIsDisplayed()
        val tabs = compose.onNodeWithTag("native-node-tabs").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("native-node-list0").fetchSemanticsNode().boundsInRoot
        assertTrue(list.top >= tabs.bottom)
    }
}
