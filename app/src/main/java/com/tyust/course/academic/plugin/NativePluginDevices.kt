package com.tyust.course.academic.plugin

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

@Composable internal fun NativePluginDeviceLaunchers(state: PageInteraction) {
    val app = LocalContext.current
    val scope = rememberCoroutineScope()
    fun fail(message: String) { state.deviceResult?.completeExceptionally(PluginException(PluginErrorCode.PERMISSION_DENIED, message)) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null && result.contents.length > 4000) fail("扫码内容过长")
        else state.deviceResult?.complete(result.contents?.let { JSONObject().put("text", it) })
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = state.photoUri; state.photoUri = null
        if (success && uri != null && state.deviceResult?.isActive == true) state.deviceResult?.complete(JSONObject().put("uri", uri.toString()))
        else { uri?.let { runCatching { app.contentResolver.delete(it, null, null) } }; state.deviceResult?.complete(null) }
    }
    fun photograph() {
        val file = File.createTempFile("plugin-photo-", ".jpg", app.cacheDir)
        val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
        state.photoUri = uri
        try { camera.launch(uri) } catch (e: Exception) { file.delete(); state.photoUri = null; fail("没有可用的相机应用") }
    }
    fun locate() {
        val pending = state.deviceResult
        scope.launch {
            try {
                val location = withTimeout(20_000) { currentPluginLocation(app) }
                pending?.complete(JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
                    .put("accuracy", location.accuracy.toDouble()).put("timestamp", location.time))
            } catch (e: Exception) { pending?.completeExceptionally(PluginException(PluginErrorCode.NOT_OPEN, "定位未完成，请开启定位后重试")) }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) locate() else fail("定位权限未获允许")
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) photograph() else fail("相机权限未获允许") }
    SideEffect {
        state.launchDevice = { kind ->
            try {
                when (kind) {
                    "scan" -> scanner.launch(ScanOptions().setPrompt("${state.pluginName} · 扫码").setBeepEnabled(false).setOrientationLocked(false))
                    "photo" -> if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) photograph() else cameraPermission.launch(Manifest.permission.CAMERA)
                    "location" -> if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
                        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }
            } catch (_: Exception) { fail("设备操作暂不可用") }
        }
    }
    DisposableEffect(state) { onDispose { state.launchDevice = null } }
}

@SuppressLint("MissingPermission")
internal suspend fun currentPluginLocation(app: Context): Location = suspendCancellableCoroutine { continuation ->
    val manager = app.getSystemService(LocationManager::class.java)
    val precise = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val providers = listOfNotNull(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER.takeIf { precise }).filter { manager.isProviderEnabled(it) }
    val recent = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time in 0..60_000 }.maxByOrNull { it.time }
    if (recent != null) { continuation.resume(recent); return@suspendCancellableCoroutine }
    if (providers.isEmpty()) { continuation.cancel(); return@suspendCancellableCoroutine }
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { manager.removeUpdates(this); if (continuation.isActive) continuation.resume(location) }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
    providers.forEach { manager.requestLocationUpdates(it, 0, 0f, listener, Looper.getMainLooper()) }
}
