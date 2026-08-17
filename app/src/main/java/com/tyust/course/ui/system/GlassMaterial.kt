package com.tyust.course.ui.system

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 玻璃只用于悬浮的导航、控制和模态层；内容卡片不属于玻璃材质系统。
 */
enum class GlassMaterialRole {
    Navigation,
    Control,
    Modal,
    Interactive
}

@Immutable
data class GlassOpticsSpec(
    val chromaticAberration: Boolean = false,
    val spectralRimAlpha: Float = 0f,
    val specularAlpha: Float = 0f,
    val innerGlowAlpha: Float = 0f,
    val velocityForFullEffect: Float = 2200f
)

@Immutable
data class GlassMaterialSpec(
    val blurDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val surfaceAlpha: Float,
    val borderAlpha: Float,
    val shadowAlpha: Float,
    val depthEffect: Boolean = false,
    val optics: GlassOpticsSpec = GlassOpticsSpec()
) {
    val chromaticAberration: Boolean
        get() = optics.chromaticAberration
}

@Immutable
data class GlassAccessibilityMode(
    val reduceMotion: Boolean,
    val highContrast: Boolean
)

@Composable
fun rememberGlassAccessibilityMode(): GlassAccessibilityMode {
    val context = LocalContext.current
    return remember(context) {
        val resolver = context.contentResolver
        val animatorScale = runCatching {
            Settings.Global.getFloat(
                resolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
        val highContrast = runCatching {
            Settings.Secure.getInt(
                resolver,
                "high_text_contrast_enabled",
                0
            ) == 1
        }.getOrDefault(false)

        GlassAccessibilityMode(
            reduceMotion = animatorScale <= 0f,
            highContrast = highContrast
        )
    }
}

object GlassMaterials {
    private val navigation = GlassMaterialSpec(
        // 轨道：磨砂加深，穿透内容雾化均匀，避免深色内容顶在栏边缘成污块
        blurDp = 10f,
        refractionHeightDp = 8f,
        refractionAmountDp = 12f,
        surfaceAlpha = 0.16f,
        borderAlpha = 0.14f,
        shadowAlpha = 0.12f,
        optics = GlassOpticsSpec(
            // 轨道默认不色散
            chromaticAberration = false,
            spectralRimAlpha = 0f,
            specularAlpha = 0.14f,
            innerGlowAlpha = 0.08f,
            velocityForFullEffect = 1400f
        )
    )

    private val control = GlassMaterialSpec(
        blurDp = 5f,
        refractionHeightDp = 6f,
        refractionAmountDp = 10f,
        surfaceAlpha = 0.10f,
        borderAlpha = 0.12f,
        shadowAlpha = 0.08f,
        optics = GlassOpticsSpec(
            chromaticAberration = true,
            spectralRimAlpha = 0f,
            specularAlpha = 0.16f,
            innerGlowAlpha = 0.08f,
            velocityForFullEffect = 1400f
        )
    )

    private val modal = GlassMaterialSpec(
        blurDp = 6f,
        refractionHeightDp = 10f,
        refractionAmountDp = 14f,
        surfaceAlpha = 0.62f,
        borderAlpha = 0.30f,
        shadowAlpha = 0.24f,
        optics = GlassOpticsSpec(
            specularAlpha = 0.18f,
            innerGlowAlpha = 0.10f
        )
    )

    private val interactive = GlassMaterialSpec(
        // API33+ 选中透镜默认无 blur，靠折射/色散；API31/32 调用侧单独补 blur。
        blurDp = 0f,
        refractionHeightDp = 12f,
        refractionAmountDp = 18f,
        surfaceAlpha = 0.08f,
        borderAlpha = 0.16f,
        shadowAlpha = 0.12f,
        optics = GlassOpticsSpec(
            chromaticAberration = true,
            // 彩色只允许来自 lens 背景采样，不再使用模板 rim
            spectralRimAlpha = 0f,
            specularAlpha = 0.22f,
            innerGlowAlpha = 0.12f,
            velocityForFullEffect = 900f
        )
    )

    fun resolve(
        role: GlassMaterialRole,
        accessibility: GlassAccessibilityMode = GlassAccessibilityMode(
            reduceMotion = false,
            highContrast = false
        ),
        interactionProgress: Float = 0f
    ): GlassMaterialSpec {
        val base = when (role) {
            GlassMaterialRole.Navigation -> navigation
            GlassMaterialRole.Control -> control
            GlassMaterialRole.Modal -> modal
            GlassMaterialRole.Interactive -> interactive
        }
        val progress = interactionProgress.coerceIn(0f, 1f)

        // 交互推进时略降表面遮罩，让折射更可见
        val surfaceScale = 1f - progress * 0.28f

        return base.copy(
            refractionHeightDp = if (accessibility.reduceMotion) {
                base.refractionHeightDp * 0.55f
            } else {
                base.refractionHeightDp
            },
            refractionAmountDp = if (accessibility.reduceMotion) {
                base.refractionAmountDp * 0.45f
            } else {
                base.refractionAmountDp
            },
            surfaceAlpha = if (accessibility.highContrast) {
                (base.surfaceAlpha + 0.20f).coerceAtMost(0.90f)
            } else {
                (base.surfaceAlpha * surfaceScale).coerceAtLeast(0.04f)
            },
            borderAlpha = if (accessibility.highContrast) {
                (base.borderAlpha + 0.18f).coerceAtMost(0.60f)
            } else {
                base.borderAlpha
            },
            optics = base.optics.copy(
                // 角色级能力开关；具体是否开启由 resolvePhysicalLens 按 press/motion 判定
                chromaticAberration = base.optics.chromaticAberration &&
                    !accessibility.reduceMotion,
                spectralRimAlpha = 0f,
                specularAlpha = if (accessibility.highContrast) {
                    (base.optics.specularAlpha * 0.72f).coerceAtMost(0.20f)
                } else {
                    base.optics.specularAlpha
                },
                innerGlowAlpha = if (accessibility.reduceMotion) {
                    base.optics.innerGlowAlpha * 0.45f
                } else {
                    base.optics.innerGlowAlpha
                }
            )
        )
    }
}

/**
 * 旧组件的兼容配方入口。新增组件应直接解析角色化材质。
 */
object GlassRecipe {
    private val navigation = GlassMaterials.resolve(GlassMaterialRole.Navigation)
    private val control = GlassMaterials.resolve(GlassMaterialRole.Control)
    private val modal = GlassMaterials.resolve(GlassMaterialRole.Modal)
    private val interactive = GlassMaterials.resolve(GlassMaterialRole.Interactive)

    val BlurDp = navigation.blurDp
    val RefractionHeightDp = navigation.refractionHeightDp
    val RefractionAmountDp = navigation.refractionAmountDp
    val SurfaceAlpha = navigation.surfaceAlpha
    val BarBorderAlpha = navigation.borderAlpha

    val CardBlurDp = control.blurDp
    val CardRefractionHeightDp = control.refractionHeightDp
    val CardRefractionAmountDp = control.refractionAmountDp
    val CardSurfaceAlpha = control.surfaceAlpha
    val CardBorderAlpha = control.borderAlpha

    val IndicatorRefractionHeightDp = control.refractionHeightDp
    val IndicatorRefractionAmountDp = control.refractionAmountDp
    val IndicatorSurfaceAlpha = control.surfaceAlpha
    val IndicatorBorderAlpha = control.borderAlpha

    val TopBarBlurDp = navigation.blurDp
    val TopBarSurfaceAlpha = navigation.surfaceAlpha
    val TopBarLensRefractionHeightDp = navigation.refractionHeightDp
    val TopBarLensRefractionAmountDp = navigation.refractionAmountDp

    val SheetBlurDp = modal.blurDp
    val SheetRefractionHeightDp = modal.refractionHeightDp
    val SheetRefractionAmountDp = modal.refractionAmountDp
    val SheetSurfaceAlpha = modal.surfaceAlpha

    val SegTrackBlurDp = control.blurDp
    val SegTrackSurfaceAlpha = 0.34f
    val SegTrackBorderAlpha = 0.16f
    // 分段栏与底栏的观感诉求不同：底栏整条浮在壁纸上，轨道白雾就是它的主体；
    // 分段栏嵌在页面内容里，轨道白雾越重整条越"灰蒙蒙"。
    // 滑块表面刻意做到几乎透明：它一旦用白色填充制造存在感，就必然把底下被折射的
    // 文字压成灰雾。存在感改由投影与边缘高光承担，颜色交给折射出来的主色文字。
    val SegTrackSurfaceAlphaLight = 0.10f
    val SegTrackSurfaceAlphaDark = 0.16f
    val SegSelectedSurfaceAlphaLight = 0.18f
    val SegSelectedSurfaceAlphaDark = 0.14f
    val SegSelectedShadowAlpha = 0.22f
    val SegSelectedRimAlpha = 0.36f
    val SelectionIndicatorPressedScale = 78f / 56f
    // 分段滑块只做轻微按压反馈，位移由接近临界阻尼的弹簧负责。
    val SegIndicatorPressedScale = 1.035f
    val SegIndicatorMaxVelocityStretch = 0.06f

    // Apple 风清晰实心选中胶囊：不依赖折射，底栏与 segmented 共用。
    // 无真 lens（API 32）时用较高不透明度形成清晰白/浅胶囊；真 lens 时调用侧降低以露出折射。
    val SelectedCapsuleSolidAlphaLight = 0.94f
    val SelectedCapsuleSolidAlphaDark = 0.20f
    val SelectedCapsuleBorderAlpha = 0.35f
    val SelectedCapsuleShadowAlpha = 0.14f
    // 真 lens 平台上，实心底降到较低，避免盖住边缘折射/色散
    val SelectedCapsuleLensSurfaceAlphaLight = 0.16f
    val SelectedCapsuleLensSurfaceAlphaDark = 0.12f

    // 底栏 API31/32（cba2a09）：轨道固定 blur 8dp + 半透 surface，选中 Black×0.1，
    // 滑块自身不加 blur，毛玻璃质感来自 combined backdrop 采样轨道模糊层。
    val NavLegacyTrackBlurDp = 8f
    val NavLegacyTrackSurfaceLight = 0xFFFAFAFA
    val NavLegacyTrackSurfaceAlpha = 0.15f
    val NavLegacyGlassSurfaceAlphaLight = 0.12f
    val NavLegacyGlassSurfaceAlphaDark = 0.18f
    // 无 RuntimeShader 时与真 lens 平台共用 78/56 按压尺度（cba2a09）。
    val NavLegacyPressedScale = SelectionIndicatorPressedScale

    // 选中胶囊靠"提亮"与轨道区分，而不是叠黑色 tint。浅色主题下叠黑会把
    // 整块透镜压成灰片，视觉上比轨道更脏；白色低透明既保留折射也不发灰。
    val NavSelectedSolidColorLight = 0xFFFFFFFF
    val NavSelectedSolidColorDark = 0xFFFFFFFF
    val NavSelectedSolidAlpha = 0.34f
    val NavSelectedSolidAlphaDark = 0.20f
    val NavSelectedGlassAlpha = 0.14f
    val NavPressedScale = SelectionIndicatorPressedScale

    val ActionBlurDp = control.blurDp
    val ActionRefractionHeightDp = control.refractionHeightDp
    val ActionRefractionAmountDp = control.refractionAmountDp
    val ActionSurfaceAlpha = control.surfaceAlpha
    val ActionBorderAlpha = control.borderAlpha
    val ActionShadowAlpha = control.shadowAlpha
    val ActionPressedScale = 0.98f
    // 官方 LiquidButton 模板的着色透明度：BlendMode.Hue 之后再叠一层 0.75 的 tint。
    val ActionTintAlpha = 0.75f
    // 0.28→0.88：禁用态接近实色浅灰，与可用态干净区分，避免"半透明糊灰"
    val ActionDisabledSurfaceAlpha = 0.88f

    val SwitchTrackInactiveAlpha = 0.32f
    val SwitchTrackActiveAlpha = 0.78f
    val SwitchThumbBlurDp = interactive.blurDp
    val SwitchThumbRefractionHeightDp = interactive.refractionHeightDp
    val SwitchThumbRefractionAmountDp = interactive.refractionAmountDp
    val SwitchThumbSurfaceAlpha = 0.42f
    val SwitchThumbBorderAlpha = interactive.borderAlpha
    val SwitchThumbShadowAlpha = interactive.shadowAlpha
    val SwitchPressedScaleX = 1.12f
    val SwitchPressedScaleY = 0.94f

    val PickerBlurDp = control.blurDp
    val PickerRefractionHeightDp = control.refractionHeightDp
    val PickerRefractionAmountDp = control.refractionAmountDp
    val PickerSurfaceAlpha = 0.24f
    val PickerExpandedSurfaceAlpha = 0.32f
    val PickerBorderAlpha = 0.20f
    val PickerExpandedBorderAlpha = 0.32f
    val PickerShadowAlpha = control.shadowAlpha
    val PickerExpandedShadowAlpha = 0.16f
    val PickerMenuBlurDp = modal.blurDp
    val PickerMenuRefractionHeightDp = modal.refractionHeightDp
    val PickerMenuRefractionAmountDp = modal.refractionAmountDp
    val PickerMenuSurfaceAlpha = modal.surfaceAlpha
    val PickerMenuBorderAlpha = modal.borderAlpha
    val PickerMenuShadowAlpha = modal.shadowAlpha
    val PickerSelectedSurfaceAlpha = 0.12f

    val Saturation = 1f
    val CardDepthEffect = false

    val DialogCornerDp = 32f
    val DialogBlurDp = modal.blurDp
    val DialogRefractionHeightDp = modal.refractionHeightDp
    val DialogRefractionAmountDp = modal.refractionAmountDp
    val DialogSurfaceAlpha = modal.surfaceAlpha
    val DialogBorderAlpha = modal.borderAlpha
    val DialogShadowAlpha = modal.shadowAlpha
    val DialogShadowElevationDp = 8f

    // ── 交互液体芯片（顶栏图标钮、圆钮）────────────────────────────────
    // 表面必须够淡：芯片尺寸只有 38dp，白雾一重折射就被盖死，成了白贴纸。
    // 存在感交给 Fresnel 边缘，颜色交给背景折射。
    val ChipSurfaceAlphaLight = 0.10f
    val ChipSurfaceAlphaDark = 0.06f
    /** 按压时进一步让位给折射：手指按下应该看到更多环境，而不是更多白色。 */
    val ChipPressedSurfaceScale = 0.55f
    val ChipRimAlphaLight = 0.30f
    val ChipRimAlphaDark = 0.34f
    val ChipContourAlphaLight = 0.20f
    val ChipContourAlphaDark = 0.18f
    /** 按压时边缘收紧变实，像玻璃被压出的应力边。 */
    val ChipPressedRimBoost = 0.45f
    /** 触点方向的高光偏置幅度：占半径的比例，过大就成了游走的塑料反光。 */
    val ChipHighlightTravel = 0.34f
    val ChipDisabledSurfaceScale = 0.55f
    val ChipDisabledRimScale = 0.5f
    /**
     * 无 backdrop 回退路径的按压挤压深度。
     *
     * liquidChip 的形变发生在 drawBackdrop 的 layerBlock 里，回退路径没有
     * drawBackdrop，挤压必须自己补一层 graphicsLayer，否则这条路径按下去
     * 只有边缘光变化、没有任何几何反馈。
     */
    val ChipFallbackPressDepth = 0.06f

    // ── 按压/拖拽形变预算 ────────────────────────────────────────────
    /**
     * 按压时的整体涨幅（dp）。原先 4dp 在 38dp 芯片上是 10% 的增长，
     * 读起来是"变大了"而不是"被按下去"，收到 2dp。
     */
    val ChipPressSwellDp = 2f
    /**
     * 跟手行程上限（dp）。原先拿 size.minDimension 当上限，于是玻璃层能整体
     * 滑出一整个直径——芯片跑出自己的插槽、压到邻居身上，而图标留在原地。
     * 有界之后手指仍然 1:1 跟手，只是到 2.5dp 就饱和。
     */
    val ChipDragTravelDp = 2.5f
    /**
     * 拖拽各向异性幅度。**等体积**：沿运动方向拉长多少，垂直方向就收窄多少。
     * 原先两个轴都只加不减，拖得越远芯片越大，最后成了一颗蛋。
     */
    val ChipDragStretch = 0.06f
    /** 图标随按压的压扁深度。比容器涨幅小，图标才像"陷进玻璃"而不是各自动。 */
    val ChipIconPressDepth = 0.05f
    /**
     * 内容层跟随玻璃各向异性的折扣。玻璃是液体、图标是固体：等幅跟着拉伸整个
     * 按钮会显得在橡皮化，完全不跟又会在横拖时明显脱节。
     */
    val ChipContentDeformDamping = 0.5f
    /** 拖拽速度驱动的等体积拉伸幅度：快拖沿运动方向拉长、垂直方向等量收窄。 */
    val ChipVelocityStretch = 0.12f
    /** 速度拉伸的满档拖速（px/ms，约等于快速甩动）。 */
    val ChipVelocityFullEffectMsPx = 4f
    /**
     * 组收拢/展开时芯片被布局推着滑行的果冻拉伸幅度：沿运动方向拉长、纵向
     * 等体积收窄。位移由父级重排驱动（自己身上没有手势），速度只能从
     * onPlaced 的根坐标差分里来——这是"位置在变"唯一可读的物理线索。
     */
    val ChipGlideStretch = 0.14f
    /** 滑行拉伸的满档速度（px/ms，约等于收拢弹簧的中段速度）。 */
    val ChipGlideFullVelocityPxMs = 0.5f

    // ── 按压邻近液滴融合 ─────────────────────────────────────────────
    /** 邻居间距超过芯片直径的这个倍数就不再连接，避免隔着一个按钮"远程拉丝"。 */
    val ActionGroupMaxBridgeRatio = 0.62f
    /**
     * 融合颈出生 k（×两圆边距）：多项式 smin 在 k = 2×边距 时中点场值恰好过零，
     * 取 2.02 让桥一出生就是一条细丝而不是半宽矩形（实测出生腰宽 7%~20% 直径，
     * 随间距增大而变粗——间距上限被 MaxBridgeRatio 拦住，不会失控）。
     */
    val ActionGroupBridgeKBirthGapRatio = 2.02f
    /** 压满 k 的边距分量。 */
    val ActionGroupBridgeKFullGapRatio = 2.0f
    /** 压满 k 的半径分量：与边距分量相加后腰宽约为直径的 3/4（全间距段稳定）。 */
    val ActionGroupBridgeKFullRadiusRatio = 0.24f
    /** 融合起效的按压阈值：轻触不该触发，避免误连。 */
    val ActionGroupMergeThreshold = 0.18f
}
