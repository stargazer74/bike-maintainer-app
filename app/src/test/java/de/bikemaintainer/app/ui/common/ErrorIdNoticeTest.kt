package de.bikemaintainer.app.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ErrorIdNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun showsTheErrorIdUntilClosed() {
        var errorId by mutableStateOf<String?>("4bf92f35")
        compose.setContent { ErrorIdNotice(errorId = errorId, onDismiss = { errorId = null }) }

        compose.onNodeWithText("Error ID: 4bf92f35", substring = true).assertIsDisplayed()

        compose.onNodeWithText("Close").performClick()

        compose.onAllNodesWithText("Error ID", substring = true).assertCountEquals(0)
    }

    @Test fun showsNothingWithoutErrorId() {
        compose.setContent { ErrorIdNotice(errorId = null, onDismiss = {}) }

        compose.onAllNodes(hasText("Error ID", substring = true)).assertCountEquals(0)
    }
}
