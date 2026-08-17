package com.tyust.course.ui.system

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassPerformancePolicyTest {

    @Test
    fun `android below 12 uses material fallback`() {
        val tier = GlassPerformancePolicy.resolve(
            GlassDeviceProfile(
                sdkInt = 30,
                supportedAbis = listOf("arm64-v8a"),
                isLowRamDevice = false
            )
        )

        assertEquals(GlassPerformanceTier.Material, tier)
    }

    @Test
    fun `x86 emulator is classified as balanced`() {
        val tier = GlassPerformancePolicy.resolve(
            GlassDeviceProfile(
                sdkInt = 35,
                supportedAbis = listOf("x86_64", "x86"),
                isLowRamDevice = false
            )
        )

        assertEquals(GlassPerformanceTier.Balanced, tier)
    }

    @Test
    fun `low ram arm device is classified as balanced`() {
        val tier = GlassPerformancePolicy.resolve(
            GlassDeviceProfile(
                sdkInt = 33,
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
                isLowRamDevice = true
            )
        )

        assertEquals(GlassPerformanceTier.Balanced, tier)
    }

    @Test
    fun `regular arm device keeps full optics`() {
        val tier = GlassPerformancePolicy.resolve(
            GlassDeviceProfile(
                sdkInt = 35,
                supportedAbis = listOf("arm64-v8a"),
                isLowRamDevice = false
            )
        )

        assertEquals(GlassPerformanceTier.Full, tier)
    }
}
