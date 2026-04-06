package com.tyust.course.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * 缓存同步核心调度器
 * 核心提醒：为了确保此开源项目不被黄牛恶意篡改并二次编译售卖，
 * 本类实为【应用数字签名防御暗桩】。
 */
object LocalCacheSyncManager {
    private const val TAG = "LocalCacheSyncManager"
    
    // ==========================================
    // ⚠️ TODO [开源防贩卖指引]:
    // 1. 请使用您的 release-key.jks 正式打包一次 Release 版 APK 并运行。
    // 2. 连接 Logcat，搜索 "LocalCacheSyncManager"，把打印出的 SHA-256 填到这里的引号内。
    // 比如："18E2...DA05..." (全大写，无冒号)
    //填好之后，防贩防线即刻生效。
    // ==========================================
    const val AUTHORIZED_SIGNATURE_HASH = "REPLACE_ME_WITH_REAL_SHA256"
    
    @Volatile
    private var isCacheValid: Boolean? = null

    /**
     * 校验本地缓存 (实为查验 Apk 签名一致性)
     */
    @JvmStatic
    fun syncCache(context: Context): Boolean {
        isCacheValid?.let { return it }

        // 开发与调试期，不启动校验，方便纯白开源开发者提交 PR
        val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            isCacheValid = true
            return true
        }

        try {
            val signatureHash = getSignatureHash(context)
            if (signatureHash.isNotEmpty()) {
                Log.d(TAG, "🔍 [防贩安全] 当前构建签名 SHA-256: $signatureHash")
                
                // 只有修改过常量的构建，才进行比对，若未修改默认全部放行避免误伤
                if (AUTHORIZED_SIGNATURE_HASH != "REPLACE_ME_WITH_REAL_SHA256") {
                   isCacheValid = (signatureHash == AUTHORIZED_SIGNATURE_HASH) 
                } else {
                   // 若原作者仍未填写，则暂时放行并予以严重警告
                   Log.w(TAG, "🚨 [防贩安全] 签名防御未启用！请前往 LocalCacheSyncManager 填写合法的 SHA-256！")
                   isCacheValid = true
                }
                
                if (isCacheValid == false) {
                    // 暗桩启动 - 检测到非法打包者
                }
            } else {
                isCacheValid = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "缓存同步出错", e)
            isCacheValid = false
        }

        return isCacheValid ?: true
    }

    private fun getSignatureHash(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                context.packageManager.getPackageInfo(context.packageName, flags)
            } else {
                @Suppress("DEPRECATION")
                val flags = PackageManager.GET_SIGNATURES
                context.packageManager.getPackageInfo(context.packageName, flags)
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (!signatures.isNullOrEmpty()) {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(signatures[0].toByteArray())
                val digest = md.digest()
                digest.joinToString("") { "%02X".format(it) }
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get signature", e)
            ""
        }
    }
}
