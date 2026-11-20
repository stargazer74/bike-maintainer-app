package de.bikemaintainer.app.ui.vehicle

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import de.bikemaintainer.app.domain.PendingItem
import de.bikemaintainer.app.domain.PendingProblem
import de.bikemaintainer.app.domain.PendingState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-h1200dp")
class PendingSectionTest {
    @get:Rule val compose = createComposeRule()

    private fun log(id: String, state: PendingState) =
        PendingItem(id, 7, PendingItem.Kind.MAINTENANCE, 24100, LocalDate.of(2026, 11, 1), setOf(1L), state)

    private fun mileage(id: String, state: PendingState) = PendingItem(id, 7, PendingItem.Kind.MILEAGE, 12000, state = state)

    @Test fun onlyWhatEditingCanFixOffersEditing() {
        val edited = mutableListOf<String>()
        compose.setContent {
            PendingSection(
                listOf(
                    log("rejected", PendingState.Failed(PendingProblem.REJECTED)),
                    log("waiting", PendingState.Waiting),
                    log("gone", PendingState.Failed(PendingProblem.VEHICLE_GONE)),
                    mileage("lower", PendingState.Failed(PendingProblem.MILEAGE_BELOW_CURRENT)),
                ),
                emptyList(),
                onSendAnyway = {},
                onDiscard = {},
                onEdit = { edited += it.id },
            )
        }
        compose.onAllNodesWithText("Bearbeiten").assertCountEquals(2)
        compose.onAllNodesWithText("Bearbeiten")[0].performClick()
        compose.onAllNodesWithText("Bearbeiten")[1].performClick()
        assertEquals(listOf("rejected", "lower"), edited)
        compose.onNodeWithText("Bearbeite oder verwirf den Eintrag.", substring = true).assertExists()
    }

    @Test fun aPossibleDuplicateCanBeEditedOrSentAnyway() {
        compose.setContent {
            PendingSection(
                listOf(log("dup", PendingState.PossibleDuplicate(LocalDate.of(2026, 11, 1)))),
                emptyList(),
                onSendAnyway = {},
                onDiscard = {},
            )
        }
        compose.onNodeWithText("Bearbeiten").assertExists()
        compose.onNodeWithText("Trotzdem übertragen").assertExists()
    }
}
