package com.tyust.course.academic.plugin

import org.junit.Assert.*
import org.junit.Test

class NativeInputReconciliationTest {
    @Test fun slowAcknowledgementDoesNotRevertNewerTypingOrDragging() {
        val state = NativeInputReconciliation()
        val first = state.edit("address", "太原")
        val latest = state.edit("address", "太原科技大学")
        val slider = state.edit("speed", 2.5)
        state.acknowledge(first)
        assertEquals("太原科技大学", state.values["address"])
        state.acknowledge(latest)
        assertFalse(state.values.containsKey("address"))
        assertEquals(2.5, state.values["speed"])
        state.acknowledge(slider)
        assertTrue(state.values.isEmpty())
    }

    @Test fun finalAcknowledgementReleasesOptimismForReducerNormalizationOrRejection() {
        val state = NativeInputReconciliation()
        val selection = state.edit("tabs", "attendance")
        assertEquals("attendance", state.values["tabs"])
        state.acknowledge(selection)
        assertTrue(state.values.isEmpty())
    }

    @Test fun oldPageAcknowledgementCannotConsumeAnotherPagesEditWithTheSameId() {
        val state = NativeInputReconciliation()
        val retired = state.edit("code", "1234")
        state.clear()
        val current = state.edit("code", "5678")
        state.acknowledge(retired)
        assertEquals("5678", state.values["code"])
        state.acknowledge(current)
        assertTrue(state.values.isEmpty())
    }
}
