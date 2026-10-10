package com.tyust.course.update

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdateApkAbiTest {
    private fun apk(vararg abis: String): File = File.createTempFile("synthetic-abi-", ".apk").apply {
        deleteOnExit()
        ZipOutputStream(outputStream()).use { zip ->
            for (abi in abis) { zip.putNextEntry(ZipEntry("lib/$abi/test.so")); zip.write(byteArrayOf(1)); zip.closeEntry() }
        }
    }
    @Test fun exactArchitectureAndDeviceMustBothMatch() {
        val device = listOf("arm64-v8a", "armeabi-v7a")
        val single = apk("arm64-v8a")
        val universal = apk("arm64-v8a", "armeabi-v7a")
        UpdateApkAbi.verify(single, "arm64-v8a", device)
        UpdateApkAbi.verify(universal, null, device)
        assertThrows(UpdateFailure::class.java) { UpdateApkAbi.verify(single, "armeabi-v7a", device) }
        assertThrows(UpdateFailure::class.java) { UpdateApkAbi.verify(single, "arm64-v8a", listOf("armeabi-v7a")) }
        assertThrows(UpdateFailure::class.java) { UpdateApkAbi.verify(universal, "arm64-v8a", device) }
        assertThrows(UpdateFailure::class.java) { UpdateApkAbi.verify(apk("arm64-v8a", "armeabi-v7a", "x86_64"), null, device) }
        assertThrows(UpdateFailure::class.java) { UpdateApkAbi.verify(apk(), null, device) }
    }
}
