package com.mymoneytracker.app.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.mymoneytracker.app.ui.common.fabContainerColor
import com.mymoneytracker.app.ui.common.fabContentColor
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.core.portfolio.HoldingValuation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.mymoneytracker.app.ui.common.AppFilterChip
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.ListRow
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.SegmentedPills
import com.mymoneytracker.app.ui.common.StatTile
import com.mymoneytracker.app.ui.common.TickerBadge
import com.mymoneytracker.app.ui.common.TickerLegend
import com.mymoneytracker.app.ui.common.tickerGroup
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
import com.mymoneytracker.core.portfolio.CashDetail
import com.mymoneytracker.core.portfolio.DisplayConversion
import com.mymoneytracker.core.portfolio.DisplayCurrency
import com.mymoneytracker.core.portfolio.HoldingSort
import com.mymoneytracker.core.portfolio.HoldingSorter
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.portfolio.RecordFilter

/** 기록 추가 메뉴에 보여줄 유형 (예수금은 예수금 카드의 "예수금 입력" 으로 따로 넣는다). */
private val addableTypes = listOf(
    RecordType.DEPOSIT,
    RecordType.WITHDRAW,
    RecordType.TRANSFER,
    RecordType.BUY,
    RecordType.SELL,
    RecordType.DIVIDEND,
    RecordType.EXCHANGE,
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
    onImportTrades: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val apiStatus by viewModel.apiStatus.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val refresh by viewModel.refresh.collectAsStateWithLifecycle()
    val displayCurrency by viewModel.displayCurrency.collectAsStateWithLifecycle()
    val holdingSort by viewModel.holdingSort.collectAsStateWithLifecycle()
    val recordHidden by viewModel.recordHidden.collectAsStateWithLifecycle()
    val current = data
    val summary = current?.accountSummary(accountId)
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 스크롤을 내리면 기록 추가 버튼을 작게 (목록을 덜 가리도록)
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle(summary?.account?.name ?: "계좌") },
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
                    expanded = fabExpanded,
                    containerColor = fabContainerColor(),
                    contentColor = fabContentColor(),
                    icon = { Icon(Icons.Filled.Add, contentDescription = "기록 추가") },
                    text = { Text("기록 추가") },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // 한투 연결 계좌는 매수·매도를 불러오므로 직접 추가하지 않는다 (중복 방지).
                    val linkedHere = apiStatus.linkedAccountId == accountId
                    // 환전은 직접 입력 계좌의 예수금 계산용 (연결 계좌는 예수금을 불러온다)
                    addableTypes.filter { !linkedHere || (it != RecordType.BUY && it != RecordType.SELL && it != RecordType.EXCHANGE) }.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.label) },
                            onClick = {
                                menuOpen = false
                                onAddRecord(type)
                            },
                        )
                    }
                    // 표로 일괄 입력 (입출금·매매·배당 — 연결 계좌는 매매 표를 빼고 보여준다)
                    DropdownMenuItem(
                        text = { Text("표로 일괄 입력") },
                        onClick = {
                            menuOpen = false
                            onImportTrades()
                        },
                    )
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
                displayCurrency = displayCurrency,
                holdingSort = holdingSort,
                onSelectHoldingSort = viewModel::setHoldingSort,
                onSelectDisplayCurrency = viewModel::setDisplayCurrency,
                syncing = syncing,
                onSync = { viewModel.syncKisAccount() },
                onOpenSettings = onOpenSettings,
                onAddHolding = onAddHolding,
                onOpenHolding = onOpenHolding,
                onOpenRecord = onOpenRecord,
                onEnterCash = onEnterCash,
                onAddRecord = onAddRecord,
                hiddenTypes = recordHidden[accountId].orEmpty(),
                onHiddenTypesChange = { viewModel.setRecordHidden(accountId, it) },
                listState = listState,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountDetailContent(
    data: PortfolioData,
    summary: AccountSummary,
    padding: PaddingValues,
    linked: Boolean,
    apiStatus: ApiStatus,
    lastRefreshedAt: Long?,
    displayCurrency: DisplayCurrency,
    onSelectDisplayCurrency: (DisplayCurrency) -> Unit,
    holdingSort: HoldingSort,
    onSelectHoldingSort: (HoldingSort) -> Unit,
    syncing: Boolean,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddHolding: () -> Unit,
    onOpenHolding: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
    onEnterCash: () -> Unit,
    onAddRecord: (RecordType) -> Unit,
    hiddenTypes: Set<RecordType>,
    onHiddenTypesChange: (Set<RecordType>) -> Unit,
    listState: LazyListState,
) {
    val accountId = summary.account.id
    val allRecords = data.recordsOf(accountId)
    // 입금·출금·이체 기록마다 그 기록까지의 누적 투자금 (걸러 봐도 전체 기록 기준)
    val cumulativeInvested = remember(allRecords, accountId) { PortfolioCalculator.cumulativeInvested(accountId, allRecords) }
    // 기록 유형 필터 (기본은 전부 보임)
    val presentTypes = remember(allRecords) { RecordFilter.present(allRecords) }
    val records = RecordFilter.apply(allRecords, hiddenTypes)
    val filtered = records.size != allRecords.size
    var visibleRecords by rememberSaveable(accountId) { mutableStateOf(30) }
    // 총 수익률은 보기를 따른다 (원화 보기: 매매일 환율이 모두 있으면 환차익 반영 원화 수익률)
    fun totalRate(v: com.mymoneytracker.core.portfolio.HoldingValuation): Double? =
        (if (displayCurrency == DisplayCurrency.KRW) data.krwView(v)?.returnRate else null) ?: v.returnRate
    val sorted = HoldingSorter.sort(summary.holdings, holdingSort, ::totalRate) { data.todayRate(it) }
    // 0주 종목(다 판 종목)은 맨 아래 "매도 완료 종목" 안에 접어 둔다
    val holdings = sorted.filter { it.position.quantity > 0 }
    val soldOut = sorted.filter { it.position.quantity <= 0 }
    var showSoldOut by rememberSaveable(accountId) { mutableStateOf(false) }
    var showLegend by rememberSaveable { mutableStateOf(false) }
    val hasUsd = sorted.any { it.holding.currency == Currency.USD }

    LazyColumn(
        state = listState,
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
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
                // 핵심 3개(투자금·수익금·수익률)를 크게, 나머지는 아래 한 줄로
                Text("평가금", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(MoneyFormat.won(summary.valueKrw), style = MaterialTheme.typography.headlineMedium)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("투자금", MoneyFormat.won(summary.investedKrw), Modifier.weight(1f), shortValue = MoneyFormat.shortWon(summary.investedKrw))
                    StatTile(
                        "수익금",
                        MoneyFormat.signedWon(summary.profitKrw),
                        Modifier.weight(1f),
                        valueColor = profitColor(summary.profitKrw),
                        shortValue = (if (summary.profitKrw > 0) "+" else "") + MoneyFormat.shortWon(summary.profitKrw),
                    )
                    StatTile("수익률", MoneyFormat.rate(summary.returnRate), Modifier.weight(0.8f), valueColor = profitColor(summary.returnRate))
                }
                if (summary.returnRate == null) {
                    // 투자금이 0원 이하면 수익률을 계산할 수 없다 → 이유 (입금 기록이 없을 때만 바로가기)
                    val reason = PortfolioCalculator.noRateReason(accountId, summary.investedKrw, allRecords)
                    Text(
                        "${noRateText(reason)} · 누적 배당 ${MoneyFormat.won(summary.dividendsKrw)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (reason == PortfolioCalculator.NoRateReason.NO_DEPOSIT) {
                        TextButton(onClick = { onAddRecord(RecordType.DEPOSIT) }, contentPadding = PaddingValues(0.dp)) { Text("입금 기록하기") }
                    }
                } else {
                    Text(
                        "배당 미포함 ${MoneyFormat.rate(summary.returnRateExDividends)} · 누적 배당 ${MoneyFormat.won(summary.dividendsKrw)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
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
                val broker = linked || summary.brokerLinked
                if (broker) {
                    Text(
                        "한투에서 불러온 값 (새로고침할 때마다 갱신, 하루 한 번은 기록)" + (cashDate?.let { " · ${formatDate(it)} 기록" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 혼합 방식: 입력값(기준점) + 이후 기록. 통화마다 근거를 보여준다.
                    val showUsd = summary.cash.usd != 0.0 || summary.holdings.any { it.holding.currency == Currency.USD }
                    listOfNotNull(Currency.KRW, Currency.USD.takeIf { showUsd }).forEach { currency ->
                        Text(
                            cashBasis(summary.cashDetails[currency], currency, showUsd),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (summary.cash.krw < 0 || summary.cash.usd < 0) {
                        WarningText("계산상 예수금이 음수입니다. 입금 기록이나 예수금 입력을 확인하세요.")
                    }
                }
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
        if (sorted.isNotEmpty()) {
            // 정렬 + 원화/외화를 한 줄로, 티커 색 범례는 ⓘ 를 눌러 본다
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SegmentedPills(
                        HoldingSort.entries,
                        holdingSort,
                        { sortShortLabel(it) },
                        onSelectHoldingSort,
                        Modifier.weight(1f),
                    )
                    if (hasUsd) {
                        SegmentedPills(
                            options = listOf(DisplayCurrency.FOREIGN, DisplayCurrency.KRW),
                            selected = displayCurrency,
                            label = { if (it == DisplayCurrency.FOREIGN) "외화" else "원화" },
                            onSelect = onSelectDisplayCurrency,
                            modifier = Modifier.width(96.dp),
                        )
                    }
                    IconButton(onClick = { showLegend = !showLegend }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Info, contentDescription = if (showLegend) "티커 색 설명 닫기" else "티커 색 설명")
                    }
                }
            }
            if (hasUsd && displayCurrency == DisplayCurrency.KRW) {
                item { CurrencyCaption(summary.usdKrw) }
            }
            if (showLegend) {
                item { TickerLegend(sorted.map { tickerGroup(it.holding.market, it.holding.assetType) }.toSet()) }
            }
        }
        if (sorted.isEmpty()) {
            item {
                Text(
                    "종목이 없습니다. 종목을 추가한 뒤 매수 기록을 남기세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (sorted.isNotEmpty()) {
            item {
                SectionCard {
                    holdings.forEachIndexed { index, valuation ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        HoldingRow(valuation, data, summary, displayCurrency, totalRate(valuation)) { onOpenHolding(valuation.holding.id) }
                    }
                    if (soldOut.isNotEmpty()) {
                        if (holdings.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TextButton(onClick = { showSoldOut = !showSoldOut }) {
                            Text("매도 완료 종목 (${soldOut.size}) " + if (showSoldOut) "▴" else "▾")
                        }
                        if (showSoldOut) {
                            soldOut.forEach { valuation ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                HoldingRow(valuation, data, summary, displayCurrency, totalRate(valuation)) { onOpenHolding(valuation.holding.id) }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                if (filtered) "기록 ${records.size}건 / 전체 ${allRecords.size}건" else "기록",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (presentTypes.size > 1) {
            item {
                // 유형 칩: 여러 개 선택, [전체]는 모두 켜져 있으면 전체 해제·아니면 전체 선택
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFilterChip(
                        selected = RecordFilter.allShown(presentTypes, hiddenTypes),
                        label = "전체",
                        onClick = { onHiddenTypesChange(RecordFilter.toggleAll(presentTypes, hiddenTypes)) },
                    )
                    presentTypes.forEach { type ->
                        AppFilterChip(
                            selected = type !in hiddenTypes,
                            label = RecordFilter.label(type),
                            onClick = { onHiddenTypesChange(RecordFilter.toggle(type, hiddenTypes)) },
                        )
                    }
                }
            }
        }
        if (allRecords.isNotEmpty() && records.isEmpty()) {
            item {
                Text(
                    if (presentTypes.none { it !in hiddenTypes }) "보여줄 유형을 고르세요." else "선택한 유형의 기록이 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (allRecords.isEmpty()) {
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
                            subValue = cumulativeInvested[record.id]?.let { "누적 투자금 ${MoneyFormat.won(it)}" },
                            subValueColor = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun sortShortLabel(sort: HoldingSort): String = when (sort) {
    HoldingSort.VALUE -> "평가금"
    HoldingSort.TOTAL_RETURN -> "총 수익"
    HoldingSort.TODAY_RETURN -> "오늘"
}

/**
 * 보유 종목 한 줄 (2줄 구성): 1줄 종목명(가로 전체)·평가금, 2줄 종류·수량과 "총 ▲x%  오늘 ▼y%"(각자 색).
 */
@Composable
private fun HoldingRow(
    valuation: HoldingValuation,
    data: PortfolioData,
    summary: AccountSummary,
    displayCurrency: DisplayCurrency,
    rate: Double?,
    onClick: () -> Unit,
) {
    val holding = valuation.holding
    val conversion = DisplayConversion.of(holding.currency, displayCurrency, summary.usdKrw)
    val today = data.todayRate(valuation)
    val value = valuation.marketValue?.let { v ->
        conversion.convert(v)?.let { MoneyFormat.amount(conversion.currency, it) } ?: "환율 필요"
    } ?: "가격 입력 필요"
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TickerBadge(holding.code, holding.name, holding.market, holding.assetType)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    holding.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${holding.assetType.label} · ${MoneyFormat.decimal(valuation.position.quantity)}${holding.assetType.unit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (valuation.position.quantity > 0) {
                    Text("총 ${MoneyFormat.rate(rate)}", style = MaterialTheme.typography.labelLarge, color = profitColor(rate), maxLines = 1, softWrap = false)
                    if (today != null) {
                        Text("오늘 ${MoneyFormat.rate(today)}", style = MaterialTheme.typography.labelLarge, color = profitColor(today), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** 원화 보기일 때만 보이는 환율 설명. */
@Composable
fun CurrencyCaption(usdKrw: Double?) {
    Text(
        usdKrw?.let { "평가는 오늘 환율(${MoneyFormat.decimal(it, 2)}원), 매입은 매수한 날 환율" } ?: "환율이 없어 환산할 수 없습니다",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

/** 해외 종목 금액 보기 전환 (원화 / 외화). 계좌 상세와 종목 상세에서 함께 쓴다. */
@Composable
fun HoldingCurrencyToggle(selected: DisplayCurrency, usdKrw: Double?, onSelect: (DisplayCurrency) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentedPills(
            options = listOf(DisplayCurrency.FOREIGN, DisplayCurrency.KRW),
            selected = selected,
            label = { if (it == DisplayCurrency.FOREIGN) "외화" else "원화" },
            onSelect = onSelect,
            modifier = Modifier.width(140.dp),
        )
        if (selected == DisplayCurrency.KRW) {
            Text(
                usdKrw?.let { "평가는 오늘 환율(${MoneyFormat.decimal(it, 2)}원), 매입은 매수한 날 환율" } ?: "환율이 없어 환산할 수 없습니다",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 직접 입력 계좌의 예수금 근거: "10/1 입력 1,000,000원 + 이후 기록 −687,600원" 등. */
private fun cashBasis(detail: CashDetail?, currency: Currency, withLabel: Boolean): String {
    val label = if (withLabel) (if (currency == Currency.KRW) "원화: " else "달러: ") else ""
    val amount = { v: Double -> if (currency == Currency.KRW) MoneyFormat.won(v) else MoneyFormat.usd(v) }
    val signed = { v: Double -> if (currency == Currency.KRW) MoneyFormat.signedWon(v) else MoneyFormat.signedUsd(v) }
    val anchor = detail?.anchor
    val date = detail?.anchorDate
    return label + when {
        detail == null || (anchor == null && !detail.estimated) -> "아직 입력하지 않았습니다. 증권사 앱의 예수금을 입력하세요."
        anchor == null || date == null -> "입력한 예수금이 없어 기록(입금·매매 등)으로 계산한 값"
        !detail.estimated -> "${formatDate(date)} 입력한 값"
        else -> "${formatDate(date)} 입력 ${amount(anchor)} + 이후 기록 ${signed(detail.change)} (계산)"
    }
}

/** 수익률을 계산할 수 없는 이유 안내 (웹 AccountDetailPage 와 같은 문구). */
private fun noRateText(reason: PortfolioCalculator.NoRateReason?): String = when (reason) {
    PortfolioCalculator.NoRateReason.BALANCED -> "넣은 돈과 뺀 돈(출금·이체)이 같아 투자금이 0원이라 수익률을 계산할 수 없어요"
    PortfolioCalculator.NoRateReason.NEGATIVE -> "넣은 돈보다 뺀 돈(출금·이체)이 많아 투자금이 0원 이하라 수익률을 계산할 수 없어요"
    else -> "입금 기록이 없어 수익률을 계산할 수 없어요"
}
