package com.tyust.course.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

// 克莱因蓝（International Klein Blue）作为全局主色
// iOS systemBlue：明亮饱和，在多彩壁纸与玻璃表面上保持鲜明（原克莱因蓝 0xFF002FA7 在灰玻璃上发闷）
val BrandPrimary = Color(0xFF0A84FF)
val BrandPrimaryStrong = Color(0xFF0069D9)
val BrandPrimaryContainer = Color(0xFFD6E9FF)
val BrandSecondary = Color(0xFF5C6772)
val BrandSecondaryContainer = Color(0xFFE6EBF0)

val Neutral0 = Color(0xFFFFFFFF)
val Neutral10 = Color(0xFFF8F9FC)
val Neutral20 = Color(0xFFF3F5F9)
val Neutral30 = Color(0xFFECEFF5)
val Neutral40 = Color(0xFFE0E5EE)
val Neutral50 = Color(0xFFF6F7FB)
val Neutral100 = Color(0xFFE8ECF3)
val Neutral200 = Color(0xFFD5DCE7)
val Neutral300 = Color(0xFFB3BDCC)
val Neutral500 = Color(0xFF667085)
val Neutral700 = Color(0xFF344054)
val Neutral900 = Color(0xFF101828)

val SurfaceWhite = Neutral0
val SurfaceAlt = Color(0xFFF9FAFD)
val SurfaceSubtle = Color(0xFFF2F5FA)
val DividerSubtle = Color(0xFFE4E8F0)
val SurfaceDark = Color(0xFF171B22)
val BackgroundDark = Color(0xFF0F1218)

val SemanticSuccess = Color(0xFF1F845A)
val SemanticSuccessContainer = Color(0xFFD8F4E5)
val SemanticWarning = Color(0xFFE58A00)
val SemanticWarningContainer = Color(0xFFFFE9C2)
val SemanticDanger = Color(0xFFC73A2F)
val SemanticDangerContainer = Color(0xFFFFDAD6)
val SemanticInfo = BrandPrimary
val SemanticInfoContainer = BrandPrimaryContainer

val BlockBlue = Color(0xFF4F6BED)
val BlockCyan = Color(0xFF1F88C8)
val BlockMint = Color(0xFF0F9D84)
val BlockIndigo = Color(0xFF5965D8)
val BlockViolet = Color(0xFF855AE0)
val BlockOrange = Color(0xFFE57A1F)
val BlockRose = Color(0xFFD9487E)
val BlockTeal = Color(0xFF1C8F8F)
val BlockGreen = Color(0xFF4E9F3D)
val BlockRed = Color(0xFFD95757)

val CourseColors = listOf(
    BlockBlue,
    BlockMint,
    BlockCyan,
    BlockIndigo,
    BlockViolet,
    BlockOrange,
    BlockRose,
    BlockTeal,
    BlockGreen,
    BlockRed
)

val SystemBlue = BrandPrimary
val SystemBlueDark = BrandPrimaryStrong
val SystemBlueLight = BrandPrimaryContainer

@Deprecated("Use BrandPrimary instead")
val PrimaryPurple = BrandPrimary

@Deprecated("Use BrandPrimaryContainer instead")
val PrimaryPurpleLight = BrandPrimaryContainer

@Deprecated("Use BrandPrimaryStrong instead")
val PrimaryPurpleDark = BrandPrimaryStrong

@Deprecated("Use Neutral300 instead")
val SecondaryPurple = Neutral300

@Deprecated("Use SemanticSuccess instead")
val SuccessGreen = SemanticSuccess

@Deprecated("Use SemanticDanger instead")
val ErrorRed = SemanticDanger

@Deprecated("Use SemanticWarning instead")
val WarningOrange = SemanticWarning

@Deprecated("Use SemanticInfo instead")
val InfoBlue = SemanticInfo

@Deprecated("Use Neutral50 instead")
val BackgroundLight = Neutral50

@Deprecated("Use SurfaceWhite instead")
val SurfaceLight = SurfaceWhite

@Deprecated("Use Neutral100 instead")
val PurpleGrey80 = Neutral100

// ═══════════════════════════════════════════════════════════
// 新拟态（Neumorphism）基础颜色
// 参考模板：Neumorphism 新拟态设计·组件展示
// ═══════════════════════════════════════════════════════════

/** 中性冷灰内容画布，避免主动给玻璃注入高饱和色。 */
val NeuBackground = Color(0xFFF0F2F4)

/** 内容表面与背景保持同一冷灰色阶。 */
val NeuSurface = Color(0xFFF4F5F6)

/** 新拟态左上角光源亮影（模拟光照投射） */
val NeuLightShadow = Color(0xFFFFFFFF)

/** 新拟态右下角环境暗影 */
val NeuDarkShadow = Color(0xFFD1D9E6)

/** 新拟态内凹槽背景（比 NeuBackground 略深） */
val NeuInsetBackground = Color(0xFFE2E6EC)

/** 新拟态内凹左上暗影（内阴影效果） */
val NeuInsetDarkShadow = Color(0xFFBEC8D6)

/** 新拟态内凹右下亮影（内反光效果） */
val NeuInsetLightShadow = Color(0xFFF5F9FC)

// ═══════════════════════════════════════════════════════════
// 液态玻璃（Liquid Glass）颜色
// 参考模板：Liquid Glass 液态玻璃·水滴边框效果
// ═══════════════════════════════════════════════════════════

/** 液态玻璃表面高光（半透明白色叠层） */
val GlassHighlight = Color(0xAAFFFFFF)

/** 液态玻璃弧形折射高光（月牙反光渐变起始色） */
val GlassShearHighlight = Color(0x80FFFFFF)

/** 液态折射描边-明面（顶部与左侧边框渐变色） */
val GlassBorderLight = Color(0xCCFFFFFF)

/** 液态折射描边-暗面（底部与右侧边框渐变色） */
val GlassBorderDark = Color(0x33A6B4C9)

/** 液态玻璃卡片内部半透明填充色（深色暗绿调，参照模板2） */
val GlassSurfaceDark = Color(0xCC1A2E2A)

/** 液态玻璃高光弧线色（亮侧弧线描边） */
val GlassArcHighlight = Color(0x66FFFFFF)

/** 玻璃覆盖层（卡片内微白覆盖层，增加磨砂质感） */
val GlassOverlay = Color(0x18FFFFFF)

/** 玻璃内阴影（模拟玻璃内部折射暗角） */
val GlassInnerShadow = Color(0x0DBEC8D6)

// ═══════════════════════════════════════════════════════════
// 新拟态主题适配色（覆盖原 Brand 色以柔和化）
// ═══════════════════════════════════════════════════════════

/** 新拟态主色（柔和液态蓝，替代 BrandPrimary 用于新拟态上下文） */
/** 新拟态主色（动态映射到 MaterialTheme.colorScheme.primary） */
val NeuPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

/** 新拟态主色容器（动态映射到 MaterialTheme.colorScheme.primaryContainer） */
val NeuPrimaryContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primaryContainer

/** 新拟态文字主色 */
val NeuOnSurface = Color(0xFF2E3A4D)

/** 新拟态次要文字色 */
val NeuOnSurfaceVariant = Color(0xFF8494A7)

/** 新拟态分割线（极微弱对比） */
val NeuDivider = Color(0xFFD8DFE9)

// ═══════════════════════════════════════════════════════════
// 底栏选中态强调色
// 与全局主色解耦：选中滑块改为冷灰实心后，#0A84FF 在灰底上对比不足，
// 底栏单独下沉一档蓝；深色主题反向提亮，保证同样的对比强度。
// ═══════════════════════════════════════════════════════════

val NavSelectedAccentLight = Color(0xFF0057D9)
val NavSelectedAccentDark = Color(0xFF4DA3FF)
