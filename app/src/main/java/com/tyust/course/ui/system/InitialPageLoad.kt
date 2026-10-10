package com.tyust.course.ui.system

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.ui.theme.LocalModuleEntrance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class PageReadiness { var ready by mutableStateOf(false) }
private val LocalPageReadiness = compositionLocalOf<PageReadiness?> { null }

/** Terminal empty/error states count as ready. Unrelated background queries do not delay entry. */
@Composable
fun ReportInitialPageReady(ready: Boolean = true) {
    val state = LocalPageReadiness.current
    SideEffect { if (ready) state?.ready = true }
}

/** Paint the destination immediately; initialize off-thread, then mount its content exactly once. */
@Composable
fun InitialPageLoad(key: String, title: String, active: Boolean, transitionFinished: Boolean,
    prepare: suspend () -> Unit, awaitContent: Boolean = false, route: String = key.substringBefore(':'),
    content: @Composable () -> Unit) {
    val store = LocalPageDataState.current
    var entered by rememberPageData("page-entry:$key") { false }
    val retainedEntry = remember(store, key) { entered }
    var prepared by remember(store, key) { mutableStateOf(false) }
    var mounted by remember(store, key) { mutableStateOf(entered) }
    var error by remember(store, key) { mutableStateOf(false) }
    var retry by remember(store, key) { mutableIntStateOf(0) }
    val readiness = remember(store, key) { PageReadiness() }
    val currentPrepare by rememberUpdatedState(prepare)
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    LaunchedEffect(store, key, active, retry) {
        if (!active || entered || prepared) return@LaunchedEffect
        error = false
        try {
            val task = currentPrepare
            withContext(Dispatchers.IO) { task() }
            withFrameNanos { }; withFrameNanos { }
            prepared = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    LaunchedEffect(store, key, active, prepared, transitionFinished) {
        if (active && prepared && transitionFinished) mounted = true
    }
    LaunchedEffect(store, key, active, mounted, readiness.ready, awaitContent) {
        if (active && mounted && (!awaitContent || readiness.ready)) entered = true
    }
    val reveal by animateFloatAsState(if (entered) 1f else 0f,
        if (reduced || retainedEntry) snap() else tween(180), label = "page-content-reveal")
    val inheritedEntrance = LocalModuleEntrance.current
    Box(Modifier.fillMaxSize()) {
        if (mounted) key(store, key) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal }
                .then(if (!entered) Modifier.clearAndSetSemantics { } else Modifier)) {
                CompositionLocalProvider(LocalPageReadiness provides readiness, LocalLoadingRoute provides route, LocalLoadingActive provides active,
                    LocalModuleEntrance provides if (retainedEntry) inheritedEntrance else null) { content() }
            }
        }
        if (reveal < 1f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - reveal }
                .then(if (entered) Modifier.clearAndSetSemantics { } else Modifier)) {
                // A sibling behind the placeholder blocks the hidden page, not its retry button.
                Box(Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                })
                CompositionLocalProvider(LocalLoadingActive provides active) { PageSkeleton(route, title, error, onRetry = { retry++ }) }
            }
        }
    }
}

/** Stable placeholders preserve layout while the destination's own icon indicates progress. */
@Composable
private fun PageSkeleton(route: String, title: String, error: Boolean, onRetry: () -> Unit) {
    val heading = when (route) { "app.grades" -> "成绩与考试"; "app.grab" -> "抢课工作台"; else -> title }
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    @Composable fun Block(modifier: Modifier) {
        Box(modifier.background(tint, RoundedCornerShape(20.dp)).clearAndSetSemantics { })
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = PagePadding)
        .testTag("page-loading:$title"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.padding(top = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(heading, Modifier.weight(1f), fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                if (!error) PageLoadingIcon(route, Modifier.size(36.dp))
            }
            Spacer(Modifier.height(6.dp))
            Block(Modifier.width(112.dp).height(18.dp))
        }
        if (error) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("页面准备失败，请重试", style = MaterialTheme.typography.bodyMedium)
                SystemDialogButton(onClick = onRetry) { Text("重试") }
            }
        } else Column(Modifier.fillMaxSize().semantics { contentDescription = "正在加载页面" },
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (route) {
                "app.grades" -> {
                    Block(Modifier.fillMaxWidth().height(52.dp))
                    Block(Modifier.fillMaxWidth().height(50.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(3) { Block(Modifier.weight(1f).height(74.dp)) }
                    }
                    repeat(3) { Block(Modifier.fillMaxWidth().height(76.dp)) }
                }
                "app.schedule" -> {
                    Block(Modifier.fillMaxWidth().height(48.dp))
                    Row(Modifier.weight(1f).padding(bottom = LocalAppOverlayBottomInset.current + 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(7) { Block(Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
                "app.courses", "app.grab" -> {
                    Block(Modifier.fillMaxWidth().height(52.dp))
                    repeat(3) { Block(Modifier.fillMaxWidth().height(if (route == "app.grab") 112.dp else 96.dp)) }
                }
                else -> repeat(3) { Block(Modifier.fillMaxWidth().height(72.dp)) }
            }
        }
    }
}
