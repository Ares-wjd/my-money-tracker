package com.mymoneytracker.app.ui.update

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 새 버전 안내 → 다운로드 → 설치 화면 열기. */
@Composable
fun UpdateDialog(state: UpdateState, viewModel: AppUpdateViewModel) {
    val context = LocalContext.current
    when (state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { viewModel.dismissDialog() },
            title = { Text("새 버전이 있습니다") },
            text = {
                Text(
                    "${state.release.title}\n현재 설치된 버전: 빌드 #${viewModel.repository.installedVersionCode}\n\n" +
                        "업데이트해도 데이터와 로그인은 그대로 유지됩니다.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.download() }) { Text("설치") } },
            dismissButton = { TextButton(onClick = { viewModel.skip() }) { Text("나중에") } },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("내려받는 중") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(state.progress * 100).toInt()}%")
                }
            },
            confirmButton = {},
        )

        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = { viewModel.dismissDialog() },
            title = { Text("설치 준비 완료") },
            text = {
                Text(
                    "설치를 누르면 설치 화면이 열립니다.\n\n" +
                        "처음 한 번은 이 앱의 \"출처를 알 수 없는 앱 설치\" 허용 화면이 먼저 열립니다. " +
                        "허용을 켠 뒤 돌아와서 설치를 한 번 더 누르세요.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val repository = viewModel.repository
                    val intent = if (repository.canInstallPackages()) {
                        repository.installIntent(state.apk)
                    } else {
                        repository.unknownSourcesSettingsIntent()
                    }
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        viewModel.resetAfterFailure()
                    }
                }) { Text("설치") }
            },
            dismissButton = { TextButton(onClick = { viewModel.dismissDialog() }) { Text("닫기") } },
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { viewModel.resetAfterFailure() },
            title = { Text("업데이트") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = { viewModel.resetAfterFailure() }) { Text("확인") } },
        )

        else -> Unit
    }
}
