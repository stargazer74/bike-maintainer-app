package de.bikemaintainer.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import de.bikemaintainer.app.ui.navigation.AppNavHost
import de.bikemaintainer.app.ui.navigation.readStartDestination
import de.bikemaintainer.app.ui.theme.AppSurface
import de.bikemaintainer.app.ui.theme.BikeMaintainerTheme

// AppCompatActivity: AppCompatDelegate.setApplicationLocales needs it below Android 13.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BikeMaintainerApp).container
        setContent {
            BikeMaintainerTheme {
                AppSurface {
                    // Read once; until then only the background. Unreadable settings start at the setup.
                    val start by produceState<Any?>(initialValue = null) {
                        value = readStartDestination { container.settings.current() }
                    }
                    start?.let { AppNavHost(container = container, start = it) }
                }
            }
        }
    }
}
