package com.mymoneytracker.app.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.mymoneytracker.app.data.SignedInUser
import com.mymoneytracker.app.ui.common.PlaceholderContent

@Composable
fun SettingsScreen(user: SignedInUser, onSignOut: () -> Unit) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    PlaceholderContent(
        title = user.displayName ?: "설정",
        description = "${user.email.orEmpty()}\n데이터는 이 Google 계정의 클라우드 저장소에 저장됩니다.",
    ) {
        OutlinedButton(onClick = { confirmSignOut = true }) { Text("로그아웃") }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("로그아웃") },
            text = { Text("로그아웃할까요? 데이터는 클라우드에 그대로 남아 있습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) { Text("로그아웃") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("취소") } },
        )
    }
}
