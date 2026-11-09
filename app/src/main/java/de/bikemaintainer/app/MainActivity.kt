package de.bikemaintainer.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import de.bikemaintainer.app.ui.navigation.AppNavHost
import de.bikemaintainer.app.ui.navigation.startDestination
import de.bikemaintainer.app.ui.theme.BikeMaintainerTheme

// AppCompatActivity: AppCompatDelegate.setApplicationLocales needs it below Android 13.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BikeMaintainerApp).container
        setContent {
            BikeMaintainerTheme {
                // Read once; until then an empty surface in the background colour.
                val start by produceState<Any?>(initialValue = null) {
                    value = startDestination(container.settings.current())
                }
                val destination = start
                if (destination == null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                } else {
                    AppNavHost(container = container, start = destination)
                }
            }
        }
    }
}
