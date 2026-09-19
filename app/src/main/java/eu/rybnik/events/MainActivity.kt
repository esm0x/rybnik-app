package eu.rybnik.events

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import eu.rybnik.events.core.prefs.Settings
import eu.rybnik.events.core.prefs.ThemeMode
import eu.rybnik.events.ui.theme.RybnikTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

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

            RequestNotificationPermissionOnce()

            RybnikTheme(darkTheme = dark) {
                RybnikApp()
            }
        }
    }
}

/**
 * Declaring POST_NOTIFICATIONS in the manifest is not enough from Android 13 on — without
 * this prompt every reminder the app schedules is silently dropped. Asked once on first
 * start; if the user declines, Android will not show the dialog again and the toggles in
 * Settings simply have no effect.
 */
@androidx.compose.runtime.Composable
private fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Nothing to do: the reminder worker re-checks the permission before notifying. */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
