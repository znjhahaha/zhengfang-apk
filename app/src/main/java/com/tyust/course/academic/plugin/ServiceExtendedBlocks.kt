package com.tyust.course.academic.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.LiquidButton
import com.tyust.course.ui.system.LiquidButtonStyle
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun ServiceExtendedBlock(block: JSONObject, enabled: Boolean, onAction: (JSONObject) -> Unit) {
    val items = PluginJson.objects(block.optJSONArray("items") ?: JSONArray())
    when (block.getString("type")) {
        "keyValue" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items.forEach { item -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(item.getString("label"), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.getString("value"), Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
            } }
        }
        "table" -> Column(Modifier.horizontalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val columns = PluginJson.strings(block.getJSONArray("columns"))
            Row { columns.forEach { Text(it, Modifier.width(144.dp).padding(end = 12.dp), fontWeight = FontWeight.SemiBold) } }
            HorizontalDivider(Modifier.width((144 * columns.size).dp))
            PluginJson.objects(block.getJSONArray("rows")).forEach { row -> Row {
                PluginJson.strings(row.getJSONArray("cells")).forEach { Text(it, Modifier.width(144.dp).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium) }
            } }
        }
        "timeline" -> Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items.forEach { item -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.getString("time"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(item.getString("title"), fontWeight = FontWeight.SemiBold)
                    if (item.optString("detail").isNotBlank()) Text(item.getString("detail"), style = MaterialTheme.typography.bodyMedium)
                }
            } }
        }
        "barChart" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val max = (items.maxOfOrNull { it.getDouble("value") } ?: 0.0).coerceAtLeast(1.0)
            items.forEach { item -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.getString("label"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${item.get("value")}${block.optString("unit")}", style = MaterialTheme.typography.labelLarge)
                }
                LinearProgressIndicator(progress = { (item.getDouble("value") / max).toFloat() }, modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = if (item.optString("tone") == "warning") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            } }
        }
        "grid" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val columns = block.getInt("columns")
            items.chunked(columns).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item -> LiquidButton({ onAction(item.getJSONObject("action")) }, enabled = enabled, modifier = Modifier.weight(1f), style = LiquidButtonStyle.Tinted, horizontalPadding = 10.dp) {
                    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.getString("label"), style = MaterialTheme.typography.labelLarge)
                        if (item.optString("subtitle").isNotBlank()) Text(item.getString("subtitle"), style = MaterialTheme.typography.bodySmall)
                    }
                } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            } }
        }
    }
}
