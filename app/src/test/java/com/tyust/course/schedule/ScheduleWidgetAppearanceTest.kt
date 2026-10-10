package com.tyust.course.schedule

import android.app.Application
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleWidgetAppearanceTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun eachWidgetIdRetainsItsAppearanceAcrossReadsAndDeletion() {
        val first = ScheduleWidgetAppearance(opacity = 35, blur = 8)
        val second = ScheduleWidgetAppearance(opacity = 80, blur = 20)
        ScheduleWidgetAppearances.save(app, 31, first)
        ScheduleWidgetAppearances.save(app, 32, second)
        assertEquals(first, ScheduleWidgetAppearances.read(app.createConfigurationContext(app.resources.configuration), 31))
        assertEquals(second, ScheduleWidgetAppearances.read(app, 32))
        ScheduleWidgetAppearances.delete(app, 31)
        assertEquals(ScheduleWidgetAppearance(), ScheduleWidgetAppearances.read(app, 31))
        assertEquals(second, ScheduleWidgetAppearances.read(app, 32))
    }

    @Test fun aSharedImageIsDiscardedOnlyAfterTheLastWidgetReleasesIt() {
        val token = "a".repeat(32)
        val folder = File(app.filesDir, "widget-images/$token").apply { mkdirs() }
        ScheduleWidgetAppearances.save(app, 41, ScheduleWidgetAppearance(token))
        ScheduleWidgetAppearances.save(app, 42, ScheduleWidgetAppearance(token))
        ScheduleWidgetAppearances.delete(app, 41)
        assertTrue(folder.exists())
        ScheduleWidgetAppearances.delete(app, 42)
        assertFalse(folder.exists())
    }

    @Test fun malformedSettingsAreLocalToOneInstanceAndInvalidPathsCannotBeSaved() {
        ScheduleWidgetAppearances.save(app, 52, ScheduleWidgetAppearance(opacity = 65))
        app.getSharedPreferences(ScheduleWidgetAppearances.PREFS, 0).edit().putString("51", "broken").commit()
        assertEquals(ScheduleWidgetAppearance(), ScheduleWidgetAppearances.read(app, 51))
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleWidgetAppearances.save(app, 52, ScheduleWidgetAppearance("../other"))
        }
        assertEquals(65, ScheduleWidgetAppearances.read(app, 52).opacity)
    }

    @Test fun transparencyAffectsOnlyTheBackgroundBitmap() {
        for (dark in listOf(false, true)) {
            val transparent = ScheduleWidgetAppearances.bitmap(app, ScheduleWidgetAppearance(opacity = 0), dark)
            val opaque = ScheduleWidgetAppearances.bitmap(app, ScheduleWidgetAppearance(opacity = 100), dark)
            assertEquals(0, Color.alpha(transparent.getPixel(0, 0)))
            assertEquals(255, Color.alpha(opaque.getPixel(0, 0)))
        }
    }
}
