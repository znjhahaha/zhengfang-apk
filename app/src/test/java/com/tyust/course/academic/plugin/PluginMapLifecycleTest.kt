package com.tyust.course.academic.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginMapLifecycleTest {
    @Test fun openingAfterResumeDoesNotStartTheMapTwiceAndDisposalPairsEveryCall() {
        val calls = mutableListOf<String>()
        val map = PluginMapLifecycle({ calls += "start" }, { calls += "resume" }, { calls += "pause" }, { calls += "stop" }, { calls += "destroy" })
        map.update(true, true)
        map.update(true, true)
        map.update(true, false)
        map.update(false, false)
        map.update(true, true)
        map.close()
        map.close()
        map.update(true, true)
        assertEquals(listOf("start", "resume", "pause", "stop", "start", "resume", "pause", "stop", "destroy"), calls)
    }
}
