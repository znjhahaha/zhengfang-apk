package com.tyust.course.update

import java.io.File
import java.util.zip.ZipFile

internal object UpdateApkAbi {
    fun verify(file: File, selectedAbi: String?, supportedAbis: List<String>) {
        val abis = try { ZipFile(file).use { zip -> zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
            .map { it.name.split('/').getOrNull(1).orEmpty() }.toSet() } }
        catch (_: Exception) { updateError("APK_ABI", "安装包架构信息无效") }
        val expected = selectedAbi?.let { setOf(it) } ?: setOf("arm64-v8a", "armeabi-v7a")
        if (abis != expected || abis.none { it in supportedAbis }) updateError("APK_ABI", "安装包架构与设备或更新清单不一致")
    }
}
