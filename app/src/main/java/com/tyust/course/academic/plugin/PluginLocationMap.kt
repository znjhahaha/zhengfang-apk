package com.tyust.course.academic.plugin

import android.os.Bundle
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tyust.course.ui.system.GlassLoadingIndicator
import com.tyust.course.ui.system.SystemDialogButton
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.concurrent.TimeUnit

/** The resource lives inside the hosted page, including its final animated frames. */
@Composable
internal fun PluginLocationMap(point: PluginCoordinates.Point?, focusRevision: Int, onChoose: (PluginCoordinates.Point) -> Unit, modifier: Modifier = Modifier) {
    var attempt by remember { mutableIntStateOf(0) }
    key(attempt) { PluginMapSurface(point, focusRevision, onChoose, { attempt++ }, modifier) }
}

@Composable
private fun PluginMapSurface(point: PluginCoordinates.Point?, focusRevision: Int, onChoose: (PluginCoordinates.Point) -> Unit, retry: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestPoint by rememberUpdatedState(point)
    val latestChoose by rememberUpdatedState(onChoose)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val mapView = remember {
        runCatching {
            MapLibre.getInstance(context.applicationContext)
            PluginMapNetwork.initialize()
            // SurfaceView cannot participate reliably in a clipped, animated Compose page.
            object : MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                    return super.dispatchTouchEvent(event)
                }
            }.apply { onCreate(Bundle()) }
        }.getOrElse { error = "地图初始化失败"; null }
    }
    DisposableEffect(mapView, lifecycle) {
        var disposed = false
        val resource = mapView?.let { view ->
            PluginMapLifecycle(view::onStart, view::onResume, view::onPause, view::onStop, view::onDestroy)
        }
        val fail = MapView.OnDidFailLoadingMapListener { if (!disposed) error = "地图加载失败，请检查网络后重试" }
        val frame = MapView.OnDidFinishRenderingMapListener { fully ->
            if (!disposed && fully && styleReady) { rendered = true; error = "" }
        }
        mapView?.addOnDidFailLoadingMapListener(fail)
        mapView?.addOnDidFinishRenderingMapListener(frame)
        val observer = LifecycleEventObserver { _, _ ->
            resource?.update(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        resource?.update(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        mapView?.getMapAsync { loaded ->
            if (!disposed) {
                map = loaded
                loaded.uiSettings.isAttributionEnabled = true
                loaded.addOnMapClickListener { location ->
                    if (!disposed) latestChoose(PluginCoordinates.Point(location.latitude, location.longitude))
                    true
                }
                val start = latestPoint
                loaded.moveCamera(CameraUpdateFactory.newLatLngZoom(start?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(35.0, 104.0), if (start == null) 3.0 else 17.0))
                loaded.setStyle(Style.Builder().fromJson(PLUGIN_OSM_STYLE)) { if (!disposed) styleReady = true }
            }
        }
        onDispose {
            disposed = true
            lifecycle.removeObserver(observer)
            mapView?.removeOnDidFailLoadingMapListener(fail)
            mapView?.removeOnDidFinishRenderingMapListener(frame)
            resource?.close()
        }
    }
    LaunchedEffect(mapView) {
        delay(15_000)
        if (!rendered && error.isEmpty()) error = "地图加载超时，可重试或继续搜索选点"
    }
    LaunchedEffect(map, styleReady, point, focusRevision) {
        if (styleReady) {
            map?.clear()
            point?.let {
                val location = LatLng(it.latitude, it.longitude)
                map?.addMarker(MarkerOptions().position(location))
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 17.0))
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).testTag("location-map")) {
        if (mapView != null) AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (!rendered || error.isNotEmpty()) Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            if (error.isEmpty()) GlassLoadingIndicator()
            Text(error.ifEmpty { "正在加载地图…" }, style = MaterialTheme.typography.bodyMedium)
            if (error.isNotEmpty()) {
                Text("搜索结果和坐标仍可选择、保存", style = MaterialTheme.typography.bodySmall)
                SystemDialogButton(onClick = retry, primary = true) { Text("重新加载地图") }
            }
        }
    }
}

internal class PluginMapLifecycle(
    private val start: () -> Unit, private val resume: () -> Unit, private val pause: () -> Unit,
    private val stop: () -> Unit, private val destroy: () -> Unit
) : AutoCloseable {
    private var started = false
    private var resumed = false
    private var closed = false
    fun update(wantsStart: Boolean, wantsResume: Boolean) {
        if (closed) return
        if (resumed && (!wantsResume || !wantsStart)) { pause(); resumed = false }
        if (started && !wantsStart) { stop(); started = false }
        if (!started && wantsStart) { start(); started = true }
        if (!resumed && wantsStart && wantsResume) { resume(); resumed = true }
    }
    override fun close() {
        if (closed) return
        update(false, false); closed = true; destroy()
    }
}

private object PluginMapNetwork {
    private var initialized = false
    @Synchronized fun initialize() {
        if (initialized) return
        org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", PluginLocationSearch.USER_AGENT).build())
            }.build())
        initialized = true
    }
}

private const val PLUGIN_OSM_STYLE = """{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#e8ecef"}},{"id":"osm","type":"raster","source":"osm"}]}"""
