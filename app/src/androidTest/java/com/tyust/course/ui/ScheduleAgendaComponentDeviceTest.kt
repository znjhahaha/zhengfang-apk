package com.tyust.course.ui

import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import com.tyust.course.R
import com.tyust.course.schedule.*
import com.tyust.course.ui.screen.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import java.io.File
import java.text.SimpleDateFormat
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleAgendaComponentDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun isolatedPreviewOnly() = assumeTrue(BuildConfig.UI_PREVIEW)

    @Test fun overlappingCoursesCanBeChosenIndividuallyAndWeekendVisibilityIsLocalToTheGrid() {
        var chosen: String? = null
        val weekend = mutableStateOf(true)
        val courses = listOf(
            ScheduleCourseUi("大学体育", "教师甲", "体育馆", 1, 1, 2, "1-16周", Color.Blue, id = "pe"),
            ScheduleCourseUi("选修研讨", "教师乙", "博学楼 203", 1, 2, 3, "1-16周", Color.Cyan, id = "seminar"),
            ScheduleCourseUi("周末自习", "", "图书馆", 6, 1, 2, "1-16周", Color.Green, id = "weekend")
        )
        compose.setContent {
            CourseSelectorTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("agenda-preview")) {
                    ScheduleGrid(courses, 2, periodCount = 4, showWeekend = weekend.value,
                        onCourseClick = { chosen = it.id })
                }
            }
        }
        compose.onNodeWithText("2 门重叠 · 点按查看").performClick()
        compose.onNodeWithText("重叠课程").assertIsDisplayed()
        compose.onNodeWithTag("schedule-day-course-seminar").performClick()
        compose.runOnIdle { assertEquals("seminar", chosen); weekend.value = false }
        compose.onNodeWithTag("schedule-course-weekend").assertDoesNotExist()
        compose.onNodeWithText("2 门重叠 · 点按查看").performClick()
        compose.onNodeWithTag("schedule-day-course-pe").performClick()
        compose.runOnIdle { assertEquals("pe", chosen) }
        capture("agenda-overlap")
    }

    @Test fun nativeWidgetViewsRemainReadableAtCompactAndExpandedSizes() {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm").parse("2026-09-07 08:10")!!.time
        val base = ScheduleTimeBase("2026-09-07", mapOf(1 to "08:00", 2 to "10:00"), mapOf(1 to "08:45", 2 to "10:45"))
        val first = ScheduleCourseRecord("pe", "大学体育", "教师", "东区体育馆", 1, 1, 1, "1-16周")
        val state = ScheduleWidgetState.from(ScheduleSnapshot("fixture", "fixture", "2026-2027-1",
            listOf(first, first.copy(id = "next", name = "计算机网络", startPeriod = 2, endPeriod = 2)), base, now, true), now)
        val dimensions = mutableStateOf(280 to 128)
        val fontScale = mutableFloatStateOf(1f)
        var rendered: View? = null
        compose.setContent {
            CourseSelectorTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp).testTag("agenda-preview")) {
                    val (width, height) = dimensions.value
                    key(width, height, fontScale.floatValue) {
                        AndroidView(factory = { original ->
                            val context = original.createConfigurationContext(android.content.res.Configuration(original.resources.configuration)
                                .apply { this.fontScale = fontScale.floatValue })
                            ScheduleWidgetRenderer.views(context, state, width, height).apply(context, FrameLayout(context)).also { rendered = it }
                        }, modifier = Modifier.size(width.dp, height.dp))
                    }
                }
            }
        }
        for ((size, font) in listOf((250 to 110) to 1f, (280 to 110) to 1f, (280 to 128) to 1f, (280 to 180) to 1f,
            (150 to 110) to 1f, (360 to 240) to 1f, (280 to 180) to 1.6f, (150 to 110) to 1.6f,
            (280 to 110) to 1.6f, (150 to 110) to 2f)) {
            compose.runOnIdle { dimensions.value = size; fontScale.floatValue = font }
            compose.waitForIdle()
            compose.runOnIdle {
                val view = requireNotNull(rendered)
                val title = view.findViewById<TextView>(R.id.widget_name)
                assertEquals("大学体育", title.text.toString())
                assertTrue(title.height >= title.lineHeight)
                assertTrue(view.findViewById<View>(R.id.widget_header).isClickable)
                assertTrue(view.findViewById<View>(R.id.widget_course).isClickable)
                val two = size.first >= 250 && size.second >= 110 * font
                assertEquals(if (two) View.VISIBLE else View.GONE, view.findViewById<View>(R.id.widget_next).visibility)
                val fields = listOf(R.id.widget_name, R.id.widget_location, R.id.widget_time) +
                    if (two) listOf(R.id.widget_next_name, R.id.widget_next_location, R.id.widget_next_time) else emptyList()
                for (id in fields) {
                    val field = view.findViewById<TextView>(id)
                    assertTrue("Clipped widget field $id at $size/font$font: height=${field.height}, line=${field.lineHeight}, bottom=${field.bottom}, parent=${(field.parent as View).height}",
                        field.height >= field.lineHeight && field.bottom <= (field.parent as View).height)
                }
                assertEquals("东区体育馆", view.findViewById<TextView>(R.id.widget_location).text.toString())
                if (two) {
                    assertEquals("计算机网络", view.findViewById<TextView>(R.id.widget_next_name).text.toString())
                    assertEquals("东区体育馆", view.findViewById<TextView>(R.id.widget_next_location).text.toString())
                }
            }
            capture("widget-${size.first}-${size.second}-font$font")
        }
    }

    @Test fun allWidgetStylesFitSmallSizesLargeFontsAndBothThemes() {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm").parse("2026-09-07 08:10")!!.time
        val base = ScheduleTimeBase("2026-09-07", mapOf(1 to "08:00", 2 to "10:00"), mapOf(1 to "08:45", 2 to "10:45"))
        val first = ScheduleCourseRecord("first", "高等数学", "", "博学楼 A205", 1, 1, 1, "1-16周")
        val state = ScheduleWidgetState.from(ScheduleSnapshot("fixture", "fixture", "2026-2027-1",
            listOf(first, first.copy(id = "next", name = "计算机网络", startPeriod = 2, endPeriod = 2)), base, now, true), now)
        var style by mutableStateOf(ScheduleWidgetStyle.Single)
        var size by mutableStateOf(280 to 180)
        var font by mutableFloatStateOf(1f)
        var dark by mutableStateOf(false)
        var rendered: View? = null
        val appearance = com.tyust.course.manager.AppearanceSettingsManager
        val previous = appearance.themeMode
        try {
            compose.setContent {
                CourseSelectorTheme {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp).testTag("agenda-preview")) {
                        key(style, size, font, dark) {
                            AndroidView(factory = { original ->
                                val context = original.createConfigurationContext(android.content.res.Configuration(original.resources.configuration)
                                    .apply { fontScale = font })
                                ScheduleWidgetRenderer.views(context, state, size.first, size.second, style).apply(context, FrameLayout(context)).also { rendered = it }
                            }, modifier = Modifier.size(size.first.dp, size.second.dp))
                        }
                    }
                }
            }
            for (night in listOf(false, true)) for (kind in ScheduleWidgetStyle.entries) {
                for ((dimensions, scale) in listOf((280 to 240) to 1f, (150 to 110) to 2f, (360 to 360) to 1.6f)) {
                    compose.runOnIdle {
                        appearance.receiveThemeMode(if (night) com.tyust.course.manager.AppThemeMode.Dark else com.tyust.course.manager.AppThemeMode.Light)
                        dark = night; style = kind; size = dimensions; font = scale
                    }
                    compose.waitForIdle()
                    compose.runOnIdle {
                        fun check(view: View) {
                            if (view.visibility != View.VISIBLE) return
                            if (view is TextView && view.text.isNotEmpty()) {
                                assertTrue("Clipped ${view.text} in $kind at $dimensions/$scale", view.height >= view.lineHeight)
                                assertTrue("Overflow ${view.text} in $kind", view.bottom <= (view.parent as View).height)
                            }
                            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) check(view.getChildAt(i))
                        }
                        check(requireNotNull(rendered))
                        assertTrue(rendered!!.findViewById<View>(R.id.widget_course).isClickable)
                    }
                    capture("style-${kind.name}-${if (night) "dark" else "light"}-${dimensions.first}-${dimensions.second}-font$scale")
                }
            }
        } finally { compose.runOnIdle { appearance.receiveThemeMode(previous) } }
    }

    private fun capture(name: String) {
        val image = compose.onNodeWithTag("agenda-preview").captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.getExternalFilesDir(null), "flow-validation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
