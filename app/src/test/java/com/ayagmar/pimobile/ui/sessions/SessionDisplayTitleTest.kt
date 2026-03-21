package com.ayagmar.pimobile.ui.sessions

import com.ayagmar.pimobile.coresessions.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionDisplayTitleTest {
    @Test
    fun displaySubtitleUsesPreviewForNamedSessions() {
        val session =
            SessionRecord(
                sessionPath = "/tmp/session-1.jsonl",
                cwd = "/tmp",
                createdAt = "2026-03-21T00:00:00Z",
                updatedAt = "2026-03-21T00:00:00Z",
                displayName = "Bugfix work",
                firstUserMessagePreview = "Fix the failing mobile tests",
            )

        assertEquals("Bugfix work", session.displayTitle)
        assertEquals("Fix the failing mobile tests", session.displaySubtitle)
    }

    @Test
    fun displaySubtitleFallsBackToFileNameWhenNamedSessionHasNoPreview() {
        val session =
            SessionRecord(
                sessionPath = "/tmp/session-2.jsonl",
                cwd = "/tmp",
                createdAt = "2026-03-21T00:00:00Z",
                updatedAt = "2026-03-21T00:00:00Z",
                displayName = "Named session",
            )

        assertEquals("Named session", session.displayTitle)
        assertEquals("session-2.jsonl", session.displaySubtitle)
    }

    @Test
    fun displaySubtitleStaysHiddenForUnnamedSessions() {
        val session =
            SessionRecord(
                sessionPath = "/tmp/session-3.jsonl",
                cwd = "/tmp",
                createdAt = "2026-03-21T00:00:00Z",
                updatedAt = "2026-03-21T00:00:00Z",
                firstUserMessagePreview = "Preview only",
            )

        assertEquals("Preview only", session.displayTitle)
        assertNull(session.displaySubtitle)
    }
}
