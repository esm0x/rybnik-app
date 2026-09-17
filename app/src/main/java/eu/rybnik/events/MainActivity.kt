package eu.rybnik.events

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import eu.rybnik.events.core.prefs.Settings
import eu.rybnik.events.core.prefs.ThemeMode
import eu.rybnik.events.ui.theme.RybnikTheme
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = Graph.prefs.settings
            .stateIn(lifecycleScope, SharingStarted.Eagerly, Settings())

        setContent {
            val current by settings.collectAsState()
            val dark = when (current.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            RybnikTheme(darkTheme = dark) {
                RybnikApp()
            }
        }
    }
}
