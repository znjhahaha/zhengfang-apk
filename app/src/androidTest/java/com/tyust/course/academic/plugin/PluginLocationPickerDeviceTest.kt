package com.tyust.course.academic.plugin

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.tyust.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual native map surface; a renderer crash must fail this test. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class PluginLocationPickerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun requestedChineseAddressSearchSelectsARealResultWithoutCoordinateInput() = runBlocking {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("chaoxingLiveLocationSearch") == "true")
        val prompt = PluginVisualPrompt("device.location.pick", JSONObject().put("query", "太原市万柏林区和平街道南社街"), emptyList())
        compose.setContent { CourseSelectorTheme { LocationPrompt(prompt) } }
        compose.onNodeWithTag("location-search-input").assertTextContains("太原市万柏林区和平街道南社街")
        compose.onNodeWithText("搜索", useUnmergedTree = true).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("location-search-result-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("南社街", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("太原市", substring = true) and hasAnyAncestor(hasTestTag("location-search-result-0")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("location-search-result-0").performClick()
        compose.onNodeWithText("使用此地点").performClick()
        compose.waitUntil(5_000) { prompt.result.isCompleted }
        val selected = requireNotNull(prompt.result.await())
        assertTrue(selected.getString("address").contains("南社街"))
        assertTrue(selected.getString("address").contains("太原市"))
        assertTrue(selected.getDouble("latitude") in 37.0..38.0)
        assertTrue(selected.getDouble("longitude") in 112.0..113.0)
        assertEquals("WGS84", selected.getString("coordinateSystem"))
    }

    @Test fun mapCanSelectCloseAndReopenWithoutLosingTheSavedPoint() = runBlocking {
        fun prompt(initial: JSONObject) = PluginVisualPrompt("device.location.pick", JSONObject().put("initial", initial), emptyList())
        val first = prompt(JSONObject().put("latitude", 37.85).put("longitude", 112.5)
            .put("address", "合成选点测试").put("coordinateSystem", "WGS84"))
        val visible = mutableStateOf<PluginVisualPrompt?>(first)
        compose.setContent { CourseSelectorTheme { visible.value?.let { LocationPrompt(it) } } }

        fun awaitMap() {
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("双指缩放地图，点击放置标记").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("location-map").performScrollTo().assertIsDisplayed()
        }
        awaitMap()
        compose.onNodeWithTag("location-map").performTouchInput { click(Offset(width * .7f, height * .5f)) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("已选中地点，可继续拖动地图或点击微调").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("使用此地点").performClick()
        compose.waitUntil(5_000) { first.result.isCompleted }
        val selected = requireNotNull(first.result.await())
        assertEquals("WGS84", selected.getString("coordinateSystem"))
        assertTrue(selected.getDouble("latitude") in -90.0..90.0)
        assertTrue(selected.getDouble("longitude") > 112.5)
        compose.runOnIdle { visible.value = null }
        compose.waitForIdle()

        val reopened = prompt(selected)
        compose.runOnIdle { visible.value = reopened }
        awaitMap()
        compose.onNodeWithText("使用此地点").performClick()
        compose.waitUntil(5_000) { reopened.result.isCompleted }
        val unchanged = requireNotNull(reopened.result.await())
        assertEquals(selected.getDouble("latitude"), unchanged.getDouble("latitude"), 1e-7)
        assertEquals(selected.getDouble("longitude"), unchanged.getDouble("longitude"), 1e-7)
        compose.runOnIdle { visible.value = null }
    }
}
