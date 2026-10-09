package com.mymoneytracker.app.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.ApiStatus
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.ListRow
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.app.ui.common.accountDescription
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.app.ui.records.recordAmountText
import com.mymoneytracker.app.ui.records.recordDetailText
import com.mymoneytracker.app.ui.records.recordTitle
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.AccountSummary

/** 기록 추가 메뉴에 보여줄 유형 (예수금 조정은 "예수금 수정" 버튼으로 따로 만든다). */
private val addableTypes = listOf(
    RecordType.DEPOSIT,
    RecordType.WITHDRAW,
    RecordType.TRANSFER,
    RecordType.EXCHANGE,
    RecordType.BUY,
    RecordType.SELL,
    RecordType.DIVIDEND,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    viewModel: PortfolioViewModel,
    accountId: String,
    onBack: () -> Unit,
    onEditAccount: () -> Unit,
    onAddHolding: () -> Unit,
    onOpenHolding: (String) -> Unit,
    onAddRecord: (RecordType) -> Unit,
    onOpenRecord: (String) -> Unit,
    onAdjustCash: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val apiStatus by viewModel.apiStatus.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val current = data
    val summary = current?.accountSummary(accountId)
    var menuOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(summary?.account?.name ?: "계좌") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
                actions = {
                    IconButton(onClick = onEditAccount) { Icon(Icons.Filled.Edit, contentDescription = "계좌 편집") }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { menuOpen = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("기록 추가") },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // 한투 연결 계좌는 매수·매도를 불러오므로 직접 추가하지 않는다 (중복 방지).
                    val linkedHere = apiStatus.linkedAccountId == accountId
                    addableTypes.filter { !linkedHere || (it != RecordType.BUY && it != RecordType.SELL) }.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.label) },
                            onClick = {
                                menuOpen = false
                                onAddRecord(type)
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (current == null || summary == null) {
            LoadingBox(Modifier.padding(padding))
        } else {
            AccountDetailContent(
                data = current,
                summary = summary,
                padding = padding,
                linked = apiStatus.linkedAccountId == accountId,
                apiStatus = apiStatus,
                syncing = syncing,
                onSync = { viewModel.syncKisAccount() },
                onRecordCashDifference = { currency, diff -> viewModel.recordCashDifference(accountId, currency, diff) },
                onOpenSettings = onOpenSettings,
                onAddHolding = onAddHolding,
                onOpenHolding = onOpenHolding,
                onOpenRecord = onOpenRecord,
                onAdjustCash = onAdjustCash,
            )
        }
    }
}

@Composable
private fun AccountDetailContent(
    data: PortfolioData,
    summary: AccountSummary,
    padding: PaddingValues,
    linked: Boolean,
    apiStatus: ApiStatus,
    syncing: Boolean,
    onSync: () -> Unit,
    onRecordCashDifference: (Currency, Double) -> Unit,
    onOpenSettings: () -> Unit,
    onAddHolding: () -> Unit,
    onOpenHolding: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
    onAdjustCash: () -> Unit,
) {
    val accountId = summary.account.id
    val records = data.recordsOf(accountId)
    val holdings = summary.holdings.sortedByDescending { it.marketValueKrw ?: 0.0 }

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                listOf(accountDescription(summary.account.kind.label, summary.account.number), summary.account.memo)
                    .filter { it.isNotBlank() }.joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SectionCard(highlighted = true) {
                Text("평가금", style = MaterialTheme.typography.labelLarge)
                Text(
                    MoneyFormat.won(summary.valueKrw),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                LabeledValue("투자금 (입금 − 출금)", MoneyFormat.won(summary.investedKrw))
                LabeledValue(
                    "수익금 (배당 포함)",
                    "${MoneyFormat.signedWon(summary.profitKrw)} (${MoneyFormat.percent(summary.returnRate)})",
                    valueColor = profitColor(summary.profitKrw),
                )
                LabeledValue(
                    "수익금 (배당 미포함)",
                    "${MoneyFormat.signedWon(summary.profitExDividendsKrw)} (${MoneyFormat.percent(summary.returnRateExDividends)})",
                    valueColor = profitColor(summary.profitExDividendsKrw),
                )
                LabeledValue("누적 배당", MoneyFormat.won(summary.dividendsKrw))
                if (summary.missingFx) WarningText("환율이 없어 달러 금액이 원화 합계에서 빠져 있습니다. 설정에서 환율을 입력하세요.")
                if (summary.missingPrice) WarningText("현재가가 없는 종목이 있습니다. 종목을 눌러 가격을 입력하세요.")
            }
        }

        if (linked) {
            item {
                LinkedAccountCard(
                    summary = summary,
                    apiStatus = apiStatus,
                    syncing = syncing,
                    onSync = onSync,
                    onOpenSettings = onOpenSettings,
                    onRecordCashDifference = onRecordCashDifference,
                )
            }
        }

        item {
            SectionCard(title = "예수금") {
                LabeledValue("원화", MoneyFormat.won(summary.cash.krw))
                if (summary.cash.usd != 0.0 || summary.holdings.any { it.holding.currency == Currency.USD }) {
                    LabeledValue("달러", MoneyFormat.usd(summary.cash.usd))
                }
                TextButton(onClick = onAdjustCash) { Text("예수금 수정 (실제 금액과 맞추기)") }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("보유 종목", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onAddHolding) { Text("종목 추가") }
            }
        }
        if (holdings.isEmpty()) {
            item {
                Text(
                    "종목이 없습니다. 종목을 추가한 뒤 매수 기록을 남기세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(holdings, key = { "h-" + it.holding.id }) { valuation ->
            val holding = valuation.holding
            val position = valuation.position
            ListRow(
                title = holding.name,
                subtitle = "${holding.assetType.label} · ${MoneyFormat.decimal(position.quantity)}주" +
                    if (holding.code.isNotBlank()) " · ${holding.code}" else "",
                value = valuation.marketValue?.let { MoneyFormat.amount(holding.currency, it) } ?: "가격 입력 필요",
                subValue = valuation.returnRate?.let { MoneyFormat.percent(it) },
                subValueColor = profitColor(valuation.returnRate),
                onClick = { onOpenHolding(holding.id) },
            )
            HorizontalDivider()
        }

        item {
            Text("기록", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        }
        if (records.isEmpty()) {
            item {
                Text(
                    "기록이 없습니다. 기록 추가에서 입금부터 남겨 보세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(records, key = { "r-" + it.id }) { record ->
            ListRow(
                title = recordTitle(record, data, accountId),
                subtitle = listOfNotNull(formatDate(record.date), recordDetailText(record, data)).joinToString(" · "),
                value = recordAmountText(record, data, accountId),
                onClick = { onOpenRecord(record.id) },
            )
            HorizontalDivider()
        }
    }
}

/** 한투 연결 계좌: 매매는 불러오고, 입출금은 직접 입력. 한투 예수금과 앱 계산 예수금의 차이를 보여준다. */
@Composable
private fun LinkedAccountCard(
    summary: AccountSummary,
    apiStatus: ApiStatus,
    syncing: Boolean,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
    onRecordCashDifference: (Currency, Double) -> Unit,
) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val differences = listOfNotNull(
        apiStatus.brokerCashKrw?.let { Triple(Currency.KRW, it, it - summary.cash.krw) }?.takeIf { kotlin.math.abs(it.third) >= 1 },
        apiStatus.brokerCashUsd?.let { Triple(Currency.USD, it, it - summary.cash.usd) }?.takeIf { kotlin.math.abs(it.third) >= 0.01 },
    )

    SectionCard(title = "한투 연결 계좌") {
        Text(
            "매수·매도 체결은 한투에서 불러옵니다. 입금·출금(투자금)과 배당은 직접 기록하세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onSync, enabled = !syncing) { Text("한투에서 불러오기") }
            if (syncing) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
            TextButton(onClick = onOpenSettings) { Text("연결 설정") }
        }
        apiStatus.brokerCashDate?.let { date ->
            Text(
                "한투 예수금 (${formatDate(date)} 불러온 값)",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            apiStatus.brokerCashKrw?.let { LabeledValue("원화 (D+2)", MoneyFormat.won(it)) }
            apiStatus.brokerCashUsd?.let { LabeledValue("달러", MoneyFormat.usd(it)) }
            if (differences.isEmpty()) {
                Text("앱 계산 예수금과 같습니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                differences.forEach { (currency, _, diff) ->
                    WarningText("앱 계산 예수금과 ${MoneyFormat.signedAmount(currency, diff)} 차이가 있습니다.")
                }
                Text(
                    "기록하지 않은 입금·출금 때문이면 기록 추가에서 입금·출금을 남기세요. " +
                        "예탁금 이용료·수수료·세금 때문이면 아래 버튼으로 예수금 조정을 남기세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                differences.forEach { (currency, _, diff) ->
                    TextButton(onClick = { confirm = currency.name }) {
                        Text("${currency.label} 차이 ${MoneyFormat.signedAmount(currency, diff)} 를 예수금 조정으로 기록")
                    }
                }
            }
        }
    }

    differences.firstOrNull { it.first.name == confirm }?.let { (currency, _, diff) ->
        ConfirmDialog(
            title = "예수금 조정",
            text = "${MoneyFormat.signedAmount(currency, diff)} 를 예수금 조정으로 기록합니다. 이 금액은 투자금이 아니라 수익으로 계산됩니다. " +
                "입금·출금 때문에 생긴 차이라면 취소하고 입금·출금을 기록하세요.",
            confirmLabel = "기록",
            onConfirm = { onRecordCashDifference(currency, diff) },
            onDismiss = { confirm = null },
        )
    }
}
