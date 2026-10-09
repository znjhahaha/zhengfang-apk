package com.tyust.course.academic.plugin

import org.junit.Assert.*
import org.junit.Test

class NativeInputEventsTest {
    private data class Input(var value: String)

    @Test fun synchronousConsumerDoesNotLeaveAStaleInputThatSwallowsTheNextEvent() {
        val events = NativeInputEvents<Input> { first, next -> first.value = next.value }
        val delivered = mutableListOf<String>()
        repeat(20) { round ->
            for (page in listOf("courses", "work", "attendance", "exams", "tasks", "account")) {
                events.offer("control", Input(page + round)) { pending ->
                    events.consumed(pending)
                    delivered += pending.value
                    true
                }
            }
        }
        assertEquals(120, delivered.size)
        assertEquals("account19", delivered.last())
    }

    @Test fun onlyWaitingContinuousInputsMergeAndDiscreteSelectionsStayOrdered() {
        val events = NativeInputEvents<Input> { first, next -> first.value = next.value }
        val pending = mutableListOf<Input>()
        val send: (Input) -> Boolean = { pending += it; true }
        events.offer("search", Input("a"), send)
        events.offer("search", Input("abc"), send)
        events.offer(null, Input("tasks"), send)
        events.offer(null, Input("courses"), send)
        assertEquals(listOf("abc", "tasks", "courses"), pending.map { it.value })
        events.consumed(pending.first())
        events.offer("search", Input("abcd"), send)
        assertEquals(4, pending.size)
    }

    @Test fun rejectedAndRetiredInputsCannotSwallowLaterInput() {
        val events = NativeInputEvents<Input> { first, next -> first.value = next.value }
        assertFalse(events.offer("a", Input("old")) { false })
        var calls = 0
        assertTrue(events.offer("a", Input("next")) { calls++; true })
        events.clear()
        assertTrue(events.offer("a", Input("new account")) { calls++; true })
        assertEquals(2, calls)
    }
}
