package com.tyust.course.ui.system

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.tyust.course.BottomNavItem
import com.tyust.course.ui.system.glass.DampedDragAnimation
import com.tyust.course.ui.system.glass.GlassLensFreshness
import com.tyust.course.ui.system.glass.GlassLensTransform
import com.tyust.course.ui.system.glass.chromaticFringe
import com.tyust.course.ui.system.glass.drawBlurred
import com.tyust.course.ui.system.glass.glassLens
import com.tyust.course.ui.system.glass.glassLensAnchor
import com.tyust.course.ui.system.glass.isGlassLensApplicable
import com.tyust.course.ui.system.glass.glassLensOpticsFrom
import com.tyust.course.ui.system.glass.motionIntensityFromVelocity
import com.tyust.course.ui.system.glass.rememberGlassLensAnchor
import com.tyust.course.ui.system.glass.resolvePhysicalLens
import com.tyust.course.ui.theme.MotionSpring
import com.tyust.course.ui.theme.NavSelectedAccentDark
import com.tyust.course.ui.theme.NavSelectedAccentLight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * 底栏块的几何。**轨道高与内容留白必须来自同一处。**
 *
 * 之前不是这样：底栏自己是 `8 + 72 + 8 + navigationBars`，而各页的内容留白读
 * `LocalAppOverlayBottomInset`，那里写死 96dp——那是照着手势条（24dp）量出来的。
 * 换到三键导航（48dp）底栏实际 136dp 高，列表末项就有 16dp 藏在栏后面。
 */
object NavBarMetrics {
    /**
     * 轨道高。**有真折射就给得开**，短屏统一收一档。
     *
     * 判据是"这台设备出不出真折射"，不是"平台有没有 AGSL"。API31/32 现在由离屏
     * GL 出同一份折射（[isGlassLensApplicable]），几何也必须跟上 —— 否则 32 上是
     * 64dp 的窄轨配 56dp 的滑块，滑块几乎顶满轨道，边缘折射被轨道内侧压掉一半。
     *
     * 改这里会连带改 [contentInset]，即全 App 各页的底部留白。这是有意的：两者
     * 本来就必须同源，脱钩过一次，末项被底栏压住。
     */
    @Composable
    fun trackHeight(): Dp {
        val metrics = rememberScreenMetrics()
        return if (isRuntimeShaderTrulySupported() || isGlassLensApplicable()) {
            metrics.tall(72.dp, 62.dp)
        } else {
            metrics.tall(64.dp, 58.dp)
        }
    }

    /** 轨道到屏幕边缘的纵向留白。 */
    @Composable
    fun blockPadding(): Dp = rememberScreenMetrics().tall(8.dp, 6.dp)

    /** 轨道到屏幕边缘的横向留白。窄屏收一点，每个 tab 才多分到几 dp。 */
    @Composable
    fun horizontalPadding(): Dp = rememberScreenMetrics().wide(16.dp, 10.dp)

    /**
     * 内容要避开的高度。
     *
     * 调用方（各页的 `contentPadding`）还会各自再加 24dp，两者合起来正好在底栏上缘
     * 留 8dp 的缝——所以这里要把那 24dp 抵掉一部分，减 16dp。
     *
     * 代入 20:9 手势条（6+72+24）算出来正好是原先写死的那个 **96dp**，
     * 于是 20:9 的留白一个 dp 都不变；换到三键导航（48dp）它自己长到 120dp，
     * 末项才不会再被底栏压住。
     */
    @Composable
    fun contentInset(): Dp {
        val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        return (blockPadding() * 2 + trackHeight() + navigationBar - 16.dp)
            .coerceAtLeast(0.dp)
    }
}

/**
 * 选中指示器的静止折射下限 —— **0，静止完全不折射**。
 *
 * **两条路必须读这一个值**：API33+ 的 `resolvePhysicalLens` 和 API31/32 的
 * `glassLensOpticsFrom`。曾经两处各写一个字面量（0.42 / 0），于是同一个控件在
 * 两种平台上静止态根本不是一回事 —— 33+ 的边被折射软化，32 的是硬边，
 * 描边亮度差两倍（实测 +9.9% vs +21.2%）。
 *
 * ## 为什么是 0
 *
 * 库自己的 LiquidBottomTabs 指示器写的是
 * `lens(10dp * progress, 14dp * progress)` —— `progress` 是 pressProgress，
 * 静止时为 0，所以**一个像素都不折射**，没有任何下限。
 *
 * 这里曾经是 0.42，理由写的是"静止保留轻折射，玻璃看着是凸起的镜片而不是贴纸"。
 * 那是我自己加的观感，不是库的行为，而且它在 31/32 上后果更重：那条路采的是
 * 模糊过的底图，底图里页面的彩色内容（课表的课程块、成绩的进度条、设置的图标）
 * 被 blur 摊开成大色斑，静止折射把色斑沿胶囊边缘挤压一圈，屏幕上就是指示器边上
 * 一条青蓝/粉的彩边 —— 用户三次指出"静止状态有折射"，指的就是它。
 * 33+ 的页面内容通常不在底栏下面，所以同一个 0.42 在那边不明显。
 */
private const val NavIndicatorRefractionFloor = 0f

@Composable
fun CapsuleNavigationBar(
    items: List<BottomNavItem>,
    selectedTab: Int,
    onTabSelect: (Int) -> Unit,
    minimized: Boolean = false,
    onExpandRequest: () -> Unit = {},
    backdrop: Backdrop? = LocalAppBackdrop.current,
    /**
     * API 31/32 折射底图的新鲜度信号。页面滚动后底图必须重拍，否则折射里
     * 是启动那一刻的内容。传 null 则底图只在换 tab / 换主题时重拍。
     */
    lensFreshness: GlassLensFreshness? = null,
    modifier: Modifier = Modifier
) {
    val useGlass = backdrop != null && isBackdropSupported()
    val regionState = rememberWallpaperRegionState()
    val appearance = rememberWallpaperRegionAppearance(regionState)

    ProvideWallpaperAppearance(appearance) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = NavBarMetrics.horizontalPadding(),
                vertical = NavBarMetrics.blockPadding()
            )
            .navigationBarsPadding()
            .wallpaperRegion(regionState),
        contentAlignment = Alignment.Center
    ) {
        when {
            useGlass -> {
                // 滚动最小化：整条玻璃横向收拢淡出，单胶囊液态弹出
                val minimizeFraction by animateFloatAsState(
                    targetValue = if (minimized) 1f else 0f,
                    animationSpec = MotionSpring.liquidSettle(),
                    label = "navMinimizeFraction"
                )
                if (minimizeFraction < 0.999f) {
                    Box(
                        modifier = Modifier.graphicsLayer {
                            val f = minimizeFraction
                            alpha = (1f - f * 1.6f).fastCoerceIn(0f, 1f)
                            scaleX = 1f - 0.82f * f
                            scaleY = 1f - 0.30f * f
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                    ) {
                        GlassNavigationBar(
                            items = items,
                            selectedTab = selectedTab,
                            onTabSelect = onTabSelect,
                            backdrop = requireNotNull(backdrop),
                            lensFreshness = lensFreshness
                        )
                    }
                }
                if (minimizeFraction > 0.001f) {
                    MinimizedNavCapsule(
                        item = items.getOrElse(selectedTab) { items.first() },
                        backdrop = requireNotNull(backdrop),
                        onClick = onExpandRequest,
                        modifier = Modifier.graphicsLayer {
                            val f = minimizeFraction
                            alpha = ((f - 0.35f) / 0.65f).fastCoerceIn(0f, 1f)
                            val scale = 0.55f + 0.45f * f
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                    )
                }
            }
            else -> FallbackNavigationBar(
                items = items,
                selectedTab = selectedTab,
                onTabSelect = onTabSelect
            )
        }
    }
    }
}

/** 最小化形态：仅显示当前 tab 的小玻璃胶囊，点按展开。 */
@Composable
private fun MinimizedNavCapsule(
    item: BottomNavItem,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLightTheme = LocalWallpaperAppearanceColors.current.usesDarkForeground
    val accentColor = if (isLightTheme) NavSelectedAccentLight else NavSelectedAccentDark
    val containerColor = if (isLightTheme) {
        Color.White.copy(alpha = 0.28f)
    } else {
        Color.Black.copy(alpha = 0.26f)
    }
    Row(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = 0.14f) },
                shadow = { Shadow(alpha = 0.10f) },
                onDrawSurface = { drawRect(containerColor) }
            )
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .height(48.dp)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = accentColor,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = item.label,
            color = LocalWallpaperAppearanceColors.current.onSurface,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun GlassNavigationBar(
    items: List<BottomNavItem>,
    selectedTab: Int,
    onTabSelect: (Int) -> Unit,
    backdrop: Backdrop,
    lensFreshness: GlassLensFreshness? = null
) {
    val tabsCount = items.size
    // 隐藏 tint 内容层：供选中透镜 combined 采样，避免只看到空雾
    val tabsBackdrop = rememberLayerBackdrop()
    // 只含 tint 文字、不含模糊壁纸、**也不含胶囊裁边**的副本，供 API31/32 的
    // 离屏折射采样。它挂在 drawBackdrop 之后（见下面 :664），所以录到的只有
    // 下游的文字。底图的模糊层由锚点自己铺满重画，理由见 lensAnchor 处。
    val tabsTintBackdrop = rememberLayerBackdrop()
    val accessibility = rememberGlassAccessibilityMode()
    // 平台是否真出折射/色散（API 33+）
    val hasRealLens = isRuntimeShaderTrulySupported()
    // API31/32 是否走自家离屏 GL 折射。isGlassLensApplicable() 只在 31/32 为真，
    // 所以下面每一处 `useOffscreenLens` 分支都进不了 33+，也进不了 ≤30。
    val useOffscreenLens = isGlassLensApplicable()
    // **这台设备最终有没有折射外观** —— 平台 AGSL 或自家离屏 GL，二者其一。
    //
    // 这个判据和 `hasRealLens` 的区别很要紧：`hasRealLens` 回答的是"平台有没有
    // AGSL"，用它去 gate **视觉补偿**就错了。那些补偿（Black×0.1 的选中块、
    // 无描边的轨道、压低的容器色）当初存在的唯一理由是"这台机器没有折射，
    // 得用别的手段把选中态做出来"。现在 31/32 有折射了，补偿必须一起撤，
    // 否则就是折射叠在补偿上 —— 屏幕上是一块发灰的滑块配一条看不见边的轨道，
    // 正是用户指出的"底栏还是有区别"。
    //
    // 反过来，真正的**平台能力**判据仍然只能用 hasRealLens：
    // `lens()` / `resolvePhysicalLens` 在 <33 上是 no-op。
    val hasLensLook = hasRealLens || useOffscreenLens
    // API33+ 加大尺寸可溢出；API32 回归 cba2a09：64/56/4 + pressedScale 78/56
    // 高度统一从 NavBarMetrics 取——内容留白读的是同一个函数，两者不可能再脱钩。
    val trackHeight = NavBarMetrics.trackHeight()

    // 两态原本都是 56dp；短屏跟着轨道一起收，否则 58dp 的轨道装不下它。
    val indicatorHeight = rememberScreenMetrics().tall(56.dp, 48.dp)
    val barPadding = if (hasLensLook) 6.dp else 4.dp
    val isLightTheme = LocalWallpaperAppearanceColors.current.usesDarkForeground
    // 有折射外观：半透主题底；≤30 无折射：cba2a09 半透轨
    val containerColor = if (hasLensLook) {
        // 提浊：降低穿透内容对比度，深色文字经过栏后不再形成清晰污块
        if (isLightTheme) Color.White.copy(alpha = 0.28f)
        else Color.Black.copy(alpha = 0.26f)
    } else {
        if (isLightTheme) {
            Color(GlassRecipe.NavLegacyTrackSurfaceLight)
                .copy(alpha = GlassRecipe.NavLegacyTrackSurfaceAlpha)
        } else {
            Color.Black.copy(alpha = GlassRecipe.NavLegacyTrackSurfaceAlpha)
        }
    }
    val accentColor = if (isLightTheme) NavSelectedAccentLight else NavSelectedAccentDark
    val barMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Navigation,
        accessibility = accessibility,
        interactionProgress = 0f
    )
    val indicatorMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Interactive,
        accessibility = accessibility,
        interactionProgress = 0f
    )
    val pressedScale = if (hasRealLens) {
        GlassRecipe.NavPressedScale
    } else {
        GlassRecipe.NavLegacyPressedScale
    }

    // 指示器采样的背景：壁纸/页面 + 隐藏 tint 文字层。提到这里是为了让
    // 折射锚点能引用同一个 backdrop。
    val indicatorBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)

    // API 31/32：平台无 AGSL，用离屏 OpenGL ES 2.0 做真折射（见 GlassLens.kt）。
    // 锚点挂在下面这个不随指示器移动的 BoxWithConstraints 上：底图上传一次，
    // 指示器滑动时只改采样窗口，避免每帧约 3ms 的重新快照。
    //
    // 底图必须复现指示器**实际压着的**东西，顺序与屏幕一致：
    //   模糊的壁纸+页面（blur 8dp）→ 轨道容器色 → 锐利 tint 文字
    //
    // ## 为什么模糊要在这里重做一遍，而不是 replay 轨道层
    //
    // 因为轨道层**有形状**。replay `tabsBackdrop` 会把它的胶囊裁边一起带进底图，
    // 而那条边正好压在指示器轮廓上，被斜坡放大成一圈灰罩。见下面 drawBlurred。
    //
    // 模糊本身是必须的：锐利源下页面文字会清晰透过指示器（课程卡片的"可选"
    // 三个字叠在图标上，1x 下依然可读），这正是最初被指出的"文字错乱"。
    // 曾经担心过"模糊把壁纸的微纹理也抹平，折射就看不见了"，于是在着色器里加了
    // 一层伪造微纹理 —— 那是误判：真凶是位移被 clamp 成库的 1/3。库自己的
    // LiquidBottomTabs 采的也是 blur(8dp) 过的 backdrop，没有任何纹理补偿。
    // 显式命名：DrawScope 里的 `density` 是 Float 成员，会遮蔽外层的 Density
    val outerDensity = LocalDensity.current
    // 轨道的模糊半径。**底图与轨道必须读同一个值**：底图是在重建"指示器压着的
    // 东西"，差一档模糊，折射里的内容就和屏幕上的对不上。
    //
    // 有折射外观时取配方的 10dp（33+ 一直是这个值），≤30 保留 cba2a09 的 8dp。
    val trackBlurDp = if (hasLensLook) barMaterial.blurDp else GlassRecipe.NavLegacyTrackBlurDp
    val trackBlurPx = with(outerDensity) { trackBlurDp.dp.toPx() }
    val lensAnchor = rememberGlassLensAnchor(tag = "navbar") { coords ->
        // 1) 模糊的壁纸/页面，**铺满整个锚点**，无形状。
        //
        // 这里不能 replay `tabsBackdrop`：那一层是 `shape = { Capsule() }`，
        // 裁边正好落在指示器边缘上（指示器与轨道内侧只差几 dp，第一个标签处
        // 左端完全重合）。底图里于是有一条沿指示器轮廓的半透明边界，斜坡再把它
        // 放大 —— 那圈"灰罩"就是它，跟折射本身无关。已量过：罩在**底图里**就有。
        drawBlurred(trackBlurPx) {
            with(backdrop) { drawBackdrop(outerDensity, coords, null) }
        }
        // 2) 轨道的容器色，同样铺满：指示器一定在轨道内，铺满与按形状画等价。
        drawRect(containerColor)
        // 3) 锐利的 tint 文字，压在最上面 —— 顺序与屏幕一致（库的隐藏层也是
        // 先 drawBackdrop 再画文字，文字不吃那层模糊）。
        with(tabsTintBackdrop) { drawBackdrop(outerDensity, coords, null) }
    }
    // 选中项或主题变化会改变隐藏 tint 层的内容，底图需要重拍。
    //
    // ## 为什么不能只拍一次
    //
    // 换页时**页面内容**也变了，而新页是带入场动画进来的（translationX + scale +
    // alpha，见 MainActivity 的 tabEnterProgress）。立刻拍一张，拍到的是动画中途
    // 那一帧 —— 内容整体偏移、半透明。之后没有任何东西再触发重拍，这张错的底图
    // 就一直留着，指示器的斜坡还会把它边缘那点内容放大。
    //
    // 屏幕上的样子（API32 实拍，设置页）：指示器左下角有一团**绿色**，而紧贴它
    // 的轨道里一点绿都没有 —— 那团绿是「检查更新」的绿图标在**动画中途**的位置，
    // 早已不在那儿了。只有课表/成绩/设置三页看得见，因为只有它们底部有彩色内容；
    // 也只有"没滑动过"时看得见，因为随便滑一下就会走下面那条滚动重拍的路，
    // 底图就修正了。API33+ 没有这个问题：那边 lens 采的是实时 backdrop，没有快照。
    //
    // 所以照抄滚动那条的做法：立刻拍一张（换页瞬间总比留着上一页的好），
    // 入场动画停了再补一张。collectLatest 让连续换页只有最后一次走到底。
    // `selectedTab` 必须当 **key**，不能放进 snapshotFlow：它是个普通 Int 形参，
    // 不是 snapshot state。`snapshotFlow { selectedTab }` 只会发一次 —— 发的是
    // 启动这个 effect 那次组合里捕获的值 —— 之后永远不再发。而 key 里又没有它，
    // effect 也不会重启。于是这一整段是**死代码**，换页从来没有重拍过底图。
    // 实测确认：点标签之后 logcat 里一条 `recapture` 都没有。
    // 用 key 重启还顺带拿到了 collectLatest 的语义：连续换页时前一次的 delay
    // 会被取消，只有最后一次走到底。
    LaunchedEffect(lensAnchor, isLightTheme, accentColor, selectedTab) {
        if (lensAnchor == null) return@LaunchedEffect
        lensAnchor.invalidate()
        // 入场动画是 tween(160ms)（MainActivity 的 tabEnterProgress），之后还要
        // 等它把那层全屏 RenderNode 撤掉、内容重排完。240 给足余量。
        delay(240)
        lensAnchor.invalidate()
    }
    // 滚动改变的是页面内容那一层。限频版本号推进 + 停下后补一次：
    // 版本号一变立刻重拍，然后 delay 之后如果没有新的滚动就再拍一次
    // （静止态才是用户真正盯着看的）。
    //
    // 版本号在 snapshotFlow **里**读，不在组合期读。
    // 曾经写成 `val v = lensFreshness?.version` 再拿去当 LaunchedEffect 的 key：
    // 那是个 mutableIntStateOf，组合期读它等于让整个导航栏订阅滚动，滑动时
    // 每 100ms 重组一次 —— 而且 33+ 也要白付，那边根本没有底图要重拍。
    //
    // collectLatest 正好是想要的语义：新的滚动到来时取消上一次的 delay，
    // 只有真正停下来的那次才会走到底。
    LaunchedEffect(lensAnchor, lensFreshness) {
        if (lensAnchor == null || lensFreshness == null) return@LaunchedEffect
        snapshotFlow { lensFreshness.version }.collectLatest {
            lensAnchor.invalidate()
            delay(140)
            lensAnchor.invalidate()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { clip = false }
            .glassLensAnchor(lensAnchor),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val barPaddingPx = with(density) { barPadding.toPx() }
        val tabWidth = (constraints.maxWidth.toFloat() - barPaddingPx * 2f) / tabsCount

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth)
                    .fastCoerceIn(-1f, 1f)
                with(density) {
                    4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val animationScope = rememberCoroutineScope()
        var currentIndex by remember { mutableIntStateOf(selectedTab) }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTab.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = pressedScale,
                directManipulationSpec = MotionSpring.liquidFollow(),
                settleAnimationSpec = MotionSpring.navSettle(),
                releaseScaleAnimationSpec = MotionSpring.navRelease(),
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue
                        .fastRoundToInt()
                        .fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    onTabSelect(targetIndex)
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, MotionSpring.liquidJellyRebound())
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }

        LaunchedEffect(selectedTab) {
            if (currentIndex != selectedTab) {
                currentIndex = selectedTab
                dampedDragAnimation.animateToValue(selectedTab.toFloat())
            }
        }

        // 1) 外层磨砂轨道：API32 固定 blur 8（cba2a09）；API33+ 轻 blur + 弱 lens
        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationX = panelOffset
                    clip = false
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        if (hasRealLens) {
                            val params = resolvePhysicalLens(
                                scope = this,
                                material = barMaterial,
                                minCornerRadiusPx = size.minDimension / 2f,
                                minDimensionPx = size.minDimension,
                                interactionProgress = 0f,
                                enableBlur = true,
                                allowChromaticAberration = false,
                                pressScalesRefraction = false
                            )
                            if (params.blurPx > 0f) blur(params.blurPx)
                            else blur(8.dp.toPx())
                            if (params.useLens) {
                                lens(
                                    refractionHeight = params.refractionHeightPx,
                                    refractionAmount = params.refractionAmountPx,
                                    chromaticAberration = false
                                )
                            }
                        } else {
                            // 无 AGSL：只有模糊。半径与底图读同一个 trackBlurDp。
                            blur(trackBlurPx)
                        }
                    },
                    highlight = {
                        // 描边与投影不需要 AGSL（Highlight/Shadow 都是 Compose 侧绘制），
                        // 之前按 hasRealLens 关掉是把它们当成了"33+ 才合理的增强"。
                        // 结果 32 上的轨道没有轮廓，直接融进壁纸 —— 见用户截图。
                        if (hasLensLook) Highlight.Default.copy(alpha = 0.16f) else null
                    },
                    shadow = {
                        if (hasLensLook) {
                            Shadow(alpha = 0.08f)
                        } else {
                            // cba2a09：轨道无投影
                            null
                        }
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        // 轨道随按压 + 指示器 scale 呼吸；滑块果冻回弹时底栏同步放大回落
                        val maxGain = 16.dp.toPx()
                        val pressScale = lerp(1f, 1f + maxGain / size.width, progress)
                        val indicatorBoost = (
                            (dampedDragAnimation.scaleX + dampedDragAnimation.scaleY) / 2f - 1f
                            ).fastCoerceIn(0f, 0.4f)
                        val scale = pressScale * (1f + indicatorBoost * 0.18f)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = {
                        drawRect(containerColor)
                    }
                )
                .height(trackHeight)
                .fillMaxWidth()
                .padding(barPadding)
        ) {
            // 可见图标文字
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(tabsCount, tabWidth) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startValue = dampedDragAnimation.value
                            val startX = down.position.x
                            val touchSlop = viewConfiguration.touchSlop
                            var totalDragX = 0f
                            var dragging = false
                            var pointerId = down.id

                            dampedDragAnimation.press()

                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                        ?: break
                                    if (change.changedToUpIgnoreConsumed()) {
                                        if (dragging) change.consume()
                                        break
                                    }

                                    val dragAmount = change.positionChange()
                                    if (dragAmount != Offset.Zero) {
                                        totalDragX += dragAmount.x
                                        if (!dragging && abs(totalDragX) > touchSlop) {
                                            dragging = true
                                        }
                                        if (dragging) {
                                            change.consume()
                                            dampedDragAnimation.updateValue(
                                                (startValue + totalDragX / tabWidth)
                                                    .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                                            )
                                            animationScope.launch {
                                                offsetAnimation.snapTo(totalDragX)
                                            }
                                        }
                                    }
                                    pointerId = change.id
                                }
                            } finally {
                                val targetIndex = if (dragging) {
                                    dampedDragAnimation.targetValue
                                        .fastRoundToInt()
                                        .fastCoerceIn(0, tabsCount - 1)
                                } else {
                                    (startX / tabWidth)
                                        .toInt()
                                        .fastCoerceIn(0, tabsCount - 1)
                                }
                                if (!dragging && targetIndex == currentIndex) {
                                    // 重复点击仍走原来的按压/松开视觉反馈，但不重复启动
                                    // 位移、速度和页面状态更新动画。
                                    dampedDragAnimation.release()
                                } else {
                                    currentIndex = targetIndex
                                    onTabSelect(targetIndex)
                                    dampedDragAnimation.animateToValue(targetIndex.toFloat())
                                }
                                if (offsetAnimation.value != 0f) {
                                    animationScope.launch {
                                        offsetAnimation.animateTo(
                                            0f,
                                            MotionSpring.liquidJellyRebound()
                                        )
                                    }
                                }
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val selectionWeight = (1f - abs(dampedDragAnimation.value - index))
                        .fastCoerceIn(0f, 1f)
                    NavTab(
                        item = item,
                        selected = selectionWeight > 0.55f,
                        accent = accentColor,
                        selectionWeight = selectionWeight,
                        onClick = null
                    )
                }
            }
        }

        // 2) 隐藏 tint 内容层：被选中透镜 refraction / combined 采样
        Row(
            modifier = Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        vibrancy()
                        if (hasRealLens) {
                            val params = resolvePhysicalLens(
                                scope = this,
                                material = barMaterial,
                                minCornerRadiusPx = size.minDimension / 2f,
                                minDimensionPx = size.minDimension,
                                interactionProgress = progress,
                                enableBlur = true,
                                allowChromaticAberration = false,
                                pressScalesRefraction = false
                            )
                            if (params.blurPx > 0f) blur(params.blurPx)
                            else blur(8.dp.toPx())
                            if (params.useLens) {
                                lens(
                                    refractionHeight = params.refractionHeightPx,
                                    refractionAmount = params.refractionAmountPx,
                                    chromaticAberration = false
                                )
                            }
                        } else {
                            // 无 AGSL：与 Layer1 / 底图同一个半径
                            blur(trackBlurPx)
                        }
                    },
                    highlight = {
                        // 该层被选中透镜采样，白环压低避免折射进胶囊形成白圈
                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress * 0.35f)
                    },
                    onDrawSurface = {
                        drawRect(containerColor)
                    }
                )
                // 放在 drawBackdrop **之后**：只录到下游的 tint 文字，不含上面那层
                // 模糊壁纸。recordLayer 只是把同一段绘制再录一遍到离屏层，
                // drawContent() 已经照常上屏，插在这里不改变任何可见结果。
                //
                // 只在离屏折射路径上挂：录层是**每帧**的离屏绘制开销，而 33+
                // 没有任何东西消费 tabsTintBackdrop，挂着就是白烧一层。
                .then(
                    if (useOffscreenLens) Modifier.layerBackdrop(tabsTintBackdrop) else Modifier
                )
                .height(indicatorHeight)
                .fillMaxWidth()
                .padding(horizontal = barPadding)
                .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                NavTab(
                    item = item,
                    selected = true,
                    accent = accentColor,
                    selectionWeight = 1f,
                    onClick = null,
                    forceAccent = true
                )
            }
        }

        // 3) 选中透镜：缩放只在 layerBlock；API32 走离屏 GL 折射
        Box(
            modifier = Modifier
                .padding(horizontal = barPadding)
                .graphicsLayer {
                    translationX = dampedDragAnimation.value * tabWidth + panelOffset
                    clip = false
                }
                // API31/32 真折射：画在 drawBackdrop 之前，所以它提供背景，
                // 库那层的 surface / highlight / shadow 仍叠在上面。
                .glassLens(
                    anchor = lensAnchor,
                    // 光学参数与下面 33+ 的 resolvePhysicalLens 读**同一份**配方、
                    // 同一个 floor（NavIndicatorRefractionFloor）、同一条曲线。
                    //
                    // 两处曾经各写一个字面量（这里 0，下面 0.42）。那不是配置，是漂移：
                    // 静止态一边折射一边不折射，描边落在软边 vs 死平边上，实测同一处
                    // 描边 33+ 高出基线 9.9%、32 高出 21.2% —— 两倍。所以必须共用。
                    // 共用之后值本身取 0（库的行为），见 NavIndicatorRefractionFloor。
                    //
                    // lambda 而非现成值：`pressProgress` / `velocity` 是 snapshot
                    // state，在组合期读会让整个导航栏在按压期间每帧重组，而且
                    // API33+ 也要白付这份代价。见 GlassLensOpticsProvider。
                    // w/h 是**实测**尺寸，由 glassLens 在 draw 里给。别再用
                    // indicatorHeight.toPx() 那个标称值：它和实测差过 6.9%。
                    optics = { w, h ->
                        val minDim = minOf(w, h)
                        glassLensOpticsFrom(
                            material = indicatorMaterial,
                            density = density,
                            cornerRadiusPx = minDim / 2f,
                            minDimensionPx = minDim,
                            interactionProgress = dampedDragAnimation.pressProgress,
                            motionIntensity = motionIntensityFromVelocity(
                                velocityX = dampedDragAnimation.velocity * tabWidth,
                                fullEffectVelocity =
                                    indicatorMaterial.optics.velocityForFullEffect
                            ),
                            pressScalesRefraction = true,
                            refractionFloor = NavIndicatorRefractionFloor,
                            chromaticAberrationAtRest = false
                        )
                    },
                    // 与下面 drawBackdrop 的 layerBlock **同一份**形变。
                    // 不传的话按下时库的 highlight 环按放大后的轮廓画，而折射还是
                    // 原尺寸，屏幕上是一圈白环浮在玻璃外面（已截图确认）。
                    scale = { _, _ -> indicatorScale(dampedDragAnimation) }
                )
                .drawBackdrop(
                    backdrop = indicatorBackdrop,
                    shape = { Capsule() },
                    effects = {
                        val press = dampedDragAnimation.pressProgress
                        vibrancy()
                        if (hasRealLens) {
                            // 横向速度 → 运动强度，驱动色散随滑动增强
                            val motion = motionIntensityFromVelocity(
                                velocityX = dampedDragAnimation.velocity * tabWidth,
                                fullEffectVelocity = indicatorMaterial.optics.velocityForFullEffect
                            )
                            val params = resolvePhysicalLens(
                                scope = this,
                                material = indicatorMaterial,
                                minCornerRadiusPx = size.minDimension / 2f,
                                minDimensionPx = size.minDimension,
                                interactionProgress = press,
                                motionIntensity = motion,
                                enableBlur = false,
                                allowChromaticAberration = true,
                                // 折射跟着 pressProgress 从 0 长到满值，与库一致
                                pressScalesRefraction = true,
                                refractionFloor = NavIndicatorRefractionFloor
                            )
                            if (params.useLens) {
                                lens(
                                    refractionHeight = params.refractionHeightPx,
                                    refractionAmount = params.refractionAmountPx,
                                    chromaticAberration = params.chromaticAberration
                                )
                            }
                        } else if (lensAnchor == null) {
                            // 既无 AGSL 也无离屏折射（API ≤ 30）：只剩 RGB 分离近似。
                            // lens() 在这些平台上是 no-op，留着只是为了形状校验路径一致。
                            lens(
                                refractionHeight = 10.dp.toPx() * press,
                                refractionAmount = 14.dp.toPx() * press,
                                chromaticAberration = true
                            )
                            val legacyMotion = motionIntensityFromVelocity(
                                velocityX = dampedDragAnimation.velocity * tabWidth,
                                fullEffectVelocity = indicatorMaterial.optics.velocityForFullEffect
                            )
                            chromaticFringe(
                                (press * 1.8f + legacyMotion * 1.2f)
                                    .coerceIn(0f, 2.2f).dp.toPx()
                            )
                        }
                        // lensAnchor != null（API31/32）：折射与七波长色散都已由
                        // glassLens 在这之前画完，这里不能再叠假色散——那是两套
                        // 不同的边缘着色叠在一起，只会互相污染。
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        // 整层被 graphicsLayer 等比放大，描边宽度反向补偿保持视觉细度恒定
                        val scaleComp = (
                            (dampedDragAnimation.scaleX + dampedDragAnimation.scaleY) / 2f
                            ).coerceAtLeast(1f)
                        if (hasLensLook) {
                            Highlight.Default.copy(
                                width = Highlight.Default.width / scaleComp,
                                blurRadius = Highlight.Default.blurRadius / scaleComp,
                                alpha = 0.12f + progress * 0.35f
                            )
                        } else {
                            // 按压渐显边缘高光（压低亮度，避免白圈）
                            Highlight.Default.copy(
                                width = Highlight.Default.width / scaleComp,
                                alpha = progress * 0.35f
                            )
                        }
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        if (hasLensLook) {
                            Shadow(alpha = 0.10f + progress * 0.15f)
                        } else {
                            // 按压渐显投影（减半，滑动残影更轻）
                            Shadow(alpha = progress * 0.5f)
                        }
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4.dp * progress,
                            alpha = progress * 0.5f
                        )
                    },
                    layerBlock = {
                        // 与上面 glassLens 的 scale 读同一个函数，两者不可能再脱钩
                        val t = indicatorScale(dampedDragAnimation)
                        scaleX = t.scaleX
                        scaleY = t.scaleY
                    },
                    onDrawBackdrop = { drawBackdrop ->
                        // API31/32 上背景已由 glassLens 以折射方式画过，不能再画一遍
                        if (lensAnchor == null) {
                            // 无环 indicatorBackdrop（壁纸+页面），两平台都采样
                            drawBackdrop()
                        }
                    },
                    onDrawSurface = {
                        val press = dampedDragAnimation.pressProgress
                        if (hasLensLook) {
                            // 有折射：低透明中性 tint（白），静止即玻璃；按下更透，
                            // 露出折射/色散。
                            //
                            // 31/32 曾经走下面那条 Black×0.1 —— 那是"没有折射时怎么
                            // 把选中态做出来"的答案，叠黑把整块透镜压成灰片。同一个
                            // 错误在 LiquidSegmentedControl 上已经犯过一次。
                            val solidColor = if (isLightTheme) {
                                Color(GlassRecipe.NavSelectedSolidColorLight)
                            } else {
                                Color(GlassRecipe.NavSelectedSolidColorDark)
                            }
                            val restAlpha = if (isLightTheme) {
                                GlassRecipe.NavSelectedSolidAlpha
                            } else {
                                GlassRecipe.NavSelectedSolidAlphaDark
                            }
                            val fillAlpha = lerp(
                                restAlpha,
                                GlassRecipe.NavSelectedGlassAlpha,
                                press
                            )
                            if (fillAlpha > 0f) {
                                drawRect(solidColor.copy(alpha = fillAlpha))
                            }
                        } else {
                            // ≤30 无折射（cba2a09）：Black×0.1，按下淡出
                            drawRect(Color.Black.copy(0.1f), alpha = 1f - press)
                            drawRect(Color.Black.copy(alpha = 0.03f * press))
                        }
                    }
                )
                .height(indicatorHeight)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

/**
 * 指示器的按压 + 速度形变。
 *
 * 单独抽出来是因为它有**两个**消费者：库那层 `drawBackdrop(layerBlock)`，
 * 以及 API31/32 的 `glassLens(scale)`。两边必须逐帧一致 —— 不一致时按下会看到
 * 一圈白环（库画的 highlight）浮在玻璃（自家折射）外面。
 */
private fun indicatorScale(anim: DampedDragAnimation): GlassLensTransform {
    var sx = anim.scaleX
    var sy = anim.scaleY
    // 速度形变系数加大（/6 相对原 /10）
    val velocity = anim.velocity / 6f
    sx /= 1f - (velocity * 0.75f).fastCoerceIn(-0.22f, 0.22f)
    sy *= 1f - (velocity * 0.25f).fastCoerceIn(-0.22f, 0.22f)
    // 平移为 0：指示器的 translationX 挂在**外层** graphicsLayer 上，
    // glassLens 在它内部，已经跟着一起走了。芯片不同，见 GlassLensTransform。
    return GlassLensTransform(scaleX = sx, scaleY = sy)
}

@Composable
private fun FallbackNavigationBar(
    items: List<BottomNavItem>,
    selectedTab: Int,
    onTabSelect: (Int) -> Unit
) {
    val capsuleShape = RoundedCornerShape(28.dp)
    val appearance = LocalWallpaperAppearanceColors.current
    val accentColor = if (appearance.usesDarkForeground) {
        NavSelectedAccentLight
    } else {
        NavSelectedAccentDark
    }
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        shape = capsuleShape,
        color = appearance.solidSurface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                NavTab(
                    item = item,
                    selected = selectedTab == index,
                    accent = accentColor,
                    onClick = { onTabSelect(index) }
                )
            }
        }
    }
}

@Composable
private fun RowScope.NavTab(
    item: BottomNavItem,
    selected: Boolean,
    accent: Color,
    selectionWeight: Float = if (selected) 1f else 0f,
    onClick: (() -> Unit)?,
    forceAccent: Boolean = false
) {
    val weight = selectionWeight.fastCoerceIn(0f, 1f)
    val iconTint by animateColorAsState(
        targetValue = if (forceAccent) {
            accent
        } else {
            androidx.compose.ui.graphics.lerp(
                LocalWallpaperAppearanceColors.current.onSurfaceVariant.copy(alpha = 0.5f),
                accent,
                weight
            )
        },
        label = "navIconTint"
    )
    val labelColor by animateColorAsState(
        targetValue = if (forceAccent) {
            accent
        } else {
            androidx.compose.ui.graphics.lerp(
                LocalWallpaperAppearanceColors.current.onSurfaceVariant.copy(alpha = 0.5f),
                LocalWallpaperAppearanceColors.current.onSurface,
                weight
            )
        },
        label = "navLabelColor"
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .then(clickModifier)
            .fillMaxHeight()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = iconTint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    // 选中：上移 + 放大 1.15x（跟随 selectionWeight，回弹来自底层气泡运动）
                    val w = if (forceAccent) 1f else weight
                    val scale = 1f + 0.15f * w
                    scaleX = scale
                    scaleY = scale
                    translationY = -3.dp.toPx() * w
                }
        )
        Text(
            text = item.label,
            color = labelColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (weight > 0.55f || forceAccent) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
