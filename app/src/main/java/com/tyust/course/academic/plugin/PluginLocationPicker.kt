package com.tyust.course.academic.plugin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tyust.course.ui.system.SystemDialogButton
import com.tyust.course.ui.system.SystemFormPage
import com.tyust.course.ui.system.SystemPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale

@Composable internal fun LocationPrompt(prompt: PluginVisualPrompt) {
    val app = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var mapError by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val point = runCatching { PluginCoordinates.convert(PluginCoordinates.Point(latitude.toDouble(), longitude.toDouble()), system, system) }.getOrNull()
    fun choose(value: PluginCoordinates.Point, name: String? = null) {
        val converted = PluginCoordinates.convert(value, "WGS84", system)
        latitude = "%.7f".format(Locale.US, converted.latitude)
        longitude = "%.7f".format(Locale.US, converted.longitude)
        if (name != null) address = name.take(500)
        revision++
        feedback = "已选中地点，可继续拖动地图或点击微调"
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(value.latitude, value.longitude), 17.0))
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
    val mapView = remember(prompt.id) {
        runCatching {
            MapLibre.getInstance(app)
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", PluginLocationSearch.USER_AGENT).build())
            }.build())
            MapView(app).apply {
                onCreate(Bundle())
                addOnDidFailLoadingMapListener { mapError = "地图暂不可用，搜索和坐标输入仍可使用" }
                // Let the map own drags/pinches instead of scrolling the containing form.
                setOnTouchListener { view, event ->
                    view.parent?.requestDisallowInterceptTouchEvent(event.actionMasked !in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL))
                    false
                }
            }
        }.getOrElse { mapError = "地图暂不可用，可搜索地点或输入坐标"; null }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mapView, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> mapView?.onStart()
            Lifecycle.Event.ON_RESUME -> mapView?.onResume()
            Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
            Lifecycle.Event.ON_STOP -> mapView?.onStop()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView?.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView?.onResume()
        onDispose { lifecycle.removeObserver(observer); mapView?.onPause(); mapView?.onStop(); mapView?.onDestroy() }
    }
    val latestPoint by rememberUpdatedState(point?.let { PluginCoordinates.convert(it, system, "WGS84") })
    LaunchedEffect(mapView) {
        mapView?.getMapAsync { loaded ->
            map = loaded
            loaded.setStyle(Style.Builder().fromJson(PLUGIN_OSM_STYLE))
            loaded.uiSettings.isAttributionEnabled = true
            loaded.addOnMapClickListener { location -> latestChoose(PluginCoordinates.Point(location.latitude, location.longitude), null); true }
            val start = latestPoint
            loaded.moveCamera(CameraUpdateFactory.newLatLngZoom(if (start == null) LatLng(35.0, 104.0) else LatLng(start.latitude, start.longitude), if (start == null) 3.0 else 17.0))
        }
    }
    LaunchedEffect(map, latitude, longitude, system) {
        map?.clear()
        latestPoint?.let { map?.addMarker(MarkerOptions().position(LatLng(it.latitude, it.longitude))) }
    }
    val height = (LocalConfiguration.current.screenHeightDp * 0.42f).dp.coerceIn(260.dp, 460.dp)
    SystemFormPage(title = "选择签到地点", onDismissRequest = { prompt.result.complete(null) },
        confirmButton = { SystemDialogButton(primary = true, enabled = point != null, onClick = {
            prompt.result.complete(JSONObject().put("latitude", point!!.latitude).put("longitude", point.longitude)
                .put("address", address.ifBlank { "已选地点" }).put("coordinateSystem", system))
        }) { Text("使用此地点") } }, dismissButton = { SystemDialogButton(onClick = { prompt.result.complete(null) }) { Text("取消") } }) {
        OutlinedTextField(query, { query = it.take(120) }, modifier = Modifier.fillMaxWidth().testTag("location-search-input"), singleLine = true,
            label = { Text("搜索学校、教学楼或地址") }, trailingIcon = {
                TextButton(enabled = !searching && query.trim().length >= 2, onClick = {
                    val text = query.trim(); searching = true; feedback = ""; results = emptyList()
                    scope.launch {
                        try { results = PluginLocationSearch.search(text); if (results.isEmpty()) feedback = "没有找到，可缩短名称或在地图中选点" }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { feedback = "搜索暂不可用，可用当前位置、地图或坐标" }
                        finally { searching = false }
                    }
                }) { Text(if (searching) "搜索中" else "搜索") }
            })
        results.forEachIndexed { index, place ->
            OutlinedCard(onClick = { choose(place.point, place.name); results = emptyList() }, modifier = Modifier.fillMaxWidth().testTag("location-search-result-$index")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(place.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (place.address != place.title) Text(place.address, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), enabled = !locating, onClick = {
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
                else { locating = true; permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
            }) { Text(if (locating) "正在定位…" else "当前位置") }
            OutlinedButton(modifier = Modifier.weight(1f), enabled = point != null, onClick = { latestPoint?.let { choose(it) } }) { Text("回到选点") }
        }
        if (mapView != null) AndroidView(factory = { mapView }, modifier = Modifier.fillMaxWidth().height(height).testTag("location-map"))
        Text(mapError.ifEmpty { if (map == null) "正在加载地图…" else "双指缩放地图，点击放置标记" }, style = MaterialTheme.typography.bodySmall)
        if (feedback.isNotEmpty()) Text(feedback, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(address, { address = it.take(500) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("地点名称（方便下次复用）") }, placeholder = { Text("例如：教学楼 A101") })
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("location-coordinate-toggle")) { Text(if (advanced) "收起坐标设置" else "输入或粘贴坐标") }
        if (advanced) {
            Text("快捷输入按“经度, 纬度”填写，先选择来源坐标系。", style = MaterialTheme.typography.bodySmall)
            val systems = listOf("WGS84", "GCJ02", "BD09")
            SystemPicker(listOf("地图 / GPS", "高德 / 腾讯", "百度"), systems.indexOf(system), { index ->
                point?.let { val converted = PluginCoordinates.convert(it, system, systems[index]); latitude = converted.latitude.toString(); longitude = converted.longitude.toString() }
                system = systems[index]; revision++
            }, label = "坐标来源")
            OutlinedTextField(pasted, { pasted = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("经度, 纬度") }, singleLine = true,
                placeholder = { Text("112.500000, 37.850000") }, trailingIcon = { TextButton(onClick = {
                    val parsed = PluginLocationInput.parse(pasted)
                    if (parsed == null) feedback = "坐标格式无效，例如：112.500000, 37.850000"
                    else choose(PluginCoordinates.convert(parsed, system, "WGS84"))
                }) { Text("定位") } })
            OutlinedTextField(longitude, { longitude = it.take(24); revision++ }, modifier = Modifier.fillMaxWidth(), label = { Text("经度（−180～180）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(latitude, { latitude = it.take(24); revision++ }, modifier = Modifier.fillMaxWidth(), label = { Text("纬度（−90～90）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (point == null && (latitude.isNotEmpty() || longitude.isNotEmpty())) Text("请输入有效的经纬度", color = MaterialTheme.colorScheme.error)
            Text("地图与定位使用 WGS-84，高德 / 腾讯为 GCJ-02，百度为 BD-09。保存时保留来源，签到时自动换算。", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }) {
            Text("© OpenStreetMap · 搜索 Nominatim · MapLibre", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val PLUGIN_OSM_STYLE = """{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"osm","type":"raster","source":"osm"}]}"""
