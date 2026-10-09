package com.mymoneytracker.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.data.SignedInUser
import com.mymoneytracker.app.ui.ApiStatus
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.ChipSelector
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.DateField
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.app.ui.update.AppUpdateViewModel
import com.mymoneytracker.app.ui.update.UpdateState
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.InvestmentAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: SignedInUser,
    portfolioViewModel: PortfolioViewModel,
    updateViewModel: AppUpdateViewModel,
    onSignOut: () -> Unit,
    onDeleteAccount: suspend (Context) -> Unit,
) {
    val data by portfolioViewModel.data.collectAsStateWithLifecycle()
    val apiStatus by portfolioViewModel.apiStatus.collectAsStateWithLifecycle()
    val syncing by portfolioViewModel.syncing.collectAsStateWithLifecycle()
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

            KisCard(apiStatus, portfolioViewModel)
            KisLinkCard(apiStatus, data?.accounts.orEmpty(), syncing, portfolioViewModel)
            EximCard(apiStatus, portfolioViewModel)

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

            DeleteAccountCard(onDelete = { context ->
                portfolioViewModel.clearLocalSecrets()
                updateViewModel.repository.skippedVersionCode = null
                onDeleteAccount(context)
            })
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = "로그아웃",
            text = "로그아웃할까요? 데이터는 클라우드에 그대로 남아 있습니다. 이 기기에 저장한 API 키도 그대로 남습니다.",
            confirmLabel = "로그아웃",
            onConfirm = onSignOut,
            onDismiss = { confirmSignOut = false },
        )
    }
}

@Composable
private fun SecretField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.trim().take(400)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KisCard(status: ApiStatus, viewModel: PortfolioViewModel) {
    var appKey by rememberSaveable { mutableStateOf("") }
    var appSecret by rememberSaveable { mutableStateOf("") }
    var expiryDay by rememberSaveable {
        mutableLongStateOf((status.kisServiceExpiry ?: LocalDate.now().plusYears(1)).toEpochDay())
    }

    SectionCard(title = "한국투자증권 Open API (시세)") {
        Text(
            "국내·해외 주식과 ETF 의 시세를 자동으로 받습니다. 키는 이 기기에만 암호화해 저장되고 클라우드에 올라가지 않습니다. " +
                "이 앱은 조회 기능만 사용하며 주문 기능은 들어 있지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledValue("저장된 키", status.kisKeyHint ?: "없음")
        SecretField("App Key", appKey) { appKey = it }
        SecretField("App Secret", appSecret) { appSecret = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    viewModel.saveKisCredentials(appKey, appSecret)
                    appKey = ""
                    appSecret = ""
                },
                enabled = appKey.isNotBlank() && appSecret.isNotBlank(),
            ) { Text("저장") }
            TextButton(onClick = { viewModel.testKis() }, enabled = status.hasKis) { Text("연결 테스트") }
            TextButton(onClick = { viewModel.clearKisCredentials() }, enabled = status.hasKis) { Text("키 삭제") }
        }
        DateField("KIS 서비스 만료일 (신청현황 화면의 만료일)", LocalDate.ofEpochDay(expiryDay), { expiryDay = it.toEpochDay() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "현재: " + (status.kisServiceExpiry?.let { formatDate(it) } ?: "입력 안 함"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.saveKisServiceExpiry(LocalDate.ofEpochDay(expiryDay)) }) { Text("만료일 저장") }
        }
        Text(
            "만료 30일 전부터 홈 화면에서 갱신을 안내합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KisLinkCard(
    status: ApiStatus,
    accounts: List<InvestmentAccount>,
    syncing: Boolean,
    viewModel: PortfolioViewModel,
) {
    var accountNo by rememberSaveable { mutableStateOf(status.linkedAccountNo.orEmpty()) }
    var product by rememberSaveable { mutableStateOf(status.linkedProduct) }
    var linkedId by rememberSaveable { mutableStateOf(status.linkedAccountId) }
    var startDay by rememberSaveable {
        mutableLongStateOf((status.syncStart ?: LocalDate.now().withDayOfYear(1)).toEpochDay())
    }
    LaunchedEffect(status.linkedAccountId) { if (linkedId == null) linkedId = status.linkedAccountId }

    SectionCard(title = "한투 연결 계좌 자동 불러오기") {
        Text(
            "KIS Developers 에 연결한 계좌의 체결 내역·잔고를 불러와, 고른 앱 계좌의 기록으로 저장합니다. " +
                "처음 불러올 때는 시작일 이전부터 갖고 있던 종목을 '초기 보유'로 채웁니다. " +
                "입금·출금(투자금)과 배당은 직접 입력하세요. 한투 예수금과의 차이는 계좌 화면에서 확인할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = accountNo,
            onValueChange = { accountNo = it.filter { c -> c.isDigit() }.take(8) },
            label = { Text("종합계좌번호 (앞 8자리)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = product,
            onValueChange = { product = it.filter { c -> c.isDigit() }.take(2) },
            label = { Text("상품코드 (주식 계좌는 보통 01)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (accounts.isEmpty()) {
            WarningText("먼저 계좌 탭에서 이 한투 계좌에 해당하는 앱 계좌를 만드세요.")
        } else {
            ChipSelector(
                "불러온 기록을 저장할 앱 계좌",
                accounts.map { it.id },
                linkedId,
                { id -> accounts.firstOrNull { it.id == id }?.name.orEmpty() },
                { id ->
                    linkedId = id
                    // 앱 계좌에 적어 둔 계좌번호가 있으면 앞 8자리를 채워 준다.
                    val digits = accounts.firstOrNull { it.id == id }?.number?.filter { it.isDigit() }.orEmpty()
                    if (accountNo.isBlank() && digits.length >= 8) {
                        accountNo = digits.take(8)
                        if (digits.length >= 10) product = digits.substring(8, 10)
                    }
                },
            )
        }
        DateField("불러오기 시작일", LocalDate.ofEpochDay(startDay), { startDay = it.toEpochDay() })
        TextButton(
            onClick = { viewModel.saveKisLink(accountNo, product, linkedId, LocalDate.ofEpochDay(startDay)) },
            enabled = accountNo.length == 8 && linkedId != null,
        ) { Text("연결 정보 저장") }
        LabeledValue("마지막 불러오기", status.lastSync?.let { formatDate(it) } ?: "아직 없음")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { viewModel.syncKisAccount() },
                enabled = !syncing && status.hasKis && status.linkedAccountId != null && !status.linkedAccountNo.isNullOrBlank(),
            ) { Text("지금 불러오기") }
            if (syncing) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun EximCard(status: ApiStatus, viewModel: PortfolioViewModel) {
    var key by rememberSaveable { mutableStateOf("") }
    SectionCard(title = "수출입은행 환율 API") {
        Text(
            "달러 자산의 원화 환산에 매매기준율을 자동으로 받습니다. 인증키는 이 기기에만 암호화해 저장됩니다. " +
                "인증키는 2년마다 재동의(이메일 안내)가 필요합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledValue("저장된 인증키", status.eximKeyHint ?: "없음")
        SecretField("인증키", key) { key = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                viewModel.saveEximKey(key)
                key = ""
            }, enabled = key.isNotBlank()) { Text("저장") }
            TextButton(onClick = { viewModel.testExim() }, enabled = status.hasExim) { Text("연결 테스트") }
            TextButton(onClick = { viewModel.clearEximKey() }, enabled = status.hasExim) { Text("키 삭제") }
        }
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

    SectionCard(title = "원/달러 환율 직접 입력") {
        Text(
            "환율 API 로 받은 환율이 없을 때만 이 값을 사용합니다.",
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

@Composable
private fun DeleteAccountCard(onDelete: suspend (Context) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(0) }
    var typed by rememberSaveable { mutableStateOf("") }
    var working by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    SectionCard(title = "계정·데이터 삭제") {
        Text(
            "클라우드에 저장된 모든 데이터(계좌·기록·목적통장)와 로그인 계정을 삭제하고, 이 기기의 API 키도 지웁니다. 되돌릴 수 없습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { step = 1 }, enabled = !working) { Text("내 데이터 전체 삭제 및 탈퇴") }
        if (working) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("  삭제 중...", style = MaterialTheme.typography.bodyMedium)
            }
        }
        error?.let { WarningText(it) }
    }

    when (step) {
        1 -> ConfirmDialog(
            title = "정말 탈퇴할까요?",
            text = "모든 데이터가 영구 삭제됩니다. 계속하면 확인 문구를 입력하고, Google 계정으로 한 번 더 인증합니다.",
            confirmLabel = "계속",
            onConfirm = { step = 2 },
            onDismiss = { if (step == 1) step = 0 },
        )
        2 -> AlertDialog(
            onDismissRequest = { step = 0 },
            title = { Text("확인 문구 입력") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("아래 칸에 삭제 라고 입력하세요.")
                    OutlinedTextField(value = typed, onValueChange = { typed = it.take(10) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        step = 0
                        typed = ""
                        working = true
                        error = null
                        scope.launch {
                            try {
                                onDelete(context)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "삭제하지 못했습니다: ${e.localizedMessage ?: "인터넷 연결을 확인하세요."}"
                            } finally {
                                working = false
                            }
                        }
                    },
                    enabled = typed.trim() == "삭제",
                ) { Text("영구 삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("취소") } },
        )
    }
}
