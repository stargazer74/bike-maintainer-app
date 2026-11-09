package de.bikemaintainer.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController

/**
 * A back action for an up arrow: pops only while the calling entry is resumed, so a double tap (the second tap arrives
 * during the exit transition) does not pop the page below as well.
 */
@Composable
fun NavController.popBackOnce(): () -> Unit = dropUnlessResumed { popBackStack() }
