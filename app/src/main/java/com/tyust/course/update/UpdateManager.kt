package com.tyust.course.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 应用更新管理器
 * 负责检查更新、下载APK、安装APK
 */
class UpdateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "UpdateManager"
        
        // Gitee 仓库配置
        private const val VERSION_URL = "https://gitee.com/znj12345/zhengfang/raw/main/version.json"
        
        // 下载文件名
        private const val APK_FILE_NAME = "zhengfang_update.apk"
        
        @Volatile
        private var instance: UpdateManager? = null
        
        fun getInstance(context: Context): UpdateManager {
            return instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    
    /**
     * 更新信息数据类
     */
    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val forceUpdate: Boolean
    )
    
    /**
     * 获取本地版本信息
     */
    private fun getPackageInfo(): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取版本信息失败: ${e.message}")
            null
        }
    }
    
    private fun getLocalVersionCode(): Long {
        val packageInfo = getPackageInfo() ?: return 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }
    
    private fun getLocalVersionName(): String {
        return getPackageInfo()?.versionName ?: "1.0.0"
    }
    
    /**
     * 检查更新
     */
    fun checkForUpdate(callback: (UpdateInfo?) -> Unit) {
        Log.d(TAG, "检查更新: $VERSION_URL")
        
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        
        val request = Request.Builder()
            .url(VERSION_URL)
            .header("Cache-Control", "no-cache")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "检查更新失败: ${e.message}")
                mainHandler.post { callback(null) }
            }
            
            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = response.body?.string() ?: ""
                    Log.d(TAG, "版本信息: $json")
                    
                    val obj = JSONObject(json)
                    val serverVersionCode = obj.optInt("versionCode", 0)
                    val localVersionCode = getLocalVersionCode()
                    
                    Log.d(TAG, "本地版本: $localVersionCode, 服务器版本: $serverVersionCode")
                    
                    if (serverVersionCode > localVersionCode) {
                        val updateInfo = UpdateInfo(
                            versionCode = serverVersionCode,
                            versionName = obj.optString("versionName", ""),
                            releaseNotes = obj.optString("releaseNotes", ""),
                            downloadUrl = obj.optString("downloadUrl", ""),
                            forceUpdate = obj.optBoolean("forceUpdate", false)
                        )
                        mainHandler.post { callback(updateInfo) }
                    } else {
                        mainHandler.post { callback(null) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析版本信息失败: ${e.message}")
                    mainHandler.post { callback(null) }
                }
            }
        })
    }
    
    /**
     * 下载APK
     */
    fun downloadApk(
        downloadUrl: String,
        onProgress: (Int) -> Unit,
        onComplete: (File?) -> Unit
    ) {
        Log.d(TAG, "开始下载: $downloadUrl")
        
        // 删除旧的APK文件
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
        if (apkFile.exists()) {
            apkFile.delete()
        }
        
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        
        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setTitle("正在下载更新")
            setDescription("正在下载新版本...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        
        downloadId = downloadManager.enqueue(request)
        
        // 监听下载进度
        Thread {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                
                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val bytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    
                    if (statusIndex >= 0 && bytesIndex >= 0 && totalIndex >= 0) {
                        val status = cursor.getInt(statusIndex)
                        val bytesDownloaded = cursor.getLong(bytesIndex)
                        val bytesTotal = cursor.getLong(totalIndex)
                        
                        if (bytesTotal > 0) {
                            val progress = ((bytesDownloaded * 100) / bytesTotal).toInt()
                            onProgress(progress)
                        }
                        
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                downloading = false
                                mainHandler.post { onComplete(apkFile) }
                            }
                            DownloadManager.STATUS_FAILED -> {
                                downloading = false
                                mainHandler.post { onComplete(null) }
                            }
                        }
                    }
                }
                cursor.close()
                
                if (downloading) {
                    Thread.sleep(500)
                }
            }
        }.start()
    }
    
    /**
     * 安装APK
     */
    fun installApk(apkFile: File) {
        Log.d(TAG, "安装APK: ${apkFile.absolutePath}")
        
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile
                    )
                } else {
                    Uri.fromFile(apkFile)
                }
                
                setDataAndType(uri, "application/vnd.android.package-archive")
            }
            
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "安装APK失败: ${e.message}")
        }
    }
    
    /**
     * 取消下载
     */
    fun cancelDownload() {
        if (downloadId != -1L) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.remove(downloadId)
            downloadId = -1
        }
    }
    
    /**
     * 获取当前版本名
     */
    fun getCurrentVersionName(): String {
        return getLocalVersionName()
    }
    
    /**
     * 获取当前版本号
     */
    fun getCurrentVersionCode(): Int {
        return getLocalVersionCode().toInt()
    }
}

