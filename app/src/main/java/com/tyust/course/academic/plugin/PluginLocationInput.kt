package com.tyust.course.academic.plugin

/** The explicit input hint uses longitude first, as common Chinese map exports do. */
internal object PluginLocationInput {
    fun parse(value: String): PluginCoordinates.Point? {
        val text = value.trim().replace('，', ',').replace('：', ':')
        val number = "([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))"
        val latitude = Regex("(?:纬度|latitude|lat)\\s*:?\\s*$number", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val longitude = Regex("(?:经度|longitude|lng|lon)\\s*:?\\s*$number", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val point = if (latitude != null && longitude != null) PluginCoordinates.Point(latitude, longitude) else {
            val parts = Regex("^$number\\s*[,\\s]\\s*$number$").matchEntire(text) ?: return null
            PluginCoordinates.Point(parts.groupValues[2].toDouble(), parts.groupValues[1].toDouble())
        }
        return runCatching { PluginCoordinates.convert(point, "WGS84", "WGS84") }.getOrNull()
    }
}
