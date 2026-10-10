package com.tyust.course.academic.plugin

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class PluginLocationSearchTest {
    @Test fun chineseAddressesAreEncodedOnceAndKeepTheirCityInResults() {
        val query = "太原市万柏林区和平街道南社街"
        val url = PluginLocationSearch.searchUrl(query)
        assertEquals("plugins.hidisiwa.xyz", url.host)
        assertEquals("/api/maps/v1/search", url.encodedPath)
        assertEquals(setOf("q"), url.queryParameterNames)
        assertEquals(query, url.queryParameter("q"))
        assertTrue(url.toString().contains("%E5%A4%AA"))
        assertFalse(url.toString().contains("%25E5"))
        val places = PluginLocationSearch.parseResults("""[
            {"lat":"37.8707866","lon":"112.4750410","name":"南社街","display_name":"南社街, 和平街道, 万柏林区, 太原市, 山西省, 中国"},
            {"lat":"","lon":"112.5","name":"缺少纬度"},
            {"lat":"137","lon":"112.5","name":"无效地点"}
        ]""")
        assertEquals(1, places.size)
        assertEquals("南社街", places.single().title)
        assertTrue(places.single().address.contains("太原市"))
        assertEquals(37.8707866, places.single().point.latitude, 1e-7)
        assertEquals(112.4750410, places.single().point.longitude, 1e-7)
    }

    @Test fun mapStyleHasNoClientDependencyOnOverseasTileOrStyleHosts() {
        val style = JSONObject(PluginMapService.STYLE)
        assertFalse(style.has("glyphs"))
        assertFalse(style.has("sprite"))
        val source = style.getJSONObject("sources").getJSONObject("osm")
        assertEquals("https://plugins.hidisiwa.xyz/api/maps/v1/tiles/{z}/{x}/{y}.png", source.getJSONArray("tiles").getString(0))
        assertEquals(1, source.getJSONArray("tiles").length())
        assertEquals(256, source.getInt("tileSize"))
        assertEquals(19, source.getInt("maxzoom"))
        assertTrue(source.getString("attribution").contains("OpenStreetMap"))
    }

    @Test fun busySearchIsReportedSeparatelyFromEmptyResults() {
        assertTrue(PluginMapService.searchFailure(429).contains("繁忙"))
        assertTrue(PluginMapService.searchFailure(503).contains("繁忙"))
        assertTrue(PluginMapService.searchFailure(502).contains("暂不可用"))
        assertEquals(emptyList<PluginLocationSearch.Place>(), PluginLocationSearch.parseResults("[]"))
    }
}
