package com.tyust.course.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Account-independent update state. UI instances never own network calls or files. */
class UpdateManager internal constructor(private val context: Context,
    private val testKeys: Map<String, java.security.PublicKey>? = null,
    private val testSources: ((String) -> List<String>)? = null,
    private val allowedUrl: (String) -> Boolean = UpdateManifestVerifier::validUrl,
    private val deviceAbis: () -> List<String> = { Build.SUPPORTED_ABIS.toList() }) {
    enum class Check { IDLE, CHECKING, AVAILABLE, UP_TO_DATE, FAILED, CACHED }
    enum class Phase { IDLE, PREPARING, CONNECTING, DOWNLOADING, SWITCHING, WAITING_NETWORK, PAUSED, VERIFYING, READY, FAILED, CANCELLED }
    data class State(val manifest: UpdateManifest? = null, val check: Check = Check.IDLE,
        val phase: Phase = Phase.IDLE, val bytes: Long = 0, val mirror: UpdateMirror? = null,
        val message: String = "", val errorCode: String = "", val showDialog: Boolean = false,
        val testChannel: Boolean = false) {
        val active get() = phase in setOf(Phase.PREPARING, Phase.CONNECTING, Phase.DOWNLOADING, Phase.SWITCHING, Phase.WAITING_NETWORK, Phase.VERIFYING)
        val progress get() = manifest?.let { (bytes * 100 / it.size).coerceIn(0, 100).toInt() } ?: 0
    }
    companion object {
        @Volatile private var instance: UpdateManager? = null
        fun getInstance(context: Context): UpdateManager = instance ?: synchronized(this) {
            instance ?: UpdateManager(context.applicationContext).also { instance = it }
        }
        private const val SIX_HOURS = 6 * 60 * 60 * 1000L
        private const val DAY = 24 * 60 * 60 * 1000L
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("app_update_v2", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "app-updates").apply { mkdirs() }
    private val partial = File(directory, "download.part")
    private val completed = File(directory, "verified.apk")
    private val taskRecord = AtomicFile(File(directory, "task.json"))
    private val keys by lazy {
        testKeys ?: run {
        val json = context.assets.open("app-update-keys.json").bufferedReader().use { UpdateJson.parse(it.readText()) }
        json.keys().asSequence().associateWith { UpdateManifestVerifier.key(json.getString(it)) }
        }
    }
    private val mutable = MutableStateFlow(State(testChannel = prefs.getBoolean("test", false)))
    val state = mutable.asStateFlow()
    private val transfer = UpdateTransfer()
    private val http = UpdateTransfer.client().newBuilder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private val checkMutex = Mutex()
    private val commands = Mutex()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var task: UpdateManifest? = null
    private var taskEtag: String? = null
    private var taskMirror: String? = null
    private var requestedMirror: String? = null
    private var lastSave = 0L
    private val restore = scope.launch { restoreTask() }
    private val channel get() = if (mutable.value.testChannel) "test" else "stable"

    fun getCurrentVersionName(): String = packageInfo().versionName.orEmpty()
    fun getCurrentVersionCode(): Int = versionCode(packageInfo()).toInt()
    @Suppress("DEPRECATION") private fun packageInfo(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION") private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun cacheFile(channel: String) = AtomicFile(File(directory, "$channel-manifest.json"))
    private fun cached(channel: String): UpdateManifest? = runCatching {
        UpdateManifestVerifier.verify(cacheFile(channel).readFully().toString(Charsets.UTF_8), keys, channel)
    }.getOrNull()
    private fun write(file: AtomicFile, text: String) {
        val output = file.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    private fun sources(channel: String): List<String> {
        testSources?.let { return it(channel) }
        val host = if (channel == "test") "dl-test.hidisiwa.xyz" else "dl.hidisiwa.xyz"
        val raw = "https://raw.githubusercontent.com/znjhahaha/zhengfang-apk/updates/$channel.json"
        return listOf("https://$host/$channel.json", raw, "https://gh-proxy.com/$raw",
            "https://gitee.com/znj12345/zhengfang/raw/main/app-update-$channel.json")
    }
    private suspend fun fetch(url: String, selectedChannel: String): UpdateManifest {
        var target = url
        repeat(6) {
            if (!allowedUrl(target)) updateError("MANIFEST_URL", "更新地址不受支持")
            val key = "etag:$url"; val stored = cached(selectedChannel)
            val builder = Request.Builder().url(target).header("Cache-Control", "no-cache")
            if (stored != null) prefs.getString(key, null)?.let { builder.header("If-None-Match", it) }
            http.newCall(builder.build()).awaitResponse().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    target = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                        ?: updateError("MANIFEST_REDIRECT", "更新地址跳转失败")
                    return@use
                }
                if (response.code == 304 && stored != null && prefs.getString("etag-payload:$url", null) == stored.envelope) return stored
                if (!response.isSuccessful) updateError("MANIFEST_HTTP", "更新来源暂时不可用（HTTP ${response.code}）")
                val body = response.body ?: updateError("MANIFEST_EMPTY", "更新来源没有返回清单")
                if (body.contentLength() > 128 * 1024) updateError("MANIFEST_SIZE", "更新清单过大")
                val bytes = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
                        if (output.size() + n > 128 * 1024) updateError("MANIFEST_SIZE", "更新清单过大")
                        output.write(buffer, 0, n)
                    }; output.toByteArray()
                }
                val result = UpdateManifestVerifier.verify(bytes.toString(Charsets.UTF_8), keys, selectedChannel)
                response.header("ETag")?.let { prefs.edit().putString(key, it).putString("etag-payload:$url", result.envelope).apply() }
                return result
            }
        }
        updateError("MANIFEST_REDIRECT", "更新地址跳转过多")
    }

    /** Check calls coalesce; callers observe the one StateFlow. */
    fun checkForUpdate(manual: Boolean = false) {
        scope.launch {
            restore.join()
            checkMutex.withLock {
                if (checkJob?.isActive == true) { if (manual) mutable.update { it.copy(showDialog = true) }; return@withLock }
                val selected = channel; val now = System.currentTimeMillis()
                if (!manual && now - prefs.getLong("last-check:$selected", 0) < SIX_HOURS) return@withLock
                prefs.edit().putLong("last-check:$selected", now).apply()
                mutable.update { it.copy(check = Check.CHECKING, showDialog = it.showDialog || manual, message = "正在检查更新…", errorCode = "") }
                checkJob = scope.launch {
                    try {
                    val previous = cached(selected)
                    val results = java.util.Collections.synchronizedList(mutableListOf<UpdateManifest>())
                    withTimeoutOrNull(12_000) {
                        val semaphore = Semaphore(2)
                        coroutineScope { sources(selected).map { url -> launch {
                            semaphore.withPermit {
                                try { results.add(fetch(url, selected)) }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { /* A failed source never becomes "up to date". */ }
                            }
                        } }.joinAll() }
                    }
                    if (channel != selected) return@launch
                    val chosen = UpdateManifestVerifier.choose(results.toList(), previous)
                    val value = (chosen ?: previous)?.forDevice(deviceAbis())
                    if (chosen != null) write(cacheFile(selected), chosen.envelope)
                    val available = value != null && value.versionCode > getCurrentVersionCode() && value.minSdk <= Build.VERSION.SDK_INT
                    val canRemind = value?.let { it.forceUpdate || now - prefs.getLong("later:${it.channel}:${it.versionCode}", 0) >= DAY } ?: false
                    mutable.update { s ->
                        val activeTask = s.active || s.phase in setOf(Phase.PAUSED, Phase.READY)
                        s.copy(manifest = if (activeTask) s.manifest else value,
                            check = when { chosen == null && previous != null -> Check.CACHED; chosen == null -> Check.FAILED; available -> Check.AVAILABLE; else -> Check.UP_TO_DATE },
                            showDialog = s.showDialog || (chosen != null && available && canRemind),
                            message = if (activeTask) s.message else when { chosen == null && previous != null -> "检查失败，显示上次验证的更新信息"; chosen == null -> "检查更新失败，请重试或更换网络";
                                value != null && value.minSdk > Build.VERSION.SDK_INT -> "新版本不支持当前 Android 版本";
                                available -> "发现新版本"; else -> "已是最新版本" },
                            errorCode = if (activeTask) s.errorCode else if (chosen == null) "CHECK_FAILED" else "")
                    }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (channel == selected) mutable.update { it.copy(check = Check.FAILED, message = (e as? UpdateFailure)?.message ?: "检查更新失败，请重试", errorCode = (e as? UpdateFailure)?.code ?: "CHECK_FAILED") } }
                }
            }
        }
    }
    fun dismiss() {
        mutable.value.manifest?.let { prefs.edit().putLong("later:${it.channel}:${it.versionCode}", System.currentTimeMillis()).apply() }
        mutable.update { it.copy(showDialog = false) }
    }
    fun showDownload() { mutable.update { it.copy(showDialog = true) } }
    fun setTestChannel(enabled: Boolean) {
        scope.launch { commands.withLock {
            restore.join(); if (mutable.value.active) return@withLock
            downloadJob?.cancelAndJoin()
            checkJob?.cancelAndJoin(); taskRecord.delete(); partial.delete(); completed.delete(); task = null
            prefs.edit().putBoolean("test", enabled).apply()
            mutable.value = State(testChannel = enabled)
        } }
    }
    fun startDownload(mirrorId: String? = null, testCurrent: Boolean = false) {
        scope.launch { commands.withLock {
            restore.join(); if (mutable.value.active) return@withLock
            downloadJob?.cancelAndJoin()
            val manifest = mutable.value.manifest ?: return@withLock
            if (context.packageName != manifest.packageName || !manifest.isInstallCandidate(getCurrentVersionCode(), Build.VERSION.SDK_INT, testCurrent && channel == "test")) return@withLock
            if (task?.identity != manifest.identity) { partial.delete(); completed.delete(); taskEtag = null; taskMirror = null }
            task = manifest; requestedMirror = mirrorId
            mutable.update { it.copy(phase = Phase.PREPARING, bytes = partial.length(), message = "正在准备下载", errorCode = "", showDialog = true) }
            try {
                persistTask()
                ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java))
            } catch (_: Exception) { mutable.update { it.copy(phase = Phase.FAILED, message = "无法启动下载，请返回前台重试", errorCode = "SERVICE_START") } }
        } }
    }
    fun pauseDownload(cancel: Boolean = false) {
        transfer.cancel()
        scope.launch { commands.withLock {
            downloadJob?.cancelAndJoin()
            if (cancel) { partial.delete(); completed.delete(); taskRecord.delete(); task = null; taskEtag = null; taskMirror = null }
            mutable.update { it.copy(phase = if (cancel) Phase.CANCELLED else Phase.PAUSED,
                bytes = if (cancel) 0 else partial.length(), message = if (cancel) "下载已取消" else "已暂停，可继续下载") }
            if (!cancel) persistTask()
            context.stopService(Intent(context, UpdateDownloadService::class.java))
        } }
    }
    internal fun serviceStopped() {
        if (mutable.value.active) { transfer.cancel(); downloadJob?.cancel(); mutable.update { it.copy(phase = Phase.PAUSED, message = "下载已暂停，可继续下载") } }
    }
    private fun online(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    internal fun runDownload() {
        if (downloadJob?.isActive == true || mutable.value.phase != Phase.PREPARING) return
        downloadJob = scope.launch {
            val manifest = task ?: return@launch
            val deadline = android.os.SystemClock.elapsedRealtime() + 20 * 60 * 1000
            val order = listOfNotNull(requestedMirror, prefs.getString("successful-mirror", null), "cf", "gh-proxy", "ghproxy-net", "github")
            val mirrors = manifest.mirrors.sortedBy { order.indexOf(it.id).let { n -> if (n < 0) Int.MAX_VALUE else n } }
            var finalMessage = "下载线路暂时不可用，已保留断点"; var finalCode = "DOWNLOAD_FAILED"
            try {
                var index = 0
                while (index < mirrors.size) {
                    currentCoroutineContext().ensureActive()
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) updateError("DOWNLOAD_BUDGET", "下载时间较长，已暂停并保留断点")
                    if (!online()) {
                        mutable.update { it.copy(phase = Phase.WAITING_NETWORK, message = "网络已断开，等待连接…") }; delay(1000); continue
                    }
                    val mirror = mirrors[index]
                    mutable.update { it.copy(phase = if (index == 0) Phase.CONNECTING else Phase.SWITCHING, mirror = mirror,
                        message = if (index == 0) "正在连接 ${mirror.name}" else "正在切换到 ${mirror.name}", errorCode = "") }
                    try {
                        if (directory.usableSpace < manifest.size - partial.length() + 2 * 1024 * 1024) updateError("DISK_FULL", "存储空间不足，请清理后重试")
                        val oldEtag = taskEtag.takeIf { taskMirror == mirror.id }
                        taskMirror = mirror.id; taskEtag = oldEtag
                        transfer.download(manifest, mirror, partial, oldEtag, deadline - android.os.SystemClock.elapsedRealtime()) { bytes, etag ->
                            taskEtag = etag
                            mutable.update { it.copy(phase = Phase.DOWNLOADING, bytes = bytes, mirror = mirror, message = "正在下载") }
                            if (android.os.SystemClock.elapsedRealtime() - lastSave > 1000) { persistTask(); lastSave = android.os.SystemClock.elapsedRealtime() }
                        }
                        mutable.update { it.copy(phase = Phase.VERIFYING, message = "下载完成，正在校验…") }
                        verifyApk(manifest, partial)
                        if (!partial.renameTo(completed)) updateError("FILE_SAVE", "无法保存安装包，请重试")
                        prefs.edit().putString("successful-mirror", mirror.id).apply()
                        mutable.update { it.copy(phase = Phase.READY, bytes = manifest.size, message = "校验通过，点击安装") }
                        persistTask(); return@launch
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (!online()) continue
                        finalMessage = (e as? UpdateFailure)?.message ?: "下载超时或连接中断，已保留断点"
                        finalCode = (e as? UpdateFailure)?.code ?: "DOWNLOAD_NETWORK"
                        if (finalCode in setOf("APK_HASH", "APK_IDENTITY", "APK_SIGNATURE", "DOWNLOAD_SIZE")) { partial.delete(); taskEtag = null }
                        if (finalCode == "DISK_FULL") break
                        index++
                    }
                }
                mutable.update { it.copy(phase = Phase.PAUSED, bytes = partial.length(), message = finalMessage, errorCode = finalCode) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(phase = Phase.PAUSED, message = (e as? UpdateFailure)?.message ?: "下载已暂停，请重试", errorCode = (e as? UpdateFailure)?.code ?: "DOWNLOAD_FAILED") } }
            finally {
                if (mutable.value.active) mutable.update { it.copy(phase = Phase.PAUSED, message = "下载已暂停，可继续下载") }
                runCatching { persistTask() }
            }
        }
    }
    @Suppress("DEPRECATION") internal fun verifyApk(manifest: UpdateManifest, file: File) {
        if (file.length() != manifest.size || UpdateTransfer.digest(file) != manifest.sha256) updateError("APK_HASH", "安装包完整性校验失败，已停止安装")
        if (manifest.artifacts.isNotEmpty()) UpdateApkAbi.verify(file, manifest.selectedAbi, deviceAbis())
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: updateError("APK_IDENTITY", "无法识别安装包")
        if (apk.packageName != manifest.packageName || versionCode(apk) != manifest.versionCode.toLong() || apk.versionName != manifest.versionName)
            updateError("APK_IDENTITY", "安装包身份与更新清单不一致")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        fun signatures(info: PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()
        if (signatures(installed).isEmpty() || signatures(apk) != signatures(installed)) updateError("APK_SIGNATURE", "安装包不是当前应用的官方签名")
    }
    fun installReady() {
        scope.launch { commands.withLock {
            if (mutable.value.phase != Phase.READY) return@withLock
            val manifest = task ?: return@withLock
            try {
                verifyApk(manifest, completed)
                if (manifest.versionCode < getCurrentVersionCode() || (manifest.versionCode == getCurrentVersionCode() && manifest.channel != "test")) updateError("APK_DOWNGRADE", "不能安装相同或较旧版本")
                withContext(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                        mutable.update { it.copy(message = "请允许安装此来源，返回后点击安装即可") }
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", completed)
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }
            } catch (e: Exception) { mutable.update { it.copy(message = (e as? UpdateFailure)?.message ?: "无法打开安装器，请重试", errorCode = (e as? UpdateFailure)?.code ?: "INSTALL_FAILED") } }
        } }
    }
    fun openBrowser(mirror: UpdateMirror) {
        val snapshot = state.value
        val manifest = snapshot.manifest ?: return
        if (mirror !in manifest.mirrors || !manifest.isInstallCandidate(getCurrentVersionCode(), Build.VERSION.SDK_INT, snapshot.testChannel)) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mirror.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    private fun persistTask() {
        val manifest = task ?: return
        val json = org.json.JSONObject().put("envelope", manifest.envelope).put("channel", manifest.channel)
            .put("artifactAbi", manifest.selectedAbi ?: "universal").put("artifactIdentity", manifest.identity)
            .put("mirror", taskMirror ?: "").put("etag", taskEtag ?: "").put("ready", mutable.value.phase == Phase.READY)
        write(taskRecord, json.toString())
    }
    internal fun close() { transfer.cancel(); scope.cancel() }
    private fun restoreTask() {
        runCatching {
            val json = UpdateJson.parse(taskRecord.readFully().toString(Charsets.UTF_8))
            val original = UpdateManifestVerifier.verify(json.getString("envelope"), keys, channel)
            val abi = json.optString("artifactAbi", "universal").takeUnless { it == "universal" }
            if (abi != null && abi !in deviceAbis()) updateError("APK_ABI", "设备架构与上次下载不一致")
            val manifest = original.forAbi(abi)
            if (json.has("artifactIdentity") && json.getString("artifactIdentity") != manifest.identity || abi != null && !json.has("artifactIdentity")) updateError("APK_IDENTITY", "下载记录与签名安装包不一致")
            if (manifest.versionCode < getCurrentVersionCode() || (manifest.versionCode == getCurrentVersionCode() && manifest.channel != "test")) { partial.delete(); completed.delete(); taskRecord.delete(); return }
            task = manifest; taskMirror = json.optString("mirror").ifBlank { null }; taskEtag = json.optString("etag").ifBlank { null }
            val ready = completed.exists() && runCatching { verifyApk(manifest, completed) }.isSuccess
            mutable.update { it.copy(manifest = manifest, phase = if (ready) Phase.READY else Phase.PAUSED,
                bytes = if (ready) manifest.size else partial.length(), message = if (ready) "安装包已就绪" else "上次下载已暂停，可继续下载") }
        }
    }
}
