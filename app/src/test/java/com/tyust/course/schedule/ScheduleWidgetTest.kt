package com.tyust.course.schedule

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.tyust.course.R
import com.tyust.course.academic.AcademicStudyReader
import com.tyust.course.manager.AppThemeCoordinator
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 33], application = Application::class)
class ScheduleWidgetTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var previousZone: TimeZone
    private val now get() = SimpleDateFormat("yyyy-MM-dd HH:mm").parse("2026-09-07 08:10")!!.time
    private val base = ScheduleTimeBase("2026-09-07", mapOf(1 to "08:00", 2 to "10:00"), mapOf(1 to "08:45", 2 to "10:45"))
    private val course = ScheduleCourseRecord("network:fixture", "大学体育", "教师", "体育馆", 1, 1, 1, "1-16周")
    private fun snapshot(account: String = "a") = ScheduleSnapshot(account, "school", "2026-2027-1", listOf(course), base, 1, true)

    @Before fun setup() { previousZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai")) }
    @After fun teardown() { TimeZone.setDefault(previousZone); ScheduleWidgetNavigation.consume() }

    @Test
    @Config(sdk = [33])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun remoteViewsInflateAtBothSizesAndEveryCourseRemainsClickable() {
        val state = ScheduleWidgetState.from(snapshot().copy(courses = listOf(course, course.copy(id = "next", startPeriod = 2, endPeriod = 2))), now)
        for ((width, height) in listOf(150 to 110, 250 to 110, 280 to 110, 280 to 128, 280 to 180, 360 to 240)) {
            val view = ScheduleWidgetRenderer.views(context, state, width, height).apply(context, FrameLayout(context))
            val density = context.resources.displayMetrics.density
            view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            assertEquals("大学体育", view.findViewById<TextView>(R.id.widget_name).text.toString())
            assertEquals("正在上课", view.findViewById<TextView>(R.id.widget_status).text.toString())
            assertTrue("${width}x$height: root=${view.measuredHeight}, body=${view.findViewById<View>(R.id.widget_course).measuredHeight}, density=$density " +
                listOf(R.id.widget_heading, R.id.widget_name, R.id.widget_location, R.id.widget_time).joinToString { id ->
                    val text = view.findViewById<TextView>(id); "$id:height=${text.measuredHeight},min=${text.minHeight},font=${text.textSize},padding=${text.paddingTop}+${text.paddingBottom},line=${text.lineHeight}" },
                view.findViewById<TextView>(R.id.widget_name).measuredHeight > 0)
            assertEquals("体育馆", view.findViewById<TextView>(R.id.widget_location).text.toString())
            assertEquals("08:00–08:45", view.findViewById<TextView>(R.id.widget_time).text.toString())
            assertEquals(if (width >= 250) View.VISIBLE else View.GONE, view.findViewById<View>(R.id.widget_next).visibility)
            val fields = listOf(R.id.widget_name, R.id.widget_location, R.id.widget_time) +
                if (width >= 250) listOf(R.id.widget_next_name, R.id.widget_next_location, R.id.widget_next_time) else emptyList()
            for (id in fields) {
                val text = view.findViewById<TextView>(id)
                assertTrue("$width x $height clipped ${context.resources.getResourceEntryName(id)}: " +
                    "height=${text.height}, line=${text.lineHeight}, layout=${text.layout?.height}, " +
                    "bottom=${text.bottom}, parent=${(text.parent as View).height}",
                    text.height >= text.lineHeight && text.bottom <= (text.parent as View).height)
            }
            assertTrue(view.findViewById<View>(R.id.widget_course).performClick())
            val intent = shadowOf(context as Application).nextStartedActivity
            ScheduleWidgetNavigation.accept(intent)
            assertEquals(course.id, ScheduleWidgetNavigation.requested?.course)
            assertTrue(ScheduleWidgetNavigation.requested!!.matches("a", "school"))
            assertFalse(ScheduleWidgetNavigation.requested!!.matches("b", "school"))
            assertEquals(ScheduleWidgetAction.Course, ScheduleWidgetNavigation.requested?.action)
            if (width >= 250) {
                assertEquals("体育馆", view.findViewById<TextView>(R.id.widget_next_location).text.toString())
                assertEquals("10:00–10:45", view.findViewById<TextView>(R.id.widget_next_time).text.toString())
                view.findViewById<View>(R.id.widget_next).performClick()
                ScheduleWidgetNavigation.accept(shadowOf(context as Application).nextStartedActivity)
                assertEquals("next", ScheduleWidgetNavigation.requested?.course)
            }
            view.findViewById<View>(R.id.widget_header).performClick()
            ScheduleWidgetNavigation.accept(shadowOf(context as Application).nextStartedActivity)
            assertNull(ScheduleWidgetNavigation.requested?.course)
        }
    }

    @Test fun oldWidgetClicksCannotOpenADifferentAccountsCourseAndThemeChangesKeepReadableText() {
        val old = ScheduleWidgetRenderer.views(context, ScheduleWidgetState.from(snapshot("a"), now)).apply(context, FrameLayout(context))
        context.getSharedPreferences(AppThemeCoordinator.PREFS, 0).edit().putString(AppThemeCoordinator.KEY, "dark").apply()
        val current = ScheduleWidgetRenderer.views(context, ScheduleWidgetState.from(snapshot("b"), now)).apply(context, FrameLayout(context))
        assertNotEquals(old.findViewById<TextView>(R.id.widget_name).currentTextColor, current.findViewById<TextView>(R.id.widget_name).currentTextColor)
        old.findViewById<View>(R.id.widget_course).performClick()
        ScheduleWidgetNavigation.accept(shadowOf(context as Application).nextStartedActivity)
        assertFalse(ScheduleWidgetNavigation.requested!!.matches("b", "school"))
    }

    private fun assertCompleteLabel(root: ViewGroup, id: Int, label: String) {
        val text = root.findViewById<TextView>(id)
        assertEquals(label, text.text.toString())
        assertEquals(View.VISIBLE, text.visibility)
        val layout = text.layout
        assertNotNull(layout)
        assertEquals("End of $label was hidden", label.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("$label was ellipsized", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
        assertTrue("$label text layout was clipped: ${layout.height} > ${text.height}", layout.height <= text.height)
        val bounds = Rect(); text.getDrawingRect(bounds); root.offsetDescendantRectToMyCoords(text, bounds)
        assertTrue("$label outside widget: $bounds in ${root.width}x${root.height}", bounds.top >= 0 && bounds.bottom <= root.height && bounds.left >= 0 && bounds.right <= root.width)
    }

    @Test @Config(sdk = [28, 33]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactWidgetsShowTheWholeCourseAndRoomAtDifferentFontScales() {
        val primary = course.copy(name = "习近平新时代中国特色社会主义思想概论", location = "河东校区电教楼A座502多媒体教室")
        val secondary = course.copy(id = "next", name = "高等数学 A1（理工类）", location = "河西校区综合实验楼B座1208教室", startPeriod = 2, endPeriod = 2)
        val state = ScheduleWidgetState.from(snapshot().copy(courses = listOf(primary, secondary)), now)
        for (fontScale in listOf(1f, 1.3f, 1.8f)) {
            val config = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
            val scaled = context.createConfigurationContext(config)
            for (style in ScheduleWidgetStyle.entries) for ((width, height) in listOf(150 to 110, 250 to 110, 280 to 128)) {
                val root = ScheduleWidgetRenderer.views(scaled, state, width, height, style).apply(scaled, FrameLayout(scaled)) as ViewGroup
                val density = scaled.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                assertCompleteLabel(root, R.id.widget_name, primary.name)
                assertCompleteLabel(root, R.id.widget_location, primary.location)
                if (root.findViewById<View>(R.id.widget_next).visibility == View.VISIBLE) {
                    assertCompleteLabel(root, R.id.widget_next_name, secondary.name)
                    assertCompleteLabel(root, R.id.widget_next_location, secondary.location)
                }
                if (fontScale == 1f && style != ScheduleWidgetStyle.Timeline && width != 280) {
                    val file = File("build/reports/widget-layout/${style.name.lowercase()}-${width}x$height.png")
                    file.parentFile.mkdirs()
                    val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(image)); file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                }
            }
        }
    }

    @Test @Config(sdk = [33]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun timelineFitsCompleteRowsAndRetainsTheCurrentCourse() {
        val primary = course.copy(name = "中国特色社会主义理论与实践研究", location = "河东校区电教楼A座502多媒体教室")
        val secondary = course.copy(id = "next", name = "高等数学 A1（理工类）", location = "河西校区综合实验楼B座1208教室", startPeriod = 2, endPeriod = 2)
        val state = ScheduleWidgetState.from(snapshot().copy(courses = listOf(primary, secondary)), now)
        for ((width, height) in listOf(200 to 170, 250 to 220, 360 to 240)) {
            val root = ScheduleWidgetRenderer.views(context, state, width, height, ScheduleWidgetStyle.Timeline).apply(context, FrameLayout(context)) as ViewGroup
            val density = context.resources.displayMetrics.density
            root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val rows = root.findViewById<ViewGroup>(R.id.widget_timeline_rows)
            assertTrue("Current class must stay visible", rows.childCount > 0)
            assertCompleteLabel(root, R.id.widget_name, primary.name)
            assertCompleteLabel(root, R.id.widget_location, primary.location)
            for (index in 0 until rows.childCount) {
                val row = rows.getChildAt(index) as ViewGroup
                assertTrue("Timeline row clipped", row.bottom <= rows.height)
                for (id in listOf(R.id.widget_name, R.id.widget_location)) assertCompleteLabel(row, id, row.findViewById<TextView>(id).text.toString())
            }
        }
    }

    @Test fun offlineEmptyMissingCalendarAndLoggedOutStatesAreDistinct() {
        assertEquals("登录后查看课表", ScheduleWidgetState.from(null, now).title)
        assertEquals("还没有本地课表", ScheduleWidgetState.from(snapshot().copy(hasCache = false, courses = emptyList()), now).title)
        assertEquals("请设置开学日期", ScheduleWidgetState.from(snapshot().copy(timeBase = ScheduleTimeBase()), now).title)
        assertEquals("今天没有课程", ScheduleWidgetState.from(snapshot().copy(courses = emptyList()), now).title)
        assertEquals(course.id, ScheduleWidgetState.from(snapshot(), now).primary?.course?.id)
    }

    @Test fun idleWidgetsShowTheNextTwoOccurrencesWithExplicitCivilDates() {
        val idle = SimpleDateFormat("yyyy-MM-dd HH:mm").parse("2026-09-07 11:00")!!.time
        val next = course.copy(id = "tomorrow", day = 2)
        val later = course.copy(id = "later", day = 4)
        val state = ScheduleWidgetState.from(snapshot().copy(courses = listOf(course, next, later)), idle)
        assertEquals("下一节", state.primary?.status)
        assertEquals("随后", state.secondary?.status)
        assertEquals("明天", state.primary?.dateLabel)
        assertEquals("9/10", state.secondary?.dateLabel)
        assertEquals("今日已结束", state.heading)
        assertEquals("tomorrow", state.primary?.course?.id)
        val unknown = next.copy(weeks = "待安排")
        assertEquals("later", ScheduleWidgetState.from(snapshot().copy(courses = listOf(unknown, later)), idle).primary?.course?.id)
        assertNull(ScheduleWidgetState.from(snapshot().copy(courses = listOf(unknown)), idle).primary)
    }

    @Test fun emptyStateButtonsCarryOneSpecificActionAndTheAccountBoundary() {
        val cases = listOf(null to ScheduleWidgetAction.Login,
            snapshot().copy(hasCache = false, courses = emptyList()) to ScheduleWidgetAction.Sync,
            snapshot().copy(timeBase = ScheduleTimeBase()) to ScheduleWidgetAction.Calendar,
            snapshot().copy(courses = emptyList()) to ScheduleWidgetAction.Today)
        for ((snapshot, expected) in cases) {
            val state = ScheduleWidgetState.from(snapshot, now)
            assertNotNull(state.message)
            val view = ScheduleWidgetRenderer.views(context, state).apply(context, FrameLayout(context))
            assertEquals(View.GONE, view.findViewById<View>(R.id.widget_courses).visibility)
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_empty).visibility)
            view.findViewById<View>(R.id.widget_action).performClick()
            ScheduleWidgetNavigation.accept(shadowOf(context as Application).nextStartedActivity)
            assertEquals(expected, ScheduleWidgetNavigation.requested?.action)
            assertTrue(ScheduleWidgetNavigation.requested!!.matches("a", "school"))
            if (snapshot != null) assertFalse(ScheduleWidgetNavigation.requested!!.matches("b", "school"))
        }
    }

    @Test fun repositoryReadsOnlyTheChosenAccountAndMigratesLegacyCalendarToCurrentTerm() {
        val term = AcademicStudyReader.calendarTerm()
        val prefs = context.getSharedPreferences("schedule_cache", 0)
        val cache = ScheduleCacheStore(prefs)
        val json = """{"kbList":[{"kcmc":"大学体育","xqj":1,"jcs":"1-2","zcd":"1-16周"}]}"""
        cache.save("a", "school", CachedSchedule(term, term, json, false))
        val settingsPrefs = context.getSharedPreferences("schedule_settings", 0)
        settingsPrefs.edit().putLong("semester_start_a", now).apply()
        val settings = ScheduleSettingsManager(settingsPrefs)
        settings.addCustomCourse(ScheduleSettingsManager.CustomCourse("mine", "自习", "", "", 2, 1, 2, "1-16周"), "a")
        val repo = ScheduleRepository(context, settings)
        val first = repo.snapshot("a", "school")
        assertTrue(first.hasCache)
        assertEquals(2, first.courses.size)
        assertEquals("2026-09-07", first.timeBase.firstWeekDate)
        assertFalse(repo.snapshot("b", "school").hasCache)
        assertTrue(repo.snapshot("b", "school").courses.isEmpty())
        assertEquals("", repo.snapshot("a", "school", term.next().id).timeBase.firstWeekDate)
    }

    @Test fun threeWidgetStylesKeepIndependentProvidersAndOnlyShowTodayOnTheTimeline() {
        assertEquals(3, ScheduleWidgetStyle.entries.map { it.provider }.distinct().size)
        val today = course.copy(id = "today", startPeriod = 2, endPeriod = 2)
        val tomorrow = today.copy(id = "tomorrow", day = 2)
        val state = ScheduleWidgetState.from(snapshot().copy(courses = listOf(course, today, tomorrow)), now)
        assertEquals(listOf(course.id, today.id), ScheduleWidgetRenderer.timelineItems(state, 240, 1f).map { it.course.id })
        val noClassesToday = ScheduleWidgetState.from(snapshot().copy(courses = listOf(tomorrow)), now)
        assertTrue(ScheduleWidgetRenderer.timelineItems(noClassesToday, 240, 1f).isEmpty())
    }

    @Test @Config(sdk = [33]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun everyStyleRendersAtSeveralSizesAndTimelineCoursesOpenTheirOwnDetails() {
        val state = ScheduleWidgetState.from(snapshot().copy(courses = listOf(course, course.copy(id = "next", startPeriod = 2, endPeriod = 2))), now)
        for (style in ScheduleWidgetStyle.entries) {
            for ((width, height) in listOf(150 to 110, 280 to 180, 360 to 240)) {
                val view = ScheduleWidgetRenderer.views(context, state, width, height, style).apply(context, FrameLayout(context))
                assertTrue(view.findViewById<View>(R.id.widget_header).isClickable)
                assertTrue(view.findViewById<View>(R.id.widget_course).performClick())
                ScheduleWidgetNavigation.accept(shadowOf(context as Application).nextStartedActivity)
                assertEquals(course.id, ScheduleWidgetNavigation.requested?.course)
            }
        }
    }

    @Test fun accountSwitchExpiryRemovalAndLogoutRefreshEligibilityWithoutLoggingIn() {
        val user = UserManager.getInstance().apply { init(context); clearLoginState() }
        val school = SchoolConfig("widget-fixture-school", "测试学校", "fixture.invalid", "https")
        user.addCustomSchool(school)
        user.currentSchool = school
        user.studentId = "fixture-a"
        user.saveCookieLogin("SESSION=fixture-a")
        val accountA = user.currentAccountKey
        val storageA = user.currentAccountStorageKey
        assertEquals(storageA, ScheduleWidgetUpdater.activeSnapshot(context)?.account)
        user.clearLoginState()
        assertNull(ScheduleWidgetUpdater.activeSnapshot(context))
        user.currentSchool = school
        user.studentId = "fixture-b"
        user.saveCookieLogin("SESSION=fixture-b")
        val storageB = user.currentAccountStorageKey
        assertNotEquals(storageA, storageB)
        assertEquals(storageB, ScheduleWidgetUpdater.activeSnapshot(context)?.account)
        user.sessionState.expire(user.sessionState.token)
        assertEquals(storageB, ScheduleWidgetUpdater.activeSnapshot(context)?.account)
        user.deleteAccount(user.currentAccountKey)
        assertNull(ScheduleWidgetUpdater.activeSnapshot(context))
        assertTrue(user.switchToAccount(accountA))
        assertEquals(storageA, ScheduleWidgetUpdater.activeSnapshot(context)?.account)
        user.logout()
        assertNull(ScheduleWidgetUpdater.activeSnapshot(context))
    }
}
