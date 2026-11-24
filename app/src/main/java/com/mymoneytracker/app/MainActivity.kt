package com.mymoneytracker.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import com.mymoneytracker.app.ui.AlertLinks
import com.mymoneytracker.app.ui.AppRoot
import com.mymoneytracker.app.ui.SetupRequiredScreen
import com.mymoneytracker.app.ui.theme.MoneyTrackerTheme
import com.mymoneytracker.app.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 알림을 눌러 열었으면 그 화면으로 (v2.11)
        AlertLinks.handle(intent)
        val firebaseReady = (application as MoneyTrackerApp).firebaseReady
        ThemeMode.init(this)
        setContent {
            val dark = ThemeMode.chosen.collectAsState().value ?: isSystemInDarkTheme()
            // 고른 화면 모드에 맞춰 상태 표시줄 아이콘 색도 바꾼다 (v2.18)
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            MoneyTrackerTheme(darkTheme = dark) {
                if (firebaseReady) AppRoot() else SetupRequiredScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AlertLinks.handle(intent)
    }
}
