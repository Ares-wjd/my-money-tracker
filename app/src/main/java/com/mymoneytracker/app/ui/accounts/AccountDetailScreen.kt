package com.mymoneytracker.app.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.ApiStatus
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.ListRow
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.StatTile
import com.mymoneytracker.app.ui.common.TickerBadge
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

/** 기록 추가 메뉴에 보여줄 유형 (예수금은 예수금 카드의 "예수금 입력" 으로 따로 넣는다). */
private val addableTypes = listOf(
    RecordType.DEPOSIT,
    RecordType.WITHDRAW,
    RecordType.TRANSFER,
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
    onEnterCash: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val apiStatus by viewModel.apiStatus.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val refresh by viewModel.refresh.collectAsStateWithLifecycle()
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
                    if (refresh.running) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { viewModel.refreshQuotes() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "현재가 새로고침")
                        }
                    }
                    IconButton(onClick = onEditAccount) { Icon(Icons.Filled.Edit, contentDescription = "계좌 편집") }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { menuOpen = true },
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
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
                lastRefreshedAt = refresh.lastRefreshedAt,
                syncing = syncing,
                onSync = { viewModel.syncKisAccount() },
                onOpenSettings = onOpenSettings,
                onAddHolding = onAddHolding,
                onOpenHolding = onOpenHolding,
                onOpenRecord = onOpenRecord,
                onEnterCash = onEnterCash,
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
    lastRefreshedAt: Long?,
    syncing: Boolean,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddHolding: () -> Unit,
    onOpenHolding: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
    onEnterCash: () -> Unit,
) {
    val accountId = summary.account.id
    val records = data.recordsOf(accountId)
    var visibleRecords by rememberSaveable(accountId) { mutableStateOf(30) }
    val holdings = summary.holdings.sortedByDescending { it.marketValueKrw ?: 0.0 }

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                listOf(
                    accountDescription(summary.account.kind.label, summary.account.number),
                    summary.account.memo,
                    lastRefreshedAt?.let { "현재가 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(it)) + " 기준 · 화면을 보는 동안 1분마다 새로고침" }.orEmpty(),
                ).filter { it.isNotBlank() }.joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SectionCard {
                Text("평가금", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(MoneyFormat.won(summary.valueKrw), style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${MoneyFormat.signedWon(summary.profitKrw)} · ${MoneyFormat.percent(summary.returnRate)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = profitColor(summary.profitKrw),
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("투자금", MoneyFormat.won(summary.investedKrw), Modifier.weight(1f))
                    StatTile(
                        "배당 미포함",
                        MoneyFormat.percent(summary.returnRateExDividends),
                        Modifier.weight(1f),
                        valueColor = profitColor(summary.returnRateExDividends),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("누적 배당", MoneyFormat.won(summary.dividendsKrw), Modifier.weight(1f))
                    StatTile(
                        "수익금 (배당 미포함)",
                        MoneyFormat.signedWon(summary.profitExDividendsKrw),
                        Modifier.weight(1f),
                        valueColor = profitColor(summary.profitExDividendsKrw),
                    )
                }
                if (summary.missingFx) WarningText("환율이 없어 달러 금액이 원화 합계에서 빠져 있습니다. 설정에서 환율을 입력하세요.")
                if (summary.missingPrice) WarningText("현재가가 없는 종목이 있습니다. 종목을 눌러 가격을 입력하세요.")
            }
        }

        if (linked) {
            item {
                LinkedAccountCard(
                    apiStatus = apiStatus,
                    syncing = syncing,
                    onSync = onSync,
                    onOpenSettings = onOpenSettings,
                )
            }
        }

        item {
            SectionCard(title = "예수금") {
                val cashDate = summary.cashDate
                LabeledValue(if (linked) "원화 (D+2)" else "원화", MoneyFormat.won(summary.cash.krw))
                if (summary.cash.usd != 0.0 || summary.holdings.any { it.holding.currency == Currency.USD }) {
                    LabeledValue("달러", MoneyFormat.usd(summary.cash.usd))
                }
                Text(
                    when {
                        linked -> "한투에서 불러온 값 (새로고침할 때마다 갱신)" + (cashDate?.let { " · ${formatDate(it)} 변경" } ?: "")
                        cashDate != null -> "${formatDate(cashDate)} 입력한 값"
                        else -> "아직 입력하지 않았습니다. 증권사 앱의 예수금을 입력하세요."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!linked) TextButton(onClick = onEnterCash) { Text("예수금 입력") }
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
        if (holdings.isNotEmpty()) {
            item {
                SectionCard {
                    holdings.forEachIndexed { index, valuation ->
                        val holding = valuation.holding
                        val position = valuation.position
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ListRow(
                            title = holding.name,
                            subtitle = "${holding.assetType.label} · ${MoneyFormat.decimal(position.quantity)}주",
                            value = valuation.marketValue?.let { MoneyFormat.amount(holding.currency, it) } ?: "가격 입력 필요",
                            subValue = valuation.returnRate?.let { MoneyFormat.percent(it) },
                            subValueColor = profitColor(valuation.returnRate),
                            onClick = { onOpenHolding(holding.id) },
                            leading = { TickerBadge(holding.code, holding.name) },
                        )
                    }
                }
            }
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
        if (records.isNotEmpty()) {
            item {
                SectionCard {
                    records.take(visibleRecords).forEachIndexed { index, record ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ListRow(
                            title = recordTitle(record, data, accountId),
                            subtitle = listOfNotNull(formatDate(record.date), recordDetailText(record, data)).joinToString(" · "),
                            value = recordAmountText(record, data, accountId),
                            onClick = { onOpenRecord(record.id) },
                        )
                    }
                    if (records.size > visibleRecords) {
                        TextButton(onClick = { visibleRecords += 30 }) { Text("기록 더 보기 (${records.size - visibleRecords}건 남음)") }
                    }
                }
            }
        }
    }
}

/** 한투 연결 계좌: 매매와 예수금은 불러오고, 입출금(투자금)과 배당은 직접 입력한다. */
@Composable
private fun LinkedAccountCard(
    apiStatus: ApiStatus,
    syncing: Boolean,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    SectionCard(title = "한투 연결 계좌") {
        Text(
            "매수·매도 체결과 예수금은 한투에서 불러옵니다. 입금·출금(투자금)과 배당은 직접 기록하세요." +
                (apiStatus.lastSync?.let { "\n마지막 불러오기 ${formatDate(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onSync, enabled = !syncing) { Text("한투에서 불러오기") }
            if (syncing) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
            TextButton(onClick = onOpenSettings) { Text("연결 설정") }
        }
    }
}
