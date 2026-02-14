package com.ayagmar.pimobile.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDraftTest {
    @Test
    fun validateAcceptsCompleteHostDraft() {
        val draft =
            HostDraft(
                name = "Laptop",
                host = "100.64.0.10",
                port = "8765",
                useTls = true,
            )

        val validation = draft.validate()

        assertTrue(validation is HostValidationResult.Valid)
        val valid = validation as HostValidationResult.Valid
        assertEquals("Laptop", valid.profile.name)
        assertEquals("100.64.0.10", valid.profile.host)
        assertEquals(8765, valid.profile.port)
        assertEquals(true, valid.profile.useTls)
    }

    @Test
    fun validateRejectsInvalidPort() {
        val draft =
            HostDraft(
                name = "Laptop",
                host = "100.64.0.10",
                port = "99999",
            )

        val validation = draft.validate()

        assertTrue(validation is HostValidationResult.Invalid)
        val invalid = validation as HostValidationResult.Invalid
        assertEquals("Port must be between 1 and 65535", invalid.reason)
    }
}
