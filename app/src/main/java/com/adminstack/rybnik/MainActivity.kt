package com.adminstack.rybnik

import android.Manifest
import android.content.Intent
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
import com.adminstack.rybnik.core.prefs.Settings
import com.adminstack.rybnik.core.prefs.ThemeMode
import com.adminstack.rybnik.ui.theme.RybnikTheme
import com.adminstack.rybnik.work.Reminders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class MainActivity : ComponentActivity() {

    /**
     * Screen requested by a tapped notification. A flow rather than a one-off read of
     * the launch intent, because the activity is singleTop: when the app is already open
     * the tap arrives through onNewIntent and onCreate never runs again.
     */
    private val notificationDestination = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        notificationDestination.value = intent?.getStringExtra(Reminders.EXTRA_DESTINATION)

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

            val destination by notificationDestination.collectAsState()

            RybnikTheme(darkTheme = dark) {
                RybnikApp(
                    notificationDestination = destination,
                    onDestinationHandled = { notificationDestination.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationDestination.value = intent.getStringExtra(Reminders.EXTRA_DESTINATION)
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
