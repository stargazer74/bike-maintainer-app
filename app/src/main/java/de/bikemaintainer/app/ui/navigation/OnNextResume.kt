package de.bikemaintainer.app.ui.navigation

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Runs [action] once this page is resumed, i.e. on top and done entering: at once if it is already, otherwise when it
 * is shown again (app#100). Nothing happens if the page is gone before that.
 */
fun LifecycleOwner.onNextResume(action: () -> Unit) {
    lifecycle.addObserver(
        object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                action()
            }
        },
    )
}
