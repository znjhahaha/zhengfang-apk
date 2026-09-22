package com.tyust.course.academic.plugin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tyust.course.ui.system.LiquidButton
import com.tyust.course.ui.system.LiquidButtonStyle
import com.tyust.course.ui.system.SystemCard
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun NativePluginNode(node: JSONObject, files: NativePluginFiles?, modifier: Modifier = Modifier, enabled: Boolean = true, emit: (JSONObject, Boolean) -> Unit) {
    val id = node.getString("id"); val type = node.getString("type"); val active = enabled && node.optBoolean("enabled", true)
    val children = node.optJSONArray("children")?.let(PluginJson::objects).orEmpty()
    val gap = node.optInt("gap", 12).dp
    val colors = MaterialTheme.colorScheme
    val tint = when (node.optString("tone")) { "primary", "positive" -> colors.primary; "error" -> colors.error; "warning" -> colors.tertiary; else -> colors.onSurface }
    fun event(value: Any? = null, name: String = node.optString("event"), eventType: String = "click", gesture: Boolean = true) {
        if (active) emit(JSONObject().put("type", eventType).put("nodeId", id).put("name", name).apply { if (value != null) put("value", value) }, gesture)
    }
    val base = modifier.testTag("native-node-$id").then(if (node.has("height") && type !in setOf("canvas", "list", "scroll")) Modifier.height(node.getInt("height").dp) else Modifier)
    val body: @Composable (Modifier) -> Unit = { m ->
        when (type) {
            "column" -> Column(m, verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = when (node.optString("align")) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start }) {
                children.forEach { child -> key(child.getString("id")) { NativePluginNode(child, files, Modifier.fillMaxWidth().then(if (child.optDouble("weight", 0.0) > 0) Modifier.weight(child.getDouble("weight").toFloat()) else Modifier), active, emit) } }
            }
            "row" -> Row(m, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = when (node.optString("align")) { "start" -> Alignment.Top; "end" -> Alignment.Bottom; else -> Alignment.CenterVertically }) {
                children.forEach { child -> key(child.getString("id")) { NativePluginNode(child, files, if (child.optDouble("weight", 0.0) > 0) Modifier.weight(child.getDouble("weight").toFloat()) else Modifier, active, emit) } }
            }
            "box" -> Box(m, contentAlignment = when (node.optString("align")) { "center" -> Alignment.Center; "end" -> Alignment.BottomEnd; else -> Alignment.TopStart }) {
                children.forEach { child -> key(child.getString("id")) { NativePluginNode(child, files, enabled = active, emit = emit) } }
            }
            "scroll" -> Column(m.height(node.optInt("height", 520).dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
                children.forEach { child -> key(child.getString("id")) { NativePluginNode(child, files, Modifier.fillMaxWidth(), active, emit) } }
            }
            "list" -> {
                val listState = rememberLazyListState()
                LaunchedEffect(id, children.size, node.optString("onEnd")) {
                    if (node.has("onEnd")) snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == children.lastIndex && children.isNotEmpty() }
                        .distinctUntilChanged().collect { if (it) event(name = node.getString("onEnd"), eventType = "list.end", gesture = false) }
                }
                LazyColumn(m.height(node.optInt("height", 420).dp), state = listState, verticalArrangement = Arrangement.spacedBy(gap)) {
                    items(children, key = { it.getString("id") }) { child -> NativePluginNode(child, files, Modifier.fillMaxWidth(), active, emit) }
                }
            }
            "text" -> Text(node.getString("text"), m, color = tint, maxLines = node.optInt("maxLines", 100), style = when (node.optString("style")) { "title" -> MaterialTheme.typography.headlineSmall; "label" -> MaterialTheme.typography.labelLarge; "caption" -> MaterialTheme.typography.bodySmall; else -> MaterialTheme.typography.bodyLarge })
            "image" -> {
                val file = remember(node.getString("handle")) { runCatching { files?.file(node.getString("handle")) }.getOrNull() }
                if (file != null) AsyncImage(model = file, contentDescription = node.getString("label"), modifier = m.heightIn(max = 500.dp)) else Text("图片不可用", m)
            }
            "button" -> LiquidButton({ event() }, modifier = m.heightIn(min = 48.dp), enabled = active, style = LiquidButtonStyle.Tinted) { Text(node.getString("label")) }
            "input" -> {
                val supplied = node.getString("value")
                var value by remember(id) { mutableStateOf(supplied) }
                var focused by remember(id) { mutableStateOf(false) }
                LaunchedEffect(supplied, focused) { if (!focused) value = supplied }
                OutlinedTextField(value, { if (it.length <= 8000) { value = it; event(it, eventType = "input") } }, modifier = m.onFocusChanged { focused = it.isFocused },
                    enabled = active, label = { Text(node.getString("label")) }, placeholder = { Text(node.optString("placeholder")) }, singleLine = node.optString("inputType") != "multiline",
                    keyboardOptions = KeyboardOptions(keyboardType = when (node.optString("inputType")) { "number" -> KeyboardType.Decimal; "password" -> KeyboardType.Password; else -> KeyboardType.Text }),
                    visualTransformation = if (node.optString("inputType") == "password") PasswordVisualTransformation() else VisualTransformation.None)
            }
            "toggle" -> Row(m.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(node.getString("label"), Modifier.weight(1f)); Switch(node.getBoolean("value"), { event(it, eventType = "input") }, enabled = active)
            }
            "select" -> {
                var expanded by remember(id) { mutableStateOf(false) }
                val options = PluginJson.objects(node.getJSONArray("options"))
                Box(m) {
                    OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = active) { Text(node.getString("label") + "：" + options.first { it.getString("value") == node.getString("value") }.getString("label")) }
                    DropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(option.getString("label")) }, onClick = { expanded = false; event(option.getString("value"), eventType = "input") }) } }
                }
            }
            "slider" -> Column(m) {
                Text(node.getString("label")); Slider(node.getDouble("value").toFloat(), { event(it.toDouble(), eventType = "input") }, enabled = active, valueRange = node.getDouble("min").toFloat()..node.getDouble("max").toFloat(), steps = node.optInt("steps", 0))
            }
            "progress" -> Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) { if (node.has("label")) Text(node.getString("label")); LinearProgressIndicator(progress = { node.getDouble("value").toFloat() }, modifier = Modifier.fillMaxWidth()) }
            "divider" -> HorizontalDivider(m)
            "spacer" -> Spacer(m.height(node.optInt("height", 12).dp))
            "canvas" -> {
                val palette = mapOf("primary" to colors.primary, "foreground" to colors.onSurface, "muted" to colors.onSurfaceVariant, "positive" to colors.secondary, "warning" to colors.tertiary, "error" to colors.error)
                val shapes = PluginJson.objects(node.getJSONArray("shapes"))
                Canvas(m.fillMaxWidth().height(node.getInt("height").dp).semantics { contentDescription = node.getString("label") }) {
                    val sx = size.width / node.getInt("width"); val sy = size.height / node.getInt("height")
                    for (shape in shapes) {
                        val color = palette[shape.optString("color")] ?: palette.getValue("primary")
                        val x = shape.optDouble("x", 0.0).toFloat() * sx; val y = shape.optDouble("y", 0.0).toFloat() * sy
                        val stroke = shape.optDouble("stroke", 2.0).toFloat() * minOf(sx, sy)
                        when (shape.getString("type")) {
                            "line" -> drawLine(color, Offset(x, y), Offset(shape.optDouble("x2", 0.0).toFloat() * sx, shape.optDouble("y2", 0.0).toFloat() * sy), stroke.coerceAtLeast(1f))
                            "rect" -> drawRect(color, Offset(x, y), Size(shape.optDouble("width", 0.0).toFloat() * sx, shape.optDouble("height", 0.0).toFloat() * sy))
                            "circle" -> drawCircle(color, shape.optDouble("radius", 0.0).toFloat() * minOf(sx, sy), Offset(x, y))
                            "path" -> { val path = Path(); PluginJson.objects(shape.optJSONArray("points") ?: JSONArray()).forEachIndexed { index, point -> if (index == 0) path.moveTo(point.getDouble("x").toFloat() * sx, point.getDouble("y").toFloat() * sy) else path.lineTo(point.getDouble("x").toFloat() * sx, point.getDouble("y").toFloat() * sy) }; drawPath(path, color, style = Stroke(stroke.coerceAtLeast(1f))) }
                        }
                    }
                }
            }
        }
    }
    if (node.optString("surface") in setOf("glass", "tonal")) SystemCard(modifier = base, backgroundColor = if (node.optString("surface") == "tonal") colors.surfaceVariant else colors.surface,
        contentPadding = PaddingValues(node.optInt("padding", 16).dp)) { body(Modifier.fillMaxWidth()) }
    else body(base.padding(node.optInt("padding", 0).dp))
}
