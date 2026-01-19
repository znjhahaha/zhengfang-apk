package com.tyust.course.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object LogUtils {

    fun exportLogs(context: Context) {
        val logFile = File(context.cacheDir, "logs/app_log.txt")
        if (!logFile.parentFile.exists()) {
            logFile.parentFile.mkdirs()
        }

        try {
            val pid = android.os.Process.myPid()
            val process = Runtime.getRuntime().exec("logcat -d --pid=$pid")
            val inputStream = process.inputStream
            val outputStream = FileOutputStream(logFile)
            
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            outputStream.close()
            inputStream.close()
            
            shareFile(context, logFile)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun shareFile(context: Context, file: File) {
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, file)
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(intent, "导出日志"))
    }
}
