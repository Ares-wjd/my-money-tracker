package com.mymoneytracker.app.ui.allocation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.DeleteButton
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.portfolio.AllocationCalculator
import com.mymoneytracker.core.portfolio.AllocationTarget
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllocationEditScreen(
    viewModel: PortfolioViewModel,
    targetId: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val targets by viewModel.allocationTargets.collectAsStateWithLifecycle()
    val existing = targetId?.let { id -> targets?.firstOrNull { it.id == id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle(if (targetId == null) "비중 설정 추가" else "비중 설정 편집") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } },
            )
        },
    ) { padding ->
        val current = data
        if (current == null || targets == null || (targetId != null && existing == null)) {
            LoadingBox(Modifier.padding(padding))
        } else {
            AllocationForm(
                initial = existing,
                accounts = current.accounts,
                holdings = current.holdings,
                modifier = Modifier.padding(padding),
                onSave = {
                    viewModel.selectAllocation(viewModel.saveAllocationTarget(it))
                    onDone()
                },
                onDelete = existing?.let { t ->
                    {
                        viewModel.deleteAllocationTarget(t.id)
                        onDone()
                    }
                },
            )
        }
    }
}

@Composable
private fun AllocationForm(
    initial: AllocationTarget?,
    accounts: List<InvestmentAccount>,
    holdings: List<Holding>,
    modifier: Modifier,
    onSave: (AllocationTarget) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    val accountIds = remember {
        mutableStateListOf<String>().apply { addAll(initial?.accountIds ?: if (accounts.size == 1) listOf(accounts.first().id) else emptyList()) }
    }
    var includeCash by rememberSaveable { mutableStateOf(initial?.includeCash ?: false) }
    var thresholdText by rememberSaveable { mutableStateOf(initial?.threshold?.let { MoneyFormat.decimal(it, 2).replace(",", "") } ?: "3") }
    val texts = remember {
        mutableStateMapOf<String, String>().apply {
            initial?.targets?.forEach { (k, v) -> put(k, MoneyFormat.decimal(v, 2).replace(",", "")) }
        }
    }
    val excluded = remember { mutableStateListOf<String>().apply { addAll(initial?.excluded.orEmpty()) } }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // 대상 계좌의 종목 (같은 종목은 하나로, 0주 포함 — 아직 안 산 종목도 목표에 넣을 수 있게)
    val items = linkedMapOf<String, String>()
    holdings.filter { it.accountId in accountIds }.forEach { h -> items.putIfAbsent(AllocationCalculator.keyOf(h), h.name) }
    // 이미 목표가 있는데 대상 계좌에 없는 종목(지웠거나 계좌를 뺐을 때)도 남겨 둔다
    initial?.targets?.keys?.filter { it != AllocationCalculator.CASH && it !in items }?.forEach { items[it] = it.substringAfter(':') }
    val keys = items.keys.toList() + if (includeCash) listOf(AllocationCalculator.CASH) else emptyList()
    // 제외한 종목은 목표 % 를 받지 않고 계산에서 뺀다
    fun percent(k: String) = if (k in excluded) 0.0 else MoneyFormat.parseDecimal(texts[k].orEmpty()) ?: 0.0
    val sum = keys.sumOf { percent(it) }
    val threshold = MoneyFormat.parseDecimal(thresholdText)
    val invalid = keys.any { k -> k !in excluded && texts[k].orEmpty().isNotBlank() && (MoneyFormat.parseDecimal(texts[k].orEmpty()) == null || percent(k) !in 0.0..100.0) }
    val problem = when {
        name.isBlank() -> "이름을 입력하세요."
        accountIds.isEmpty() -> "대상 계좌를 고르세요."
        invalid -> "목표 비중은 0~100% 로 입력하세요."
        abs(sum - 100) > 0.01 -> "목표 합계가 100% 가 되어야 합니다 (지금 ${MoneyFormat.decimal(sum, 2)}%)."
        threshold == null || threshold !in 0.0..100.0 -> "강조 기준은 0~100%p 로 입력하세요."
        else -> null
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextInput("이름 (예: ISA 자산배분, 미국 개별주)", name, { name = it }, maxLength = 40)
        Text("대상 계좌 (여러 개면 같은 종목을 합쳐서 계산)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        accounts.forEach { a ->
            Row(
                Modifier.fillMaxWidth().clickable { if (a.id in accountIds) accountIds.remove(a.id) else accountIds.add(a.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = a.id in accountIds, onCheckedChange = { if (it) accountIds.add(a.id) else accountIds.remove(a.id) })
                Text(a.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("예수금도 비중에 넣기", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = includeCash, onCheckedChange = { includeCash = it })
        }
        NumberField(
            "차이 강조 기준 (%p)",
            thresholdText,
            { thresholdText = it },
            preview = { "목표와 ±${MoneyFormat.decimal(it, 2)}%p 이상 차이 나면 강조" },
        )
        Text("목표 비중 (%)", style = MaterialTheme.typography.titleMedium)
        Text(
            "아직 안 산 종목은 계좌에서 종목을 먼저 추가하면 여기에 나옵니다 (0주여도 됩니다). 비중에서 뺄 종목은 [제외]를 고르세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        keys.forEach { k ->
            val off = k in excluded
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (k == AllocationCalculator.CASH) "예수금" else items[k].orEmpty()) + if (off) " (제외)" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    if (k != AllocationCalculator.CASH && !k.startsWith("id:")) {
                        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!off) {
                    OutlinedTextField(
                        value = texts[k].orEmpty(),
                        onValueChange = { v -> texts[k] = v.filter { it.isDigit() || it == '.' }.take(6) },
                        placeholder = { Text("0") },
                        suffix = { Text("%") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(100.dp),
                    )
                }
                if (k != AllocationCalculator.CASH) {
                    Row(
                        Modifier.clickable { if (off) excluded.remove(k) else excluded.add(k) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = off, onCheckedChange = { if (it) excluded.add(k) else excluded.remove(k) })
                        Text("제외", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if (keys.isNotEmpty()) {
            val left = 100 - sum
            Text(
                "합계 ${MoneyFormat.decimal(sum, 2)}%" + when {
                    abs(left) <= 0.01 -> " ✓"
                    left > 0 -> " · ${MoneyFormat.decimal(left, 2)}% 남음"
                    else -> " · ${MoneyFormat.decimal(-left, 2)}% 넘음"
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (abs(left) <= 0.01) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
        problem?.let { WarningText(it) }
        Button(
            onClick = {
                onSave(
                    AllocationTarget(
                        id = initial?.id.orEmpty(),
                        name = name.trim(),
                        accountIds = accountIds.toList(),
                        targets = keys.filter { percent(it) > 0 }.associateWith { percent(it) },
                        includeCash = includeCash,
                        threshold = threshold ?: 3.0,
                        createdAt = initial?.createdAt ?: 0L,
                        // 지금 대상 계좌에 있는 종목만 제외 목록에 남긴다
                        excluded = excluded.filter { it in items },
                    ),
                )
            },
            enabled = problem == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장") }
        if (onDelete != null) DeleteButton("비중 설정 삭제", onClick = { confirmDelete = true })
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "비중 설정 삭제",
            text = "이 비중 설정을 삭제할까요? 종목·기록은 그대로입니다.",
            confirmLabel = "삭제",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}
