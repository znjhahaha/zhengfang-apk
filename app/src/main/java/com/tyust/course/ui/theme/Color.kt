package com.tyust.course.ui.theme

import androidx.compose.ui.graphics.Color

// Apple iOS System Colors (2024 Design Standard)
val SystemBlue = Color(0xFF007AFF) // iOS Default Blue
val SystemBlueDark = Color(0xFF0A84FF) // iOS Dark Mode Blue
val SystemBlueLight = Color(0xFFE5F1FF) // 10% Opacity Blue for Light Mode Surfaces

// Solid Tints for Schedule/Course Blocks (Apple System Colors)
val BlockMint = Color(0xFF00C7BE)    // systemMint
val BlockCyan = Color(0xFF32ADE6)    // systemCyan
val BlockIndigo = Color(0xFF5E5CE6)  // systemIndigo
val BlockViolet = Color(0xFFAF52DE)  // systemPurple
val BlockOrange = Color(0xFFFF9500)  // systemOrange
val BlockRose = Color(0xFFFF2D55)    // systemPink

// Apple Native Neutral Scale (iOS Light Mode System Colors)
val Neutral50 = Color(0xFFF2F2F7)    // Secondary System Background (Inset Grouped)
val Neutral100 = Color(0xFFE5E5EA)   // System Gray 5 (Dividers / Subtle borders)
val Neutral200 = Color(0xFFD1D1D6)   // System Gray 4 (Disabled states)
val Neutral300 = Color(0xFFC7C7CC)   // System Gray 3 (Icons)
val Neutral500 = Color(0xFF8E8E93)   // System Gray (Secondary Label/Subtitle)
val Neutral700 = Color(0xFF3C3C43)   // Primary text secondary variant (60% black)
val Neutral900 = Color(0xFF000000)   // Label (Absolute Black)

// Functional Semantic Colors (Apple System)
val SemanticSuccess = Color(0xFF34C759) // systemGreen
val SemanticWarning = Color(0xFFFFCC00) // systemYellow
val SemanticDanger = Color(0xFFFF3B30) // systemRed

// Surface Base
val SurfaceWhite = Color(0xFFFFFFFF)
val SurfaceDark = Color(0xFF1C1C1E)  // iOS System Background Dark
val BackgroundDark = Color(0xFF000000) // iOS Pure Black Background

// ==========================================
// ⚠️ DEPRECATED OLD TOKENS (For legacy compatibility)
// To be removed during Phase 3/4 per-page refactoring
// ==========================================
@Deprecated("Use SystemBlue instead")
val PrimaryPurple = SystemBlue
@Deprecated("Use SystemBlueLight instead")
val PrimaryPurpleLight = SystemBlueLight
@Deprecated("Use SystemBlueDark instead")
val PrimaryPurpleDark = SystemBlueDark
@Deprecated("Use Neutral300 instead")
val SecondaryPurple = Neutral300
@Deprecated("Use SemanticSuccess instead")
val SuccessGreen = SemanticSuccess
@Deprecated("Use SemanticDanger instead")
val ErrorRed = SemanticDanger
@Deprecated("Use SemanticWarning instead")
val WarningOrange = SemanticWarning
@Deprecated("Use SystemBlue instead")
val InfoBlue = SystemBlue
@Deprecated("Use Neutral50 instead")
val BackgroundLight = Neutral50
@Deprecated("Use SurfaceWhite instead")
val SurfaceLight = SurfaceWhite
@Deprecated("Use Neutral100 instead")
val PurpleGrey80 = Neutral100

val CourseColors = listOf(
    SystemBlue,
    BlockMint,
    BlockCyan,
    BlockIndigo,
    BlockViolet,
    BlockOrange,
    BlockRose,
    Color(0xFF34D399),
    Color(0xFF818CF8),
    Color(0xFFEC4899)
)
