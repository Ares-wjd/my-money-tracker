package com.mymoneytracker.app.ui.accounts

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
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.core.model.InvestmentAccount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditScreen(
    viewModel: PortfolioViewModel,
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
                title = { Text(if (accountId == null) "계좌 추가" else "계좌 편집") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        if (accountId != null && existing == null) {
            LoadingBox(Modifier.padding(padding))
        } else {
            AccountForm(
                initial = existing,
                modifier = Modifier.padding(padding),
                onSave = { account -> onSaved(viewModel.saveAccount(account)) },
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
private fun AccountForm(
    initial: InvestmentAccount?,
    modifier: Modifier,
    onSave: (InvestmentAccount) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var memo by rememberSaveable { mutableStateOf(initial?.memo.orEmpty()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextInput("계좌 이름 (예: 한투 국내, 연금저축)", name, { name = it }, maxLength = 40)
        TextInput("메모 (선택)", memo, { memo = it }, singleLine = false, maxLength = 200)
        Button(
            onClick = {
                onSave(
                    InvestmentAccount(
                        id = initial?.id.orEmpty(),
                        name = name.trim(),
                        memo = memo.trim(),
                        createdAt = initial?.createdAt ?: 0L,
                    ),
                )
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장") }
        if (onDelete != null) {
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("계좌 삭제") }
        }
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "계좌 삭제",
            text = "이 계좌의 종목과 모든 기록(이 계좌와 주고받은 이체 포함)이 함께 삭제됩니다. 되돌릴 수 없습니다.",
            confirmLabel = "삭제",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}
