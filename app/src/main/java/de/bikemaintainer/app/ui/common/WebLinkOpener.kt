package de.bikemaintainer.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import de.bikemaintainer.app.R

/**
 * Opens [url] in the system browser (no WebView). Shows a short toast instead of crashing when no app can handle it
 * or the URL is malformed. Returns whether a viewer was started.
 */
fun openWebLink(context: Context, url: String): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_browser_found, Toast.LENGTH_SHORT).show()
        false
    } catch (_: IllegalArgumentException) {
        Toast.makeText(context, R.string.no_browser_found, Toast.LENGTH_SHORT).show()
        false
    }

@Composable
fun rememberWebLinkOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { url -> openWebLink(context, url) } }
}
