package de.bikemaintainer.app.ui.taskform

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.TaskDraft
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class TaskFormScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: TaskFormUiState) {
        compose.setContent { TaskFormContent(state, onBack = {}, onRetry = {}, onEdit = {}, onSave = {}) }
    }

    @Test fun deletedTaskSaysTheTaskIsGone() {
        show(TaskFormUiState(editing = true, loadError = AppError.NotFound))
        compose.onNodeWithText("Die Aufgabe wurde nicht gefunden.").assertExists()
        compose.onNodeWithText("Das Fahrzeug wurde nicht gefunden.").assertDoesNotExist()
    }

    @Test fun taskDeletedWhileEditingSaysSoOnSave() {
        show(TaskFormUiState(editing = true, draft = TaskDraft(name = "Kette", intervalKm = "1000"), saveError = AppError.NotFound))
        compose.onNodeWithText("Die Aufgabe wurde nicht gefunden.").assertExists()
    }

    @Test fun missingIntervalIsExplained() {
        show(TaskFormUiState(editing = false, draft = TaskDraft(name = "Kette"), showErrors = true))
        compose.onNodeWithText("Bitte ein Intervall in km und/oder Monaten eingeben.").assertExists()
    }
}
