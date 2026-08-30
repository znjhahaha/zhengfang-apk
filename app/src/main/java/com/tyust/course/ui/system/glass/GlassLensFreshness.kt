package com.tyust.course.ui.system.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 决定 API 31/32 折射底图**何时**重拍。
 *
 * ## 为什么需要它
 *
 * 底图是「壁纸 + 页面内容 + tint 文字」的一张快照。壁纸与文字是静态的，
 * **页面内容会随滚动移动**。而底图只在版本号变化时重拍，于是滚动之后折射里
 * 还是启动那一刻的画面：实测滚 3 次之后重拍次数仍然是 2，
 * 屏幕上表现为胶囊内外的卡片边缘错开一截（中心位移为 0，本不该错开）。
 *
 * ## 为什么不每帧重拍
 *
 * 实测重拍一次 5.1ms（MuMu / API 32），其中 4.6ms 是 Picture 回放本身的 GPU
 * 开销——壁纸的几个大半径径向渐变 + 微纹理平铺 + 页面内容 + 文字层。
 * 换成常驻 HardwareRenderer + SurfaceTexture 的零回读通路也没用：
 * 中位 4.69ms、p90 15.99ms，反而更差，因为 HardwareRenderer 按 vsync 节拍走
 * （`syncAndDraw` 持续返回 status 8）。这条路已验证并放弃，不要再试。
 *
 * ## 所以：按需重拍
 *
 * - 滚动期间**限频**重拍（默认 100ms 一次 ≈ 5% 开销），折射内容跟得上但不拖帧；
 * - 滚动停下后再补一次，因为用户真正盯着看的是静止态；
 * - 静止时**零开销**，一次都不拍。
 *
 * 滚动中的底图会落后一两帧。这不要紧：错位只发生在边缘带那几像素内，
 * 而整条底栏本身是模糊的；「永远不更新」才是肉眼可见的错。
 */
class GlassLensFreshness(
    /** 滚动期间两次重拍的最小间隔（毫秒）。 */
    private val throttleMs: Long = 100L
) {

    /** 底图内容版本。变化即触发重拍。 */
    var version by mutableIntStateOf(0)
        private set

    private var lastBumpUptimeMs = 0L

    /** 有滚动发生。限频提升版本号。 */
    fun onScroll() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBumpUptimeMs >= throttleMs) {
            lastBumpUptimeMs = now
            version++
        }
    }

    /** 滚动停下（或任何"内容已定"的时刻）。无条件补一次。 */
    fun onSettled() {
        lastBumpUptimeMs = android.os.SystemClock.uptimeMillis()
        version++
    }
}
