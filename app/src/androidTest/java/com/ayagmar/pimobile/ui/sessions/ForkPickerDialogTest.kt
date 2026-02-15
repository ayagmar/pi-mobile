package com.ayagmar.pimobile.ui.sessions

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ayagmar.pimobile.sessions.ForkableMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForkPickerDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectingCandidateInvokesCallbackWithEntryId() {
        var selectedEntryId: String? = null

        composeRule.setContent {
            ForkPickerDialog(
                isLoading = false,
                candidates =
                    listOf(
                        ForkableMessage(entryId = "entry-1", preview = "First", timestamp = null),
                        ForkableMessage(entryId = "entry-2", preview = "Second", timestamp = null),
                    ),
                onDismiss = {},
                onSelect = { entryId -> selectedEntryId = entryId },
            )
        }

        composeRule.onNodeWithText("Second").performClick()

        assertEquals("entry-2", selectedEntryId)
    }
}
