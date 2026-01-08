package com.tyust.course.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * 应用签名校验工具类
 * 用于防止二次打包：如果 APK 被反编译后重新签名，此处校验将失败
 */
object SignatureUtils {
    private const val TAG = "SignatureUtils"
    
    // ⚠️ 正式发布签名的 SHA-256 哈希值（已配置）
    private const val EXPECTED_SIGNATURE = "C2099CC533D249B0C68A531036486712FC09F5F1F8F8167AFF4E6F8F2C8CDE7F"
    
    /**
     * 校验当前应用的签名是否与预期一致
     * @return true = 签名匹配（未被篡改），false = 签名不匹配（可能被二次打包）
     */
    fun verifySignature(context: Context): Boolean {
        val currentSignature = getAppSignature(context)
        
        // 开发阶段：打印当前签名，方便获取正式签名值
        Log.d(TAG, "Current App Signature: $currentSignature")
        
        // 如果还没有设置预期签名，跳过校验（开发模式）
        if (EXPECTED_SIGNATURE == "YOUR_RELEASE_SIGNATURE_SHA256_HERE") {
            Log.w(TAG, "⚠️ 签名校验已跳过：请在正式发布前设置 EXPECTED_SIGNATURE")
            return true
        }
        
        val isValid = currentSignature.equals(EXPECTED_SIGNATURE, ignoreCase = true)
        
        if (!isValid) {
            Log.e(TAG, "❌ 签名校验失败！当前签名: $currentSignature")
        } else {
            Log.d(TAG, "✅ 签名校验通过")
        }
        
        return isValid
    }
    
    /**
     * 获取当前应用的签名 SHA-256 哈希值
     */
    @Suppress("DEPRECATION")
    fun getAppSignature(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }
            
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                packageInfo.signatures
            }
            
            if (signatures.isNullOrEmpty()) {
                Log.w(TAG, "无法获取签名信息")
                return ""
            }
            
            // 取第一个签名并计算 SHA-256
            val signature = signatures[0]
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(signature.toByteArray())
            
            // 转换为十六进制字符串
            digest.joinToString("") { "%02X".format(it) }
            
        } catch (e: Exception) {
            Log.e(TAG, "获取签名失败: ${e.message}")
            ""
        }
    }
}
