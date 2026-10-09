package com.mymoneytracker.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.data.SignedInUser
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.update.AppUpdateViewModel
import com.mymoneytracker.app.ui.update.UpdateState
import com.mymoneytracker.core.MoneyFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: SignedInUser,
    portfolioViewModel: PortfolioViewModel,
    updateViewModel: AppUpdateViewModel,
    onSignOut: () -> Unit,
) {
    val data by portfolioViewModel.data.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("설정") }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "계정") {
                Text(user.displayName.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                Text(user.email.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "데이터는 이 Google 계정의 클라우드 저장소에 저장됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { confirmSignOut = true }) { Text("로그아웃") }
            }

            data?.let { current ->
                ExchangeRateCard(
                    savedRate = current.settings.manualUsdKrw,
                    onSave = { portfolioViewModel.saveManualUsdKrw(it) },
                )
            }

            SectionCard(title = "앱 정보") {
                LabeledValue(
                    "현재 버전",
                    "${updateViewModel.repository.installedVersionName} (빌드 #${updateViewModel.repository.installedVersionCode})",
                )
                val status = when (val s = updateState) {
                    UpdateState.Checking -> "확인 중..."
                    UpdateState.UpToDate -> "최신 버전입니다."
                    is UpdateState.Available -> "새 버전이 있습니다: 빌드 #${s.release.versionCode}"
                    is UpdateState.Downloading -> "내려받는 중 ${(s.progress * 100).toInt()}%"
                    is UpdateState.ReadyToInstall -> "설치 준비 완료"
                    else -> null
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = { updateViewModel.check(manual = true) }) { Text("업데이트 확인") }
            }
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = "로그아웃",
            text = "로그아웃할까요? 데이터는 클라우드에 그대로 남아 있습니다.",
            confirmLabel = "로그아웃",
            onConfirm = onSignOut,
            onDismiss = { confirmSignOut = false },
        )
    }
}

@Composable
private fun ExchangeRateCard(savedRate: Double?, onSave: (Double?) -> Unit) {
    var rateText by rememberSaveable { mutableStateOf(savedRate?.let { MoneyFormat.decimal(it, 2).replace(",", "") }.orEmpty()) }
    LaunchedEffect(savedRate) {
        if (MoneyFormat.parseDecimal(rateText) != savedRate) {
            rateText = savedRate?.let { MoneyFormat.decimal(it, 2).replace(",", "") }.orEmpty()
        }
    }
    val rate = MoneyFormat.parseDecimal(rateText)

    SectionCard(title = "원/달러 환율") {
        Text(
            "환율 API 를 연결하기 전까지 달러 자산의 원화 환산에 이 환율을 사용합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NumberField(
            label = "1달러 = ?원",
            value = rateText,
            onValueChange = { rateText = it },
            preview = { "1달러 = ${MoneyFormat.decimal(it, 2)}원" },
        )
        TextButton(
            onClick = { onSave(if (rateText.isBlank()) null else rate) },
            enabled = rateText.isBlank() || (rate != null && rate > 0),
        ) { Text("환율 저장") }
    }
}
