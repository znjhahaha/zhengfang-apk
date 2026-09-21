package com.tyust.course.academic.plugin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.*
import org.json.JSONObject

/** Plugins provide data, order and a bounded layout; native components own interaction and style. */
@Composable
fun ServicePageBlock(block: JSONObject, enabled: Boolean, onAction: (JSONObject) -> Unit) {
    val type = block.getString("type")
    val title = block.optString("title").takeIf { it.isNotBlank() }
    val items = PluginJson.objects(block.optJSONArray("items") ?: org.json.JSONArray())
    InsetGroupedSection(header = if (type == "profile") null else title,
        modifier = Modifier.testTag("service-block-${block.getString("id")}")) {
        when (type) {
            "profile" -> Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(block.getString("title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                OptionalText(block.optString("subtitle"))
                PluginJson.strings(block.optJSONArray("details")).forEach { OptionalText(it) }
                block.optString("badge").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
            }
            "metrics" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items.chunked(block.getInt("columns")).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { metric -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(metric.getString("value") + metric.optString("unit"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text(metric.getString("label"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                        repeat(block.getInt("columns") - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            "progress" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items.forEach { progress ->
                    val value = progress.getDouble("value"); val max = progress.getDouble("max")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(progress.getString("label"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(progress.optString("displayValue").ifBlank { "${number(value)} / ${number(max)}" }, style = MaterialTheme.typography.labelLarge, color = toneColor(progress.optString("tone")))
                        }
                        LinearProgressIndicator(progress = { (value / max).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth(), color = toneColor(progress.optString("tone")))
                        OptionalText(progress.optString("detail"))
                    }
                }
            }
            "list" -> {
                if (items.isEmpty()) Text("暂无记录", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                items.forEachIndexed { index, item ->
                    val action = item.optJSONObject("action")
                    InsetGroupedRow(title = item.getString("title"), subtitle = item.optString("subtitle").takeIf { it.isNotBlank() },
                        enabled = enabled, showDivider = index < items.lastIndex,
                        onClick = action?.let { { onAction(it) } }, trailing = {
                            if (item.has("value")) Text(item.getString("value"), Modifier.widthIn(max = 120.dp), style = MaterialTheme.typography.labelLarge)
                            else if (action != null) Icon(Icons.Outlined.ChevronRight, null)
                        })
                }
            }
            "notice" -> Text(block.getString("text"), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = toneColor(block.optString("tone")))
            "actions" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { action -> LiquidButton({ onAction(action.getJSONObject("action")) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), style = LiquidButtonStyle.Tinted) { Text(action.getString("label")) } }
            }
            "form" -> ServiceForm(block, enabled, onAction)
        }
    }
}

@Composable private fun ServiceForm(block: JSONObject, enabled: Boolean, onAction: (JSONObject) -> Unit) {
    val fields = PluginJson.objects(block.getJSONArray("fields"))
    val values = remember(block.toString()) { mutableStateMapOf<String, String>().apply {
        fields.forEach { field -> put(field.getString("id"), field.optString("value").ifBlank {
            if (field.getString("type") == "select" && field.getBoolean("required")) field.getJSONArray("options").getJSONObject(0).getString("value") else ""
        }) }
    } }
    var error by remember(block.toString()) { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        fields.forEach { field ->
            val id = field.getString("id"); val value = values[id].orEmpty()
            val label = field.getString("label") + if (field.getBoolean("required")) "" else "（可选）"
            if (field.getString("type") == "select") {
                var expanded by remember { mutableStateOf(false) }
                val options = PluginJson.objects(field.getJSONArray("options"))
                Column {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Box {
                        LiquidButton({ expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                            Text(options.firstOrNull { it.getString("value") == value }?.getString("label") ?: "请选择")
                            Icon(Icons.Outlined.ExpandMore, null)
                        }
                        DropdownMenu(expanded, { expanded = false }) {
                            if (!field.getBoolean("required")) DropdownMenuItem(text = { Text("不选择") }, onClick = { values[id] = ""; expanded = false })
                            options.forEach { option -> DropdownMenuItem(text = { Text(option.getString("label")) }, onClick = { values[id] = option.getString("value"); expanded = false }) }
                        }
                    }
                }
            } else OutlinedTextField(value, { values[id] = it.take(1000) }, label = { Text(label) },
                placeholder = { Text(field.optString("placeholder")) }, enabled = enabled, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (field.getString("type") == "number") KeyboardType.Decimal else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().testTag("service-field-$id"))
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        val submit = block.getJSONObject("submit")
        LiquidButton({
            val invalid = fields.firstOrNull { field ->
                val value = values[field.getString("id")].orEmpty()
                field.getBoolean("required") && value.isBlank() || field.getString("type") == "number" && value.isNotBlank() && value.toDoubleOrNull()?.isFinite() != true
            }
            if (invalid != null) error = "请检查${invalid.getString("label")}"
            else { error = ""; onAction(JSONObject().put("type", "action").put("actionId", submit.getString("actionId")).put("params", JSONObject(values.toMap()))) }
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("service-form-submit"), style = LiquidButtonStyle.Tinted) { Text(submit.getString("label")) }
    }
}

@Composable private fun OptionalText(text: String) { if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun toneColor(tone: String): Color = when (tone) {
    "positive" -> Color(0xFF2B9878)
    "warning" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}
private fun number(value: Double): String = if (value % 1 == 0.0) value.toLong().toString() else value.toString()
