package com.tyust.course.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Long forms share a full page and keep their commands above the keyboard. */
@Composable
internal fun SystemFormPage(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassSubpage(onDismissRequest) { close ->
        GlassPageScaffold(title = title, onBack = close, bottomBar = {
            Box(Modifier.fillMaxWidth().background(LocalWallpaperAppearanceColors.current.solidSurface)
                .imePadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                Row(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = PagePadding, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    dismissButton?.let { Box(Modifier.weight(1f)) { it() } }
                    Box(Modifier.weight(1f)) { confirmButton() }
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = PagePadding, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }
}
