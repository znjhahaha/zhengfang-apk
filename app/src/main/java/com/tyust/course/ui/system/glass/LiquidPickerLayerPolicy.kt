package com.tyust.course.ui.system.glass

/** Keeps the picker's outer optical boundary stable while the body changes topology. */
internal object LiquidPickerLayerPolicy {
    enum class Layers(
        val headerLensAlpha: Float,
        val mergedBodyLensAlpha: Float,
        val separatedBodyLensAlpha: Float,
        val perimeterInteractionProgress: Float
    ) {
        HeaderOnly(1f, 0f, 0f, 0f),
        Merging(1f, 1f, 0f, 0f),
        Separated(1f, 0f, 1f, 0f)
    }

    fun resolve(
        bodyActive: Boolean,
        bodySeparated: Boolean,
        bodyAlpha: Float,
        @Suppress("UNUSED_PARAMETER") interactionProgress: Float
    ): Layers {
        if (!bodyActive || bodyAlpha <= 0f) return Layers.HeaderOnly
        return if (bodySeparated) Layers.Separated else Layers.Merging
    }
}
