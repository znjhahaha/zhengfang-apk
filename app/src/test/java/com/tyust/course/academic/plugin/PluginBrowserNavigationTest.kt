package com.tyust.course.academic.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginBrowserNavigationTest {
    private fun upgrade(url: String) = PluginBrowserNavigation.httpsEquivalent(url) { it.startsWith("https://course.test/course/") }

    @Test fun upstreamProbeFallbackKeepsTheOriginalPathAndQueryOnHttps() {
        assertEquals("https://course.test/course/999.html?courseid=11&classid=22#task",
            upgrade("http://course.test/course/999.html?courseid=11&classid=22#task"))
    }

    @Test fun upgradeNeverAddsANewOriginPathPortOrCredential() {
        for (url in listOf("https://course.test/course/1", "http://other.test/course/1", "http://course.test/other/1",
            "http://course.test:8080/course/1", "http://user:secret@course.test/course/1", "http://course.test/course%2fexam/1"))
            assertNull(url, upgrade(url))
    }
}
