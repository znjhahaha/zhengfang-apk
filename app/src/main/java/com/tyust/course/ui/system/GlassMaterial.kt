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
data class GlassMaterialSpec(
    val blurDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val surfaceAlpha: Float,
    val borderAlpha: Float,
    val shadowAlpha: Float,
    val depthEffect: Boolean = false,
    val chromaticAberration: Boolean = false
)

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
        blurDp = 8f,
        refractionHeightDp = 5f,
        refractionAmountDp = 7f,
        surfaceAlpha = 0.42f,
        borderAlpha = 0.24f,
        shadowAlpha = 0.14f
    )

    private val control = GlassMaterialSpec(
        blurDp = 5f,
        refractionHeightDp = 4f,
        refractionAmountDp = 5f,
        surfaceAlpha = 0.20f,
        borderAlpha = 0.22f,
        shadowAlpha = 0.10f
    )

    private val modal = GlassMaterialSpec(
        blurDp = 12f,
        refractionHeightDp = 6f,
        refractionAmountDp = 8f,
        surfaceAlpha = 0.82f,
        borderAlpha = 0.28f,
        shadowAlpha = 0.20f
    )

    private val interactive = GlassMaterialSpec(
        blurDp = 2.5f,
        refractionHeightDp = 5f,
        refractionAmountDp = 8f,
        surfaceAlpha = 0.18f,
        borderAlpha = 0.30f,
        shadowAlpha = 0.14f,
        chromaticAberration = true
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
                (base.surfaceAlpha + 0.24f).coerceAtMost(0.94f)
            } else {
                base.surfaceAlpha
            },
            borderAlpha = if (accessibility.highContrast) {
                (base.borderAlpha + 0.22f).coerceAtMost(0.72f)
            } else {
                base.borderAlpha
            },
            chromaticAberration = base.chromaticAberration &&
                !accessibility.reduceMotion &&
                progress > 0.08f
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
    val SegIndicatorPressedScale = 1.035f
    val SegIndicatorMaxVelocityStretch = 0.08f

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