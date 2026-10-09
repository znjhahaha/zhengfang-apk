package com.tyust.course.academic.plugin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.*
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemDialogButton
import com.tyust.course.ui.system.LiquidSlider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

internal class PluginVisualPrompt(val kind: String, val input: JSONObject, val images: List<File>) {
    val id = UUID.randomUUID().toString()
    val result = CompletableDeferred<JSONObject?>()
}
@Composable internal fun NativePluginVisualPrompts(state: PageInteraction) {
    val prompt = state.visualPrompt ?: return
    key(prompt.id) { if (prompt.kind == "device.location.pick") LocationPrompt(prompt) else PuzzlePrompt(prompt) }
}

@Composable private fun PuzzlePrompt(prompt: PluginVisualPrompt) {
    val owner = remember(prompt.id) { PluginPuzzleImages() }
    var images by remember { mutableStateOf<Pair<PluginImageMatcher.Image,PluginImageMatcher.Image>?>(null) }
    var error by remember { mutableStateOf("") }
    var x by rememberSaveable(prompt.id) { mutableFloatStateOf(0f) }
    var expired by remember { mutableStateOf(System.currentTimeMillis() >= prompt.input.getLong("expiresAt")) }
    LaunchedEffect(prompt.id) {
        try { images = withContext(Dispatchers.IO) { owner.load(prompt.images[0], prompt.images[1]) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = "验证图片无法读取，请重新获取" }
    }
    LaunchedEffect(prompt.id) { while (!expired) { delay(500); expired = System.currentTimeMillis() >= prompt.input.getLong("expiresAt") } }
    DisposableEffect(owner) { onDispose { owner.close() } }
    SystemDialog(onDismissRequest = { prompt.result.complete(null) }, title = { Text("拖动拼图完成验证") },
        confirmButton = { SystemDialogButton(primary=true, enabled=images!=null && !expired && error.isEmpty(), onClick={
            prompt.result.complete(JSONObject().put("x", x.toDouble()).put("width",images!!.first.width))
        }) { Text("提交验证") } }, dismissButton = { SystemDialogButton(onClick={prompt.result.complete(null)}) { Text("取消") } }) {
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if (error.isNotEmpty()) Text(error)
            images?.let { (background,piece) ->
                val maximum = (background.width-piece.width).coerceAtLeast(1).toFloat()
                Canvas(Modifier.fillMaxWidth().aspectRatio(background.width.toFloat()/background.height)
                    .pointerInput(prompt.id, maximum) { detectDragGestures { change, delta -> change.consume(); if (!expired) x=(x+delta.x/size.width*background.width).coerceIn(0f,maximum) } }) {
                    val factor=size.width/background.width
                    drawImage(background.bitmap.asImageBitmap(),dstSize=IntSize(size.width.roundToInt(),size.height.roundToInt()))
                    drawImage(piece.bitmap.asImageBitmap(),dstOffset=IntOffset((x*factor).roundToInt(),(prompt.input.optDouble("y",0.0)*factor).roundToInt()),dstSize=IntSize((piece.width*factor).roundToInt().coerceAtLeast(1),(piece.height*factor).roundToInt().coerceAtLeast(1)))
                }
                LiquidSlider({x},{x=it},valueRange=0f..maximum,enabled=!expired)
            }
            Text(if(expired)"验证已过期，请取消并重新获取" else "将拼图移动到缺口处，再提交验证。",style=MaterialTheme.typography.bodySmall)
        }
    }
}

/** Reject late decodes, while published images remain alive for the dialog's final frames. */
internal class PluginPuzzleImages : AutoCloseable {
    private var closed = false
    fun load(background: File, piece: File): Pair<PluginImageMatcher.Image, PluginImageMatcher.Image>? {
        val bg = PluginImageMatcher.load(background)
        val cut = try { PluginImageMatcher.load(piece) } catch (e: Throwable) { bg.bitmap.recycle(); throw e }
        synchronized(this) {
            if (closed) { bg.bitmap.recycle(); cut.bitmap.recycle(); return null }
            return bg to cut
        }
    }
    @Synchronized override fun close() {
        closed = true
        // DialogHost and RenderThread can still draw after the owner leaves composition.
        // Let published bitmaps be collected with their last drawing references.
    }
}
