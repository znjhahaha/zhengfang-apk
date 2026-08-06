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
        // 轨道：轻磨砂，不抢选中透镜
        blurDp = 6f,
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
        blurDp = 12f,
        refractionHeightDp = 6f,
        refractionAmountDp = 8f,
        surfaceAlpha = 0.78f,
        borderAlpha = 0.22f,
        shadowAlpha = 0.18f,
        optics = GlassOpticsSpec(
            specularAlpha = 0.10f,
            innerGlowAlpha = 0.06f
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
    val SegIndicatorBlurDp = control.blurDp
    val SegIndicatorRefractionHeightDp = control.refractionHeightDp
    val SegIndicatorRefractionAmountDp = control.refractionAmountDp
    val SegIndicatorSurfaceAlpha = 0.24f
    val SegIndicatorBorderAlpha = 0.24f
    val SegIndicatorShadowAlpha = control.shadowAlpha
    val SegIndicatorPressedShadowAlpha = interactive.shadowAlpha
    // 导航与 segmented 共用克制的选中胶囊按压尺度。
    val SelectionIndicatorPressedScale = 1.08f
    val SegIndicatorPressedScale = SelectionIndicatorPressedScale
    val SegIndicatorMaxVelocityStretch = 0.12f

    // Apple 风清晰实心选中胶囊：不依赖折射，底栏与 segmented 共用。
    // 无真 lens（API 32）时用较高不透明度形成清晰白/浅胶囊；真 lens 时调用侧降低以露出折射。
    val SelectedCapsuleSolidAlphaLight = 0.94f
    val SelectedCapsuleSolidAlphaDark = 0.20f
    val SelectedCapsuleBorderAlpha = 0.35f
    val SelectedCapsuleShadowAlpha = 0.14f
    // 真 lens 平台上，实心底降到较低，避免盖住边缘折射/色散
    val SelectedCapsuleLensSurfaceAlphaLight = 0.16f
    val SelectedCapsuleLensSurfaceAlphaDark = 0.12f

    // 底栏 API32（cba2a09）：轨道固定 blur 8dp + 半透 surface，选中 Black×0.1
    val NavLegacyTrackBlurDp = 8f
    val NavLegacyIndicatorBlurDp = 6f
    val NavLegacyTrackSurfaceLight = 0xFFFAFAFA
    val NavLegacyTrackSurfaceAlpha = 0.15f
    val NavLegacyGlassSurfaceAlphaLight = 0.12f
    val NavLegacyGlassSurfaceAlphaDark = 0.18f
    val NavLegacyShadowBaseAlpha = 0.15f
    val NavLegacyShadowPressGain = 0.35f
    // 无 RuntimeShader 时保持同样的克制按压尺度。
    val NavLegacyPressedScale = SelectionIndicatorPressedScale

    // API33+ 静态选中态以清晰实心胶囊为主；按压时降低遮罩露出折射。
    val NavSelectedSolidColorLight = 0xFFF5F5F7
    val NavSelectedSolidColorDark = 0xFF3A3A3C
    val NavSelectedSolidAlpha = 0.88f
    val NavSelectedGlassAlpha = 0.08f
    val NavPressedScale = SelectionIndicatorPressedScale

    val ActionBlurDp = control.blurDp
    val ActionRefractionHeightDp = control.refractionHeightDp
    val ActionRefractionAmountDp = control.refractionAmountDp
    val ActionSurfaceAlpha = control.surfaceAlpha
    val ActionBorderAlpha = control.borderAlpha
    val ActionShadowAlpha = control.shadowAlpha
    val ActionPressedScale = 0.98f
    val ActionTintAlpha = 0.72f
    val ActionDisabledSurfaceAlpha = 0.28f

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

    val LuminanceSampleSize = 5
    val LuminanceSampleHz = 3
}