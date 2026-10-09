package com.tyust.course.academic.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginScriptPresentationTest {
    @Test fun requestFailureWinsOverAnAlreadyClickedStartButton() {
        assertEquals("error", PluginScriptPresentation.resolve(listOf(PluginScriptPresentation.Document("error", false, true)), 1000).status)
        assertEquals("needs_input", PluginScriptPresentation.resolve(listOf(PluginScriptPresentation.Document("error", false, true), PluginScriptPresentation.Document("interaction", false, false)), 1000).status)
    }
    private fun doc(stage: String, available: Boolean = false, running: Boolean = false) =
        PluginScriptPresentation.Document(stage, available, running)

    @Test fun connectionAndEmptyFramesNeverReportReady() {
        for (documents in listOf(emptyList(), listOf(doc("")), listOf(doc("loading")))) {
            val state = PluginScriptPresentation.resolve(documents, 1000)
            assertEquals("loading", state.status)
            assertFalse(state.available)
            assertEquals("unavailable", PluginScriptPresentation.resolve(documents, 45000).status)
        }
    }

    @Test fun upstreamEntryTaskButtonAndPromptAreDistinct() {
        assertEquals("entry", PluginScriptPresentation.resolve(listOf(doc("entry", true)), 1000).status)
        assertEquals("ready", PluginScriptPresentation.resolve(listOf(doc("ready", true)), 1000).status)
        assertEquals("loading", PluginScriptPresentation.resolve(listOf(doc("opening")), 1000).status)
        val prompt = PluginScriptPresentation.resolve(listOf(doc("ready", true), doc("interaction")), 1000)
        assertEquals("needs_input", prompt.status)
        assertFalse(prompt.available)
        assertEquals("needs_input", PluginScriptPresentation.resolve(listOf(doc("running", true, true), doc("interaction")), 1000).status)
    }

    @Test fun runningFrameWinsOverAnOlderEntryAndNavigationClearsReadiness() {
        assertEquals("running", PluginScriptPresentation.resolve(listOf(doc("entry", true), doc("running", true, true)), 90000).status)
        assertEquals("loading", PluginScriptPresentation.resolve(emptyList(), 0).status)
    }

    @Test fun entryNavigationHasABoundedWaitAndNewerTaskStateWins() {
        val opening = doc("opening")
        assertEquals("loading", PluginScriptPresentation.resolve(listOf(opening), 44_999).status)
        assertEquals("unavailable", PluginScriptPresentation.resolve(listOf(opening), 45_000).status)
        assertEquals("ready", PluginScriptPresentation.resolve(listOf(opening, doc("ready", true)), 60_000).status)
        assertEquals("needs_input", PluginScriptPresentation.resolve(listOf(opening, doc("interaction")), 60_000).status)
    }
}
