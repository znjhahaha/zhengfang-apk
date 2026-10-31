package com.tyust.course.academic.plugin

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tyust.course.ui.system.GlassOverlayHost
import com.tyust.course.ui.system.GlassTimePickerDialog
import com.tyust.course.ui.system.GlassDateTimePickerDialog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PluginPickerInitialValueTest {
    @get:Rule val compose = createComposeRule()
    private val originalZone = TimeZone.getDefault()
    @After fun resetZone() { TimeZone.setDefault(originalZone) }
    private fun confirm(kind: String, input: JSONObject, button: String = "确定"): JSONObject {
        val prompt = PluginPickerPrompt(kind, input)
        val state = PageInteraction().apply { pickerPrompt = prompt }
        compose.setContent { MaterialTheme { GlassOverlayHost { NativePluginPickerPrompts(state) } } }
        compose.onNodeWithText(button).performClick()
        return prompt.result.getCompleted()!!
    }
    @Test fun historicalDateSurvivesConfirmWithoutEditing() {
        assertEquals("2000-02-29", confirm("ui.date.pick", JSONObject().put("initial", "2000-02-29")).getString("date"))
    }
    @Test fun futureDateSurvivesConfirmWithoutEditing() {
        assertEquals("2099-12-31", confirm("ui.date.pick", JSONObject().put("initial", "2099-12-31")).getString("date"))
    }
    @Test fun historicalDateTimeSurvivesConfirmWithoutEditing() {
        assertEquals("2000-02-29 08:07", confirm("ui.datetime.pick", JSONObject().put("initial", "2000-02-29 08:07")).getString("datetime"))
    }
    @Test fun distantDateTimeSurvivesConfirmWithoutEditing() {
        assertEquals("2099-12-31 23:59", confirm("ui.datetime.pick", JSONObject().put("initial", "2099-12-31 23:59")).getString("datetime"))
    }
    @Test fun offStepTimeSurvivesConfirmWithoutEditing() {
        assertEquals("08:07", confirm("ui.time.pick", JSONObject().put("initial", "08:07").put("minuteStep", 5)).getString("time"))
    }
    @Test fun offStepTimeRangeSurvivesConfirmWithoutEditing() {
        val result = confirm("ui.time.range.pick", JSONObject().put("initialStart", "08:07").put("initialEnd", "09:13"), "保存")
        assertEquals("08:07", result.getString("start"))
        assertEquals("09:13", result.getString("end"))
    }
    @Test fun dateTimeAcrossDaylightSavingKeepsTheSuppliedCalendarDay() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertEquals("2027-03-15 08:07", confirm("ui.datetime.pick", JSONObject().put("initial", "2027-03-15 08:07")).getString("datetime"))
    }
    @Test fun existingSchedulingTimePickerStillUsesFiveMinuteStepsByDefault() {
        var result: String? = null
        compose.setContent { MaterialTheme { GlassOverlayHost {
            GlassTimePickerDialog(initialTime = "08:07", onConfirm = { result = it }, onDismiss = {})
        } } }
        compose.onNodeWithText("确定").performClick()
        assertEquals("08:05", result)
    }
    @Test fun existingSchedulingDateTimePickerStillRestrictsDatesToTheNextSixtyDays() {
        var result: Long? = null
        val initial = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("2099-12-31 08:07")!!.time
        val expected = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 59)
            set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 7); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        compose.setContent { MaterialTheme { GlassOverlayHost {
            GlassDateTimePickerDialog(initialMillis = initial, onConfirm = { result = it }, onDismiss = {})
        } } }
        compose.onNodeWithText("确定").performClick()
        assertEquals(expected, result)
    }
}
