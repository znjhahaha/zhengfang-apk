package com.tyust.course.ui.system

/** 设备能力分类，仅供诊断与后续选择等价实现；不会关闭任何玻璃特性。 */
enum class GlassPerformanceTier {
    Material,
    Balanced,
    Full
}

data class GlassDeviceProfile(
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val isLowRamDevice: Boolean
)

object GlassPerformancePolicy {
    private const val MIN_BACKDROP_SDK = 31

    fun resolve(profile: GlassDeviceProfile): GlassPerformanceTier {
        if (profile.sdkInt < MIN_BACKDROP_SDK) return GlassPerformanceTier.Material

        val isX86Runtime = profile.supportedAbis.any { abi ->
            abi.equals("x86", ignoreCase = true) ||
                abi.equals("x86_64", ignoreCase = true)
        }
        return if (profile.isLowRamDevice || isX86Runtime) {
            GlassPerformanceTier.Balanced
        } else {
            GlassPerformanceTier.Full
        }
    }
}
