package com.tyust.course.academic.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginLocationSearchTest {
    @Test fun chineseAddressesAreEncodedOnceAndKeepTheirCityInResults() {
        val query = "太原市万柏林区和平街道南社街"
        val url = PluginLocationSearch.searchUrl(query)
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
}
