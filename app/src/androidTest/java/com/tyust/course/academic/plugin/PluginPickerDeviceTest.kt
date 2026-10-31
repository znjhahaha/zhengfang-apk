package com.tyust.course.academic.plugin

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import com.tyust.course.ui.system.GlassWindowHost
import com.tyust.course.ui.theme.CourseSelectorTheme
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose/Android confirmation, including dates outside the scheduling window. */
@RunWith(AndroidJUnit4::class)
class PluginPickerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun previewOnly() { assumeTrue(BuildConfig.UI_PREVIEW) }

    private fun confirm(kind: String, input: JSONObject, button: String = "确定"): JSONObject {
        val prompt = PluginPickerPrompt(kind, input)
        val interaction = PageInteraction().apply { pickerPrompt = prompt }
        compose.setContent { CourseSelectorTheme { GlassWindowHost { NativePluginPickerPrompts(interaction) } } }
        compose.onNodeWithText(button).performClick()
        compose.waitUntil(5_000) { prompt.result.isCompleted }
        val result = prompt.result.getCompleted()!!
        val descriptors = JSONArray(compose.activity.assets.open("academic-plugin/host-capabilities.json").bufferedReader().use { it.readText() })
        val descriptor = PluginJson.objects(descriptors).single { it.getString("name") == kind }
        PluginContractCache.schema(compose.activity).validate(result, descriptor.getJSONObject("output"))
        return result
    }

    @Test fun historicalDateIsNotChangedOnConfirmation() {
        assertEquals("2000-02-29", confirm("ui.date.pick", JSONObject().put("initial", "2000-02-29")).getString("date"))
    }
    @Test fun preEpochDateAlsoPassesTheHostOutputSchema() {
        val result = confirm("ui.date.pick", JSONObject().put("initial", "1969-12-31"))
        assertEquals("1969-12-31", result.getString("date"))
        org.junit.Assert.assertTrue(result.getLong("millis") < 0)
    }
    @Test fun distantDateTimeIsNotChangedOnConfirmation() {
        assertEquals("2099-12-31 23:59", confirm("ui.datetime.pick", JSONObject().put("initial", "2099-12-31 23:59")).getString("datetime"))
    }
    @Test fun offStepTimeIsNotRoundedOnConfirmation() {
        assertEquals("08:07", confirm("ui.time.pick", JSONObject().put("initial", "08:07").put("minuteStep", 5)).getString("time"))
    }
    @Test fun offStepRangeIsNotRoundedOnConfirmation() {
        val result = confirm("ui.time.range.pick", JSONObject().put("initialStart", "08:07").put("initialEnd", "09:13"), "保存")
        assertEquals("08:07", result.getString("start"))
        assertEquals("09:13", result.getString("end"))
    }
}
