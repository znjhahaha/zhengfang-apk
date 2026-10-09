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
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Immutable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.tyust.course.ui.system.InsetGroupedRow
import com.tyust.course.ui.system.LiquidButton
import com.tyust.course.ui.system.LiquidButtonStyle
import com.tyust.course.ui.system.LiquidSlider
import com.tyust.course.ui.system.LiquidSwitch
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemCompactSegmentedControl
import com.tyust.course.ui.system.SystemSegmentedControl
import com.tyust.course.ui.system.SystemPicker
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plugin views arrive as a fresh JSON tree on every reducer result. Comparing subtrees by their
 * serialized form lets Compose skip every node whose content did not change.
 */
@Immutable
class NativeNodeData(val json: JSONObject) {
    private val serialized = json.toString()
    val children: List<NativeNodeData> by lazy(LazyThreadSafetyMode.NONE) {
        json.optJSONArray("children")?.let(PluginJson::objects).orEmpty().map(::NativeNodeData)
    }
    override fun equals(other: Any?) = other is NativeNodeData && other.serialized == serialized
    override fun hashCode() = serialized.hashCode()
}

private val LocalNativeListStates = staticCompositionLocalOf<SaveableStateHolder?> { null }

@Composable
fun NativePluginNode(node: JSONObject, files: NativePluginFiles?, modifier: Modifier = Modifier, enabled: Boolean = true, emit: (JSONObject, Boolean) -> Unit) {
    val data = remember(node) { NativeNodeData(node) }
    val lists = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalNativeListStates provides lists) {
        NativePluginNode(data, files, modifier, enabled, emit)
    }
}

@Composable
fun NativePluginNode(data: NativeNodeData, files: NativePluginFiles?, modifier: Modifier = Modifier, enabled: Boolean = true, emit: (JSONObject, Boolean) -> Unit) {
    val node = data.json
    val id = node.getString("id"); val type = node.getString("type"); val active = enabled && node.optBoolean("enabled", true)
    val children = data.children
    val gap = node.optInt("gap", 12).dp
    val colors = MaterialTheme.colorScheme
    val tint = when (node.optString("tone")) { "primary", "positive" -> colors.primary; "error" -> colors.error; "warning" -> colors.tertiary; else -> colors.onSurface }
    fun toneColor(tone: String) = when (tone) { "primary" -> colors.primary; "positive" -> com.tyust.course.ui.theme.SemanticSuccess; "error" -> colors.error; "warning" -> com.tyust.course.ui.theme.SemanticWarning; else -> colors.onSurfaceVariant }
    fun event(value: Any? = null, name: String = node.optString("event"), eventType: String = "click", gesture: Boolean = true) {
        if (active) emit(JSONObject().put("type", eventType).put("nodeId", id).put("name", name).apply { if (value != null) put("value", value) }, gesture)
    }
    val base = modifier.testTag("native-node-$id").then(if (node.has("height") && type !in setOf("canvas", "list", "scroll")) Modifier.height(node.getInt("height").dp) else Modifier)
    val body: @Composable (Modifier) -> Unit = { m ->
        when (type) {
            "column" -> Column(m, verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = when (node.optString("align")) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start }) {
                children.forEach { child -> key(child.json.getString("id")) { NativePluginNode(child, files, Modifier.fillMaxWidth().then(if (child.json.optDouble("weight", 0.0) > 0) Modifier.weight(child.json.getDouble("weight").toFloat()) else Modifier), active, emit) } }
            }
            "row" -> Row(m, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = when (node.optString("align")) { "start" -> Alignment.Top; "end" -> Alignment.Bottom; else -> Alignment.CenterVertically }) {
                children.forEach { child -> key(child.json.getString("id")) { NativePluginNode(child, files, if (child.json.optDouble("weight", 0.0) > 0) Modifier.weight(child.json.getDouble("weight").toFloat()) else Modifier, active, emit) } }
            }
            "box" -> Box(m, contentAlignment = when (node.optString("align")) { "center" -> Alignment.Center; "end" -> Alignment.BottomEnd; else -> Alignment.TopStart }) {
                children.forEach { child -> key(child.json.getString("id")) { NativePluginNode(child, files, enabled = active, emit = emit) } }
            }
            // Weighted lists use their parent's remaining viewport. Nested lists retain a bound.
            "scroll", "list" -> NativeListState(id) {
                val listState = rememberLazyListState()
                LaunchedEffect(id, children.size, node.optString("onEnd")) {
                    if (node.has("onEnd")) snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == children.lastIndex && children.isNotEmpty() }
                        .distinctUntilChanged().collect { if (it) event(name = node.getString("onEnd"), eventType = "list.end", gesture = false) }
                }
                val sized = if (!node.has("height") && node.optDouble("weight", 0.0) > 0) m.fillMaxHeight()
                    else m.height(node.optInt("height", if (type == "scroll") 520 else 420).dp)
                LazyColumn(sized, state = listState, verticalArrangement = Arrangement.spacedBy(gap)) {
                    items(children, key = { it.json.getString("id") }, contentType = { it.json.getString("type") }) { child -> NativePluginNode(child, files, Modifier.fillMaxWidth(), active, emit) }
                }
            }
            "text" -> Text(node.getString("text"), m, color = tint, maxLines = node.optInt("maxLines", 100), style = when (node.optString("style")) { "title" -> MaterialTheme.typography.headlineSmall; "label" -> MaterialTheme.typography.labelLarge; "caption" -> MaterialTheme.typography.bodySmall; else -> MaterialTheme.typography.bodyLarge })
            "image" -> {
                val file = remember(node.getString("handle")) { runCatching { files?.file(node.getString("handle")) }.getOrNull() }
                if (file != null) AsyncImage(model = file, contentDescription = node.getString("label"), modifier = m.heightIn(max = 500.dp)) else Text("图片不可用", m)
            }
            "button" -> {
                val compact = node.optString("size") == "compact"
                val label: @Composable () -> Unit = { Text(node.getString("label"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
                // Solid fills: one glass sample per button made long plugin lists stutter.
                if (node.optString("variant") == "plain") Box(m.heightIn(min = if (compact) 36.dp else 44.dp).clip(RoundedCornerShape(50))
                    .clickable(enabled = active) { event() }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalContentColor provides if (active) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f)) { label() }
                } else LiquidButton({ event() }, modifier = m, enabled = active,
                    style = if (node.optString("variant") == "secondary") LiquidButtonStyle.SolidSurface else LiquidButtonStyle.SolidTinted,
                    minHeight = if (compact) 36.dp else 46.dp, horizontalPadding = if (compact) 12.dp else 16.dp) { label() }
            }
            "segmented" -> {
                val options = PluginJson.objects(node.getJSONArray("options"))
                val supplied = node.getString("value")
                val index = options.indexOfFirst { it.getString("value") == supplied }.coerceAtLeast(0)
                val select: (Int) -> Unit = { i -> event(options[i].getString("value"), eventType = "input") }
                if (node.optString("size") == "compact") SystemCompactSegmentedControl(options.map { it.getString("label") }, index, select, modifier = m.fillMaxWidth(), enabled = active)
                else SystemSegmentedControl(options.map { it.getString("label") }, index, select, modifier = m.fillMaxWidth(), enabled = active, height = 44.dp)
            }
            "listItem" -> {
                val clickable = node.has("event")
                val trailing: (@Composable () -> Unit)? = if (node.has("detail") || node.has("badge") || clickable) ({
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        node.optString("detail").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        node.optString("badge").takeIf { it.isNotBlank() }?.let { NativeBadge(it, toneColor(node.optString("badgeTone"))) }
                        if (clickable) Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                    }
                }) else null
                InsetGroupedRow(title = node.getString("title"), modifier = m, subtitle = node.optString("subtitle").takeIf { it.isNotBlank() },
                    showDivider = node.optBoolean("divider", false), enabled = active, onClick = if (clickable) ({ event() }) else null, trailing = trailing)
            }
            "badge" -> Box(m) { NativeBadge(node.getString("text"), toneColor(node.optString("tone"))) }
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
            "pattern" -> NativeGesturePattern(node.getString("label"), node.getString("value"), active, m) { event(it, eventType = "input") }
            "toggle" -> Row(m.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(node.getString("label"), Modifier.weight(1f)); LiquidSwitch(node.getBoolean("value"), { event(it, eventType = "input") }, enabled = active)
            }
            "select" -> {
                val options = PluginJson.objects(node.getJSONArray("options"))
                SystemPicker(options.map { it.getString("label") },
                    options.indexOfFirst { it.getString("value") == node.getString("value") }.takeIf { it >= 0 },
                    { event(options[it].getString("value"), eventType = "input") }, modifier = m.fillMaxWidth(),
                    label = node.getString("label"), enabled = active)
            }
            "slider" -> Column(m) {
                val supplied = node.getDouble("value").toFloat()
                var current by remember(id, supplied) { mutableFloatStateOf(supplied) }
                Text(node.getString("label")); LiquidSlider({ current }, { current = it; event(it.toDouble(), eventType = "input") }, enabled = active, valueRange = node.getDouble("min").toFloat()..node.getDouble("max").toFloat(), steps = node.optInt("steps", 0))
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

@Composable
private fun NativeListState(id: String, content: @Composable () -> Unit) {
    val states = LocalNativeListStates.current
    if (states == null) content() else states.SaveableStateProvider(id, content)
}

@Composable
private fun NativeBadge(text: String, color: Color) {
    Text(text, Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
        color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
}
