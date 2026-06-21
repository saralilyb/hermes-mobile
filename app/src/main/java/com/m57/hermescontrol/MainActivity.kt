package com.m57.hermescontrol

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.notification.NotificationReplyReceiver
import com.m57.hermescontrol.theme.HermesControlTheme

class MainActivity : ComponentActivity() {
    // Observable state so both onCreate and onNewIntent updates flow to Compose
    private var notificationSessionId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read notification tap sessionId (if any) from the intent that launched us
        notificationSessionId = intent?.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)

        enableEdgeToEdge()
        setContent {
            val themePreference by AuthManager.themePreferenceFlow.collectAsState()
            val useDynamicColors by AuthManager.useDynamicColorsFlow.collectAsState()
            val themePreset by AuthManager.themePresetFlow.collectAsState()
            HermesControlTheme(
                themePreference = themePreference,
                useDynamicColors = useDynamicColors,
                themePreset = themePreset,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainNavigation(sessionId = notificationSessionId)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // When a notification tap arrives while the app is already running
        // (task exists in background), Android delivers the intent here instead
        // of onCreate. Update the observable state so Compose recomposes
        // and ChatScreen's LaunchedEffect switches to the correct session.
        notificationSessionId = intent.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)
    }
}
