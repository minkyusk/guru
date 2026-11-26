package com.clauderelay.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clauderelay.app.ui.AppViewModel
import com.clauderelay.app.ui.ChatScreen
import com.clauderelay.app.ui.Screen
import com.clauderelay.app.ui.SessionListScreen
import com.clauderelay.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) vm.openFromNotification(intent?.getStringExtra(Notifier.EXTRA_SESSION))
        setContent {
            RelayTheme {
                val ui by vm.ui.collectAsStateWithLifecycle()
                BackHandler(enabled = ui.screen != Screen.Sessions) { vm.back() }
                when (val s = ui.screen) {
                    Screen.Settings -> SettingsScreen(ui, vm)
                    Screen.Sessions -> SessionListScreen(ui, vm)
                    is Screen.Chat -> ChatScreen(ui, vm, s.sessionId)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.openFromNotification(intent.getStringExtra(Notifier.EXTRA_SESSION))
    }
}

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFE8A087))
        else -> lightColorScheme(primary = Color(0xFFC2603F))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
