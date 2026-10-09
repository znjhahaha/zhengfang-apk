package com.tyust.course.ui.screen

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LoginFormStateTest {
    @Test
    fun rotationKeepsFormButSchoolOrAdapterChangeClearsSensitiveFields() {
        val store = ViewModelStore()
        val factory = ViewModelProvider.NewInstanceFactory()
        try {
            // Use the same public lifecycle API as the UI, not ViewModelStore's internal put.
            val state = ViewModelProvider(store, factory)[LoginFormState::class.java]
            state.selectContext("school:1/adapter:1", "")
            state.password = "synthetic-password"
            state.cookie = "SYNTHETIC=cookie"

            val recreated = ViewModelProvider(store, factory)[LoginFormState::class.java]
            assertSame(state, recreated)
            recreated.selectContext("school:1/adapter:1", "")
            assertEquals("synthetic-password", recreated.password)
            assertEquals("SYNTHETIC=cookie", recreated.cookie)

            state.selectContext("school:2/adapter:1", "")
            assertEquals("", state.password)
            assertEquals("", state.cookie)

            state.password = "synthetic-password"
            state.cookie = "SYNTHETIC=cookie"
            state.selectContext("school:2/adapter:2", "")
            assertEquals("", state.password)
            assertEquals("", state.cookie)

            state.password = "synthetic-password"
            state.cookie = "SYNTHETIC=cookie"
            store.clear()
            assertEquals("", state.password)
            assertEquals("", state.cookie)
        } finally {
            store.clear()
        }
    }
}
