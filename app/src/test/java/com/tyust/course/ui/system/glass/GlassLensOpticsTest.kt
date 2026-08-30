package com.tyust.course.ui.system.glass

import androidx.compose.ui.unit.Density
import com.tyust.course.ui.system.GlassAccessibilityMode
import com.tyust.course.ui.system.GlassMaterialRole
import com.tyust.course.ui.system.GlassMaterialSpec
import com.tyust.course.ui.system.GlassMaterials
import com.tyust.course.ui.system.GlassRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折射光学参数的不变量。
 *
 * 这些断言全部来自实测踩坑，不是凭空的性质检查：每一条都对应一次屏幕上肉眼
 * 可见的错误。改动 [glassLensOpticsFrom] 时它们是安全网。
 */
class GlassLensOpticsTest {

    private val density = Density(3f)

    /** 指示器配方：与库的 `lens(10dp, 14dp)` 同值。 */
    private val indicator = GlassMaterialSpec(
        blurDp = 0f,
        refractionHeightDp = 10f,
        refractionAmountDp = 14f,
        surfaceAlpha = 0.1f,
        borderAlpha = 0f,
        shadowAlpha = 0.1f
    )

    /** 指示器实际尺寸：196×152，短边 152，半短边 76。 */
    private val minDimensionPx = 152f

    private fun optics(
        progress: Float,
        motion: Float = 0f
    ): GlassLensOptics = glassLensOpticsFrom(
        material = indicator,
        density = density,
        cornerRadiusPx = minDimensionPx / 2f,
        minDimensionPx = minDimensionPx,
        interactionProgress = progress,
        motionIntensity = motion,
        pressScalesRefraction = true,
        refractionFloor = 0f,
        chromaticAberrationAtRest = false
    )

    /**
     * 复现 [Modifier.glassLens] 的绘制期夹取，用元素**实测**尺寸。
     *
     * 参数刻意与标称尺寸分开：实测踩到过标称 163px / 实测 152px 的情况，
     * 而旧实现的斜坡是「占标称半短边的比例」、施加时乘实测半短边，两者不一致时
     * 比值就漂了。现在两个量都是绝对像素，这个函数存在的意义就是证明
     * **实测尺寸怎么变，比值都不变**。
     */
    private fun clamped(o: GlassLensOptics, actualMinDimPx: Float): Pair<Float, Float> {
        val maxThickness = actualMinDimPx / 2f * GLASS_LENS_THICKNESS_FRACTION_MAX
        val thickness = o.thicknessPx.coerceIn(1f, maxThickness.coerceAtLeast(1f))
        val scale = if (o.thicknessPx > 0f) thickness / o.thicknessPx else 1f
        return thickness to o.lensAmountPx * scale
    }

    private fun thicknessPx(o: GlassLensOptics): Float = clamped(o, minDimensionPx).first

    private fun amountPx(o: GlassLensOptics): Float = clamped(o, minDimensionPx).second

    @Test
    fun `静止态完全不折射`() {
        // 库的 LiquidBottomTabs 把 lens 的两个参数都乘 pressProgress，
        // 静止就是一块平的半透胶囊。已在 API 35 上截图确认。
        // 曾经给过 floor=0.42 让静止也折射，屏幕上是一圈暗边 + 鱼眼鼓包，
        // 而静止态恰恰是用户盯着看的那一态。
        val rest = optics(progress = 0f)
        assertEquals(0f, rest.lensAmountPx, 0f)
        assertEquals(0f, rest.dispersion, 0f)
    }

    @Test
    fun `静止态不出色散`() {
        assertEquals(0f, optics(progress = 0f, motion = 0f).dispersion, 0f)
    }

    @Test
    fun `位移允许大于斜坡宽度`() {
        // 库就是这么用的：amount/height = 1.4。曾经这里被 clamp 到 height×0.5，
        // 位移只有库的 1/3，折射弱到看不见——当时误判成"底图没有高频内容"，
        // 还为此在着色器里加了一层伪造微纹理。
        val pressed = optics(progress = 1f)
        assertTrue(
            "位移 ${pressed.lensAmountPx} 应大于斜坡 ${thicknessPx(pressed)}",
            pressed.lensAmountPx > thicknessPx(pressed)
        )
    }

    @Test
    fun `静止不动时的比值等于配方比值`() {
        // 无速度时两个系数都是 1，比值就是配方比值 14/10 = 1.4。
        // 有速度时比值会涨（斜坡 ×0.25、位移 ×0.35），这是**故意的** ——
        // 与 API33+ 同一条曲线，见 `随速度的曲线与API33plus一致`。
        val pressed = optics(progress = 1f, motion = 0f)
        assertEquals(1.4f, amountPx(pressed) / thicknessPx(pressed), 0.02f)
    }

    @Test
    fun `比值与元素实测尺寸无关`() {
        // 这一条是设备实测打出来的：调用方传的标称短边 163px，元素实测 152px。
        // 旧实现的斜坡是「占标称半短边的比例」，施加时却乘实测半短边，
        // 于是斜坡窄了 6.9%，位移不受影响，比值从 1.4 漂到 1.504。
        // 单元测试当时查不出来 —— 它只看得到标称值那一侧。
        val pressed = optics(progress = 1f, motion = 0f)
        for (actual in listOf(120f, 152f, 163f, 240f)) {
            val (t, a) = clamped(pressed, actual)
            assertEquals("实测短边 $actual 时比值应仍为 1.4", 1.4f, a / t, 0.02f)
        }
    }

    @Test
    fun `斜坡被夹时位移同比例缩小`() {
        // 小元素上斜坡会撞上限。此时只夹斜坡会让比值变大、边缘更陡；
        // 位移同比例缩之后，折射只是整体变弱，形状不变。
        val pressed = optics(progress = 1f, motion = 0f)
        val tiny = 40f
        val (t, a) = clamped(pressed, tiny)
        assertEquals(tiny / 2f * GLASS_LENS_THICKNESS_FRACTION_MAX, t, 0.01f)
        assertTrue("位移 $a 应小于未夹时的 ${pressed.lensAmountPx}", a < pressed.lensAmountPx)
        assertEquals(1.4f, a / t, 0.02f)
    }

    @Test
    fun `速度提高折射强度`() {
        val slow = optics(progress = 1f, motion = 0f)
        val fast = optics(progress = 1f, motion = 1f)
        assertTrue(
            "快滑位移 ${fast.lensAmountPx} 应大于慢滑 ${slow.lensAmountPx}",
            fast.lensAmountPx > slow.lensAmountPx
        )
    }

    @Test
    fun `斜坡宽度不会吃掉整个形状`() {
        // 上限 0.5 × 半短边。曾经按 indicatorHeight×0.3 算出 43px（占半短边 0.6），
        // 整个形状都落进边缘带，屏幕上是一个空心灰环。
        for (m in listOf(0f, 0.5f, 1f)) {
            val o = optics(progress = 1f, motion = m)
            val t = thicknessPx(o)
            assertTrue(
                "motion=$m 的斜坡 $t 应 ≤ 半短边的一半",
                t <= minDimensionPx / 2f * GLASS_LENS_THICKNESS_FRACTION_MAX + 1e-3f
            )
        }
    }

    @Test
    fun `位移不超过元素短边`() {
        // 唯一保留的位移防御：再大就会采到底图之外。
        for (m in listOf(0f, 0.5f, 1f)) {
            val o = optics(progress = 1f, motion = m)
            assertTrue(
                "motion=$m 的位移 ${o.lensAmountPx} 应 ≤ $minDimensionPx",
                o.lensAmountPx <= minDimensionPx
            )
        }
    }

    @Test
    fun `底部标签栏不开 depthEffect`() {
        // 库的 LiquidBottomTabs 只传两个位置参数，没有 depthEffect。
        // 开了会把梯度混向径向，胶囊中部也跟着位移，观感是一颗球而不是一片玻璃。
        assertEquals(0f, optics(progress = 1f).depthEffect, 0f)
    }

    /**
     * 离屏折射（API31/32）与 API33+ 读**同一份配方、同一条曲线**。
     *
     * 这条是目标的直接编码：低版本上要复现的是 33+ 的观感，不是做第二种效果。
     * 所以两条路的数值必须同源 —— 谁给离屏路径单独加一份 dp 覆盖，这里就该红。
     *
     * 背景：中途我为了让比值等于库的 1.4，先是直接改了共用的 `interactive`
     * 配方（连带把 33+ 的观感一起改了），后来又给离屏路径加了一份 10/14 的覆盖
     * （两条路观感从此对不上）。两个都退了。
     */
    @Test
    fun `离屏折射与API33plus读同一份配方`() {
        val shared = GlassMaterials.resolve(
            role = GlassMaterialRole.Interactive,
            accessibility = GlassAccessibilityMode(reduceMotion = false, highContrast = false),
            interactionProgress = 0f
        )
        // 满按压、无速度时，两个量都应等于配方的 dp 换算值（scale = 1）
        val o = glassLensOpticsFrom(
            material = shared,
            density = density,
            cornerRadiusPx = minDimensionPx / 2f,
            minDimensionPx = minDimensionPx,
            interactionProgress = 1f,
            pressScalesRefraction = true,
            refractionFloor = 0f
        )
        assertEquals(shared.refractionHeightDp * 3f, o.thicknessPx, 0.01f)
        assertEquals(shared.refractionAmountDp * 3f, o.lensAmountPx, 0.01f)
    }

    /**
     * 随速度的变化曲线与 33+ 逐帧一致：斜坡 ×(1+m×0.25)、位移 ×(1+m×0.35)。
     *
     * 只断言两个端点不够 —— 曾经离屏那条路用的是"两者同乘一个数"，端点（m=0）
     * 完全一致，中间全程偏差。所以这里逐点比。
     */
    @Test
    fun `随速度的曲线与API33plus一致`() {
        for (m in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val o = optics(progress = 1f, motion = m)
            assertEquals(
                "motion=$m 的斜坡宽度应为 10dp×(1+m×0.25)",
                10f * 3f * (1f + m * 0.25f),
                o.thicknessPx,
                0.01f
            )
            assertEquals(
                "motion=$m 的位移幅度应为 14dp×(1+m×0.35)",
                14f * 3f * (1f + m * 0.35f),
                o.lensAmountPx,
                0.01f
            )
        }
    }
}
