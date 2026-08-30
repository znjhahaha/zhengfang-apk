package com.tyust.course.ui.system.glass

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [lensCornerRadiusPx] 的形状解析。
 *
 * 着色器只有一个 `sdRoundedRect`，所有形状都要落到"一个圆角半径"上。解析错了
 * 不会报错，只会在屏幕上画出**错的轮廓**，而错法很不直观 —— 所以逐类钉住。
 */
class LensCornerRadiusTest {

    private val density = Density(3f)

    /**
     * 弹窗卡片：kyant 的连续曲率 `RoundedRectangle`。
     *
     * 这一条对应一次实拍缺陷：它不是 `CornerBasedShape`，曾经直接落到
     * `min(w,h)/2` 的兜底上 —— 320×197dp 的卡片于是被当成半径 98dp 的胶囊，
     * 折射的斜坡沿胶囊轮廓走，卡片里出现一道大弧。与 API35 对照才看出来。
     */
    @Test
    fun `kyant 连续曲率圆角矩形按自己的半径解析，不落到半短边兜底`() {
        val w = 320f * 3f
        val h = 197f * 3f
        val shape = RoundedRectangle(
            cornerRadius = 32.dp,
            style = RoundedCornerStyle.Continuous
        )
        val r = lensCornerRadiusPx(shape, w, h, density)
        assertEquals(32f * 3f, r, 0.5f)
        // 兜底值会是这个，必须**不等于**它，否则等于没解析
        assertEquals(295.5f, minOf(w, h) / 2f, 0.5f)
    }

    /** kyant 的 Capsule：半径就是半短边，与兜底同值，但要走解析而不是兜底。 */
    @Test
    fun `kyant 胶囊解析成半短边`() {
        val r = lensCornerRadiusPx(Capsule(), 600f, 150f, density)
        assertEquals(75f, r, 0.5f)
    }

    /** androidx 的 CircleShape：50% 圆角 → 半短边。圆钮走这条。 */
    @Test
    fun `androidx CircleShape 解析成半短边`() {
        val r = lensCornerRadiusPx(CircleShape, 102f, 102f, density)
        assertEquals(51f, r, 0.5f)
    }

    /** androidx 的固定 dp 圆角：按 dp 解析。 */
    @Test
    fun `androidx 固定 dp 圆角按 dp 解析`() {
        val r = lensCornerRadiusPx(RoundedCornerShape(14.dp), 400f, 200f, density)
        assertEquals(42f, r, 0.5f)
    }

    /**
     * 半径永远不超过半短边。
     *
     * 超了 `sdRoundedRect` 里的 `halfSize - r` 会出现负分量，SDF 退化：形状既不是
     * 胶囊也不再与 Compose 侧的形状重合，不重合那一圈里着色器输出透明、而库的
     * surface/highlight 照真形状画 —— 屏幕上是一层套在玻璃外面的"壳"。
     */
    @Test
    fun `半径夹到半短边以内`() {
        val r = lensCornerRadiusPx(RoundedCornerShape(100.dp), 400f, 120f, density)
        assertEquals(60f, r, 0.5f)
    }

    /**
     * 切角也是 [androidx.compose.foundation.shape.CornerBasedShape]，所以按它的
     * 角尺寸解析成圆角。单半径能给出的最接近的近似就是这个，不必特殊处理。
     */
    @Test
    fun `切角形状按角尺寸解析成同尺度的圆角`() {
        val r = lensCornerRadiusPx(CutCornerShape(8.dp), 400f, 200f, density)
        assertEquals(24f, r, 0.5f)
    }

    /**
     * 认不出的形状（任意路径）退回半短边。
     *
     * 方向是**刻意保守**的：半径宁可偏大。偏小会让折射轮廓落在真形状内部，
     * 中间那一圈就是上面说的那层"壳"。
     */
    @Test
    fun `认不出的形状退回半短边`() {
        val generic = GenericShape { size, _ ->
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width / 2f, size.height)
            close()
        }
        val r = lensCornerRadiusPx(generic, 400f, 200f, density)
        assertEquals(100f, r, 0.5f)
    }
}
