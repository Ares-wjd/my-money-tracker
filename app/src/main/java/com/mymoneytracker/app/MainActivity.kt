package com.mymoneytracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mymoneytracker.app.ui.AppRoot
import com.mymoneytracker.app.ui.SetupRequiredScreen
import com.mymoneytracker.app.ui.theme.MoneyTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val firebaseReady = (application as MoneyTrackerApp).firebaseReady
        setContent {
            MoneyTrackerTheme {
                if (firebaseReady) AppRoot() else SetupRequiredScreen()
            }
        }
    }
}
