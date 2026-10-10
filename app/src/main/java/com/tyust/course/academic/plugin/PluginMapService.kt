package com.tyust.course.academic.plugin

/** Public map data keeps WGS84 coordinates; no school or plugin session is sent. */
internal object PluginMapService {
    const val ORIGIN = "https://plugins.hidisiwa.xyz"
    const val SEARCH_URL = "$ORIGIN/api/maps/v1/search"
    const val STYLE = """{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://plugins.hidisiwa.xyz/api/maps/v1/tiles/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#e8ecef"}},{"id":"osm","type":"raster","source":"osm"}]}"""

    fun searchFailure(code: Int) = when (code) {
        429, 503 -> "搜索服务繁忙，请稍后重试"
        400 -> "请填写 2～120 个字符的学校或地点名称"
        else -> "地址搜索暂不可用，请重试或使用当前位置"
    }
}
