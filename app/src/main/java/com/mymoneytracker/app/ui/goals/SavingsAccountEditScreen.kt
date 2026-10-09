package com.mymoneytracker.app.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.SavingsAccount
import java.time.LocalDate
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsAccountEditScreen(
    viewModel: GoalsViewModel,
    accountId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val existing = accountId?.let { data?.account(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (accountId == null) "목적통장 추가" else "목적통장 편집") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        if (accountId != null && existing == null) {
            LoadingBox(Modifier.padding(padding))
        } else {
            SavingsAccountForm(
                initial = existing,
                modifier = Modifier.padding(padding),
                onSave = { onSaved(viewModel.saveAccount(it)) },
                onDelete = existing?.let { account ->
                    {
                        viewModel.deleteAccount(account.id)
                        onDeleted()
                    }
                },
            )
        }
    }
}

@Composable
private fun SavingsAccountForm(
    initial: SavingsAccount?,
    modifier: Modifier,
    onSave: (SavingsAccount) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var balanceText by rememberSaveable { mutableStateOf(initial?.balance?.roundToLong()?.toString().orEmpty()) }
    var rateText by rememberSaveable {
        mutableStateOf(initial?.annualRate?.let { MoneyFormat.decimal(it * 100, 2).replace(",", "") }.orEmpty())
    }
    var memo by rememberSaveable { mutableStateOf(initial?.memo.orEmpty()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val balance = if (balanceText.isBlank()) 0.0 else MoneyFormat.parseDecimal(balanceText)
    val ratePercent = if (rateText.isBlank()) 0.0 else MoneyFormat.parseDecimal(rateText)
    val valid = name.isNotBlank() && balance != null && balance >= 0 && ratePercent != null && ratePercent in 0.0..100.0

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextInput("통장 이름 (예: IT기기금, 지출)", name, { name = it }, maxLength = 40)
        NumberField("현재 잔액 (원)", balanceText, { balanceText = it }, allowDecimal = false, preview = { MoneyFormat.won(it) })
        NumberField(
            "기대수익률 (연 %, 예: 3.5)",
            rateText,
            { rateText = it },
            preview = { "연 ${MoneyFormat.decimal(it, 2)}% (CMA·채권 예상 수익률)" },
        )
        TextInput("메모 (선택)", memo, { memo = it }, singleLine = false, maxLength = 200)
        Button(
            onClick = {
                val newBalance = balance ?: 0.0
                onSave(
                    SavingsAccount(
                        id = initial?.id.orEmpty(),
                        name = name.trim(),
                        balance = newBalance,
                        balanceDate = if (initial == null || initial.balance != newBalance) LocalDate.now() else initial.balanceDate,
                        annualRate = (ratePercent ?: 0.0) / 100,
                        memo = memo.trim(),
                        createdAt = initial?.createdAt ?: 0L,
                    ),
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장") }
        if (onDelete != null) {
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("목적통장 삭제") }
        }
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "목적통장 삭제",
            text = "이 통장과 모든 목표가 삭제됩니다. 되돌릴 수 없습니다.",
            confirmLabel = "삭제",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}
