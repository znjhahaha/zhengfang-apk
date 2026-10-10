package com.tyust.course.academic.plugin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tyust.course.ui.system.SystemDialogButton
import com.tyust.course.ui.system.SystemFormPage
import com.tyust.course.ui.system.SystemPicker
import com.tyust.course.ui.system.GlassFormField
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.InsetGroupedRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Locale

@Composable internal fun LocationPrompt(prompt: PluginVisualPrompt) {
    val app = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val initial = prompt.input.optJSONObject("initial")
    var latitude by rememberSaveable(prompt.id) { mutableStateOf(initial?.optString("latitude").orEmpty()) }
    var longitude by rememberSaveable(prompt.id) { mutableStateOf(initial?.optString("longitude").orEmpty()) }
    var address by rememberSaveable(prompt.id) { mutableStateOf(initial?.optString("address").orEmpty()) }
    var system by rememberSaveable(prompt.id) { mutableStateOf(initial?.optString("coordinateSystem") ?: "WGS84") }
    var advanced by rememberSaveable(prompt.id) { mutableStateOf(false) }
    var pasted by rememberSaveable(prompt.id) { mutableStateOf("") }
    var query by rememberSaveable(prompt.id) { mutableStateOf(prompt.input.optString("query").take(120)) }
    var results by remember { mutableStateOf(emptyList<PluginLocationSearch.Place>()) }
    var searching by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    val point = runCatching { PluginCoordinates.convert(PluginCoordinates.Point(latitude.toDouble(), longitude.toDouble()), system, system) }.getOrNull()
    fun choose(value: PluginCoordinates.Point, name: String? = null) {
        val converted = PluginCoordinates.convert(value, "WGS84", system)
        latitude = "%.7f".format(Locale.US, converted.latitude)
        longitude = "%.7f".format(Locale.US, converted.longitude)
        if (name != null) address = name.take(500)
        revision++
        feedback = "已选中地点，可继续拖动地图或点击微调"
    }
    val latestChoose by rememberUpdatedState(::choose)
    fun locate() {
        val started = revision
        locating = true
        scope.launch {
            try {
                val location = withTimeout(20_000) { currentPluginLocation(app) }
                if (revision == started) {
                    latestChoose(PluginCoordinates.Point(location.latitude, location.longitude), "当前位置")
                    feedback = "已定位，精度约 ${location.accuracy.toInt()} 米；可点击地图微调"
                }
            } catch (e: CancellationException) { if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e; feedback = "定位超时，可搜索地点或手动选点" }
            catch (_: Exception) { feedback = "暂时无法定位，可搜索地点或手动选点" }
            finally { locating = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else { locating = false; feedback = "定位权限未开启，可搜索地点或手动选点" }
    }
    val latestPoint by rememberUpdatedState(point?.let { PluginCoordinates.convert(it, system, "WGS84") })
    fun search() {
        if (searching || query.trim().length < 2) return
        val text = query.trim(); searching = true; feedback = ""; results = emptyList()
        focus.clearFocus(); keyboard?.hide()
        scope.launch {
            try { results = PluginLocationSearch.search(text); if (results.isEmpty()) feedback = "没有找到，可缩短名称或在地图中选点" }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedback = "搜索暂不可用，可用当前位置、地图或坐标" }
            finally { searching = false }
        }
    }
    val height = (LocalConfiguration.current.screenHeightDp * 0.42f).dp.coerceIn(260.dp, 460.dp)
    SystemFormPage(title = "选择签到地点", onDismissRequest = { prompt.result.complete(null) },
        confirmButton = { SystemDialogButton(modifier = Modifier.fillMaxWidth(), primary = true, enabled = point != null, onClick = {
            prompt.result.complete(JSONObject().put("latitude", point!!.latitude).put("longitude", point.longitude)
                .put("address", address.ifBlank { "已选地点" }).put("coordinateSystem", system))
        }) { Text("使用此地点") } }, dismissButton = { SystemDialogButton(modifier = Modifier.fillMaxWidth(), onClick = { prompt.result.complete(null) }) { Text("取消") } }) {
        GlassFormField(query, { query = it.take(120) }, label = "搜索学校、教学楼或地址", placeholder = "例如：太原科技大学", modifier = Modifier.fillMaxWidth().testTag("location-search-input"),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }), trailing = {
                TextButton(enabled = !searching && query.trim().length >= 2, onClick = { search() }) { Text(if (searching) "搜索中" else "搜索") }
            })
        results.forEachIndexed { index, place ->
            SystemCard(modifier = Modifier.fillMaxWidth().testTag("location-search-result-$index"), contentPadding = PaddingValues(0.dp)) {
                InsetGroupedRow(title = place.title, subtitle = place.address.takeIf { it != place.title },
                    onClick = { choose(place.point, place.name); results = emptyList() }, showDivider = false)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SystemDialogButton(modifier = Modifier.weight(1f), enabled = !locating, onClick = {
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
                else { locating = true; permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
            }) { Text(if (locating) "正在定位…" else "当前位置") }
            SystemDialogButton(modifier = Modifier.weight(1f), enabled = point != null, onClick = { revision++ }) { Text("回到选点") }
        }
        PluginLocationMap(latestPoint, revision, { choose(it) }, Modifier.fillMaxWidth().height(height))
        Text("双指缩放地图，点击放置标记", style = MaterialTheme.typography.bodySmall)
        if (feedback.isNotEmpty()) Text(feedback, style = MaterialTheme.typography.bodyMedium)
        GlassFormField(address, { address = it.take(500) }, label = "地点名称", modifier = Modifier.fillMaxWidth(),
            supportingText = "保存名称，方便下次复用", placeholder = "例如：教学楼 A101")
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("location-coordinate-toggle")) { Text(if (advanced) "收起坐标设置" else "输入或粘贴坐标") }
        if (advanced) {
            Text("快捷输入按“经度, 纬度”填写，先选择来源坐标系。", style = MaterialTheme.typography.bodySmall)
            val systems = listOf("WGS84", "GCJ02", "BD09")
            SystemPicker(listOf("地图 / GPS", "高德 / 腾讯", "百度"), systems.indexOf(system), { index ->
                point?.let { val converted = PluginCoordinates.convert(it, system, systems[index]); latitude = converted.latitude.toString(); longitude = converted.longitude.toString() }
                system = systems[index]; revision++
            }, label = "坐标来源")
            GlassFormField(pasted, { pasted = it.take(200) }, label = "经度, 纬度", modifier = Modifier.fillMaxWidth(),
                placeholder = "112.500000, 37.850000", trailing = { TextButton(onClick = {
                    val parsed = PluginLocationInput.parse(pasted)
                    if (parsed == null) feedback = "坐标格式无效，例如：112.500000, 37.850000"
                    else choose(PluginCoordinates.convert(parsed, system, "WGS84"))
                }) { Text("定位") } })
            GlassFormField(longitude, { longitude = it.take(24); revision++ }, label = "经度（−180～180）", modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            GlassFormField(latitude, { latitude = it.take(24); revision++ }, label = "纬度（−90～90）", modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (point == null && (latitude.isNotEmpty() || longitude.isNotEmpty())) Text("请输入有效的经纬度", color = MaterialTheme.colorScheme.error)
            Text("地图与定位使用 WGS-84，高德 / 腾讯为 GCJ-02，百度为 BD-09。保存时保留来源，签到时自动换算。", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }) {
            Text("© OpenStreetMap · 搜索 Nominatim · MapLibre", style = MaterialTheme.typography.labelSmall)
        }
    }
}
