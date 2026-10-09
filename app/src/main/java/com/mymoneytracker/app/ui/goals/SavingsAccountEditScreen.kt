package com.mymoneytracker.app.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.common.AccountNumberField
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.SavingsAccount
import java.time.LocalDate

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
    val rows = rememberSaveable(saver = subAccountRowsSaver) {
        (initial?.subAccounts?.map(SubAccountRow::from)?.ifEmpty { null } ?: listOf(SubAccountRow(name = "CMA")))
            .toMutableStateList()
    }
    var rateText by rememberSaveable {
        mutableStateOf(initial?.annualRate?.let { MoneyFormat.decimal(it * 100, 2).replace(",", "") }.orEmpty())
    }
    var memo by rememberSaveable { mutableStateOf(initial?.memo.orEmpty()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val ratePercent = if (rateText.isBlank()) 0.0 else MoneyFormat.parseDecimal(rateText)
    val balancesValid = rows.all { it.balance.isBlank() || (MoneyFormat.parseDecimal(it.balance) ?: -1.0) >= 0 }
    val valid = name.isNotBlank() && rows.isNotEmpty() && balancesValid && ratePercent != null && ratePercent in 0.0..100.0
    val total = rows.sumOf { MoneyFormat.parseDecimal(it.balance) ?: 0.0 }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextInput("통장 이름 (예: IT기기금, 지출)", name, { name = it }, maxLength = 40)

        Text("계좌", style = MaterialTheme.typography.titleMedium)
        Text(
            "이 목적통장의 돈이 들어 있는 계좌들입니다 (예: CMA, 채권 계좌). 잔액은 합계로 계산합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEachIndexed { index, row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("계좌 ${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (rows.size > 1) {
                            IconButton(onClick = { rows.removeAt(index) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "계좌 삭제")
                            }
                        }
                    }
                    TextInput("계좌 이름 (예: CMA, 채권)", row.name, { rows[index] = row.copy(name = it) }, maxLength = 30)
                    AccountNumberField(row.number) { rows[index] = row.copy(number = it) }
                    NumberField(
                        "잔액 (원)",
                        row.balance,
                        { rows[index] = row.copy(balance = it) },
                        allowDecimal = false,
                        preview = { MoneyFormat.won(it) },
                    )
                }
            }
        }
        if (rows.size < 20) {
            OutlinedButton(onClick = { rows.add(SubAccountRow()) }) { Text("계좌 추가") }
        }
        LabeledValue("잔액 합계", MoneyFormat.won(total), bold = true)

        NumberField(
            "기대수익률 (연 %, 예: 3.5)",
            rateText,
            { rateText = it },
            preview = { "연 ${MoneyFormat.decimal(it, 2)}% (CMA·채권 예상 수익률)" },
        )
        TextInput("메모 (선택)", memo, { memo = it }, singleLine = false, maxLength = 200)
        Button(
            onClick = {
                val subAccounts = rows.mapIndexed { i, row -> row.toSubAccount(i) }
                val balanceChanged = initial == null ||
                    initial.subAccounts.map { it.balance } != subAccounts.map { it.balance }
                onSave(
                    SavingsAccount(
                        id = initial?.id.orEmpty(),
                        name = name.trim(),
                        subAccounts = subAccounts,
                        balanceDate = if (balanceChanged) LocalDate.now() else initial?.balanceDate,
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
