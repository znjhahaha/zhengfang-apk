package com.tyust.course.ui.system

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.kyant.backdrop.Backdrop
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production source ownership check; the red child stands for an AndroidView subtree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33], application=Application::class)
class GlassWebCaptureTest {
    @get:Rule val compose=createComposeRule()
    @Test fun webWindowChromeUsesWallpaperAndNeverItsPageSource() {
        var capture by mutableStateOf(false)
        var wallpaper: Backdrop?=null
        var modal: Backdrop?=null
        compose.setContent { MaterialTheme { GlassWindowHost(capturePage=capture) {
            val a=LocalAppBackdrop.current;val b=LocalModalBackdrop.current
            SideEffect {wallpaper=a;modal=b}
            Box(Modifier.fillMaxSize().background(Color.Red))
        } } }
        compose.runOnIdle {assertNotNull(wallpaper);assertSame(wallpaper,modal)}
        compose.runOnIdle {capture=true};compose.waitForIdle()
        compose.runOnIdle {assertNotNull(modal);assertNotSame(wallpaper,modal)}
        compose.runOnIdle {capture=false};compose.waitForIdle()
        compose.runOnIdle {assertSame(wallpaper,modal)}
    }
}
