package com.mymoneytracker.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mymoneytracker.app.ui.AlertLinks
import com.mymoneytracker.app.ui.AppRoot
import com.mymoneytracker.app.ui.SetupRequiredScreen
import com.mymoneytracker.app.ui.theme.MoneyTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 알림을 눌러 열었으면 그 화면으로 (v2.11)
        AlertLinks.handle(intent)
        val firebaseReady = (application as MoneyTrackerApp).firebaseReady
        setContent {
            MoneyTrackerTheme {
                if (firebaseReady) AppRoot() else SetupRequiredScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AlertLinks.handle(intent)
    }
}
