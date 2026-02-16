package com.ayagmar.pimobile.ui.sessions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.sessions.CwdSessionGroupUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CwdChipSelectorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersPathTailLabelsWithSessionCounts() {
        composeRule.setContent {
            CwdChipSelector(
                groups =
                    listOf(
                        group(
                            cwd = "/home/ayagmar/Projects/pi-mobile",
                            sessionCount = 2,
                        ),
                        group(
                            cwd = "/home/ayagmar",
                            sessionCount = 1,
                        ),
                    ),
                selectedCwd = "/home/ayagmar/Projects/pi-mobile",
                onCwdSelected = {},
            )
        }

        composeRule.onNodeWithText("Projects/pi-mobile (2)").assertIsDisplayed()
        composeRule.onNodeWithText("home/ayagmar (1)").assertIsDisplayed()
    }

    @Test
    fun clickingChipInvokesSelectionCallbackWithFullCwd() {
        var selected: String? = null

        composeRule.setContent {
            CwdChipSelector(
                groups =
                    listOf(
                        group(cwd = "/home/ayagmar/Projects/pi-mobile", sessionCount = 2),
                        group(cwd = "/home/ayagmar", sessionCount = 1),
                    ),
                selectedCwd = "/home/ayagmar/Projects/pi-mobile",
                onCwdSelected = { cwd -> selected = cwd },
            )
        }

        composeRule.onNodeWithText("home/ayagmar (1)").performClick()

        assertEquals("/home/ayagmar", selected)
    }

    private fun group(
        cwd: String,
        sessionCount: Int,
    ): CwdSessionGroupUiState {
        return CwdSessionGroupUiState(
            cwd = cwd,
            sessions =
                List(sessionCount) { index ->
                    SessionRecord(
                        sessionPath = "$cwd/session-$index.jsonl",
                        cwd = cwd,
                        createdAt = "2026-02-10T10:00:00Z",
                        updatedAt = "2026-02-10T11:00:00Z",
                    )
                },
        )
    }
}
