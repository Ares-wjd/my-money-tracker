package com.mymoneytracker.app.ui.goals

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mymoneytracker.app.ui.common.PlaceholderContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen() {
    Scaffold(topBar = { TopAppBar(title = { Text("목표") }) }) { padding ->
        PlaceholderContent(
            "목적통장",
            "목적통장과 목표 관리 기능이 곧 추가됩니다.",
            Modifier.padding(padding),
        )
    }
}
