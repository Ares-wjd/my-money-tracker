package com.mymoneytracker.app.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.ApiStatus
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.TickerLegend
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.app.ui.common.accountDescription
import com.mymoneytracker.app.ui.common.fabContainerColor
import com.mymoneytracker.app.ui.common.fabContentColor
import com.mymoneytracker.app.ui.common.tickerGroup
import com.mymoneytracker.app.ui.home.periodLine
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.AccountSummary
import com.mymoneytracker.core.portfolio.DisplayCurrency
import com.mymoneytracker.core.portfolio.HoldingSort
import com.mymoneytracker.core.portfolio.HoldingSorter
import com.mymoneytracker.core.portfolio.HoldingValuation
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.portfolio.RecordFilter

// 계좌 상세 화면 틀과 목록 순서. 각 칸은 AccountSummaryViews · AccountHoldingViews · AccountRecordViews.

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
    onOpenPeriod: () -> Unit = {},
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val stored by viewModel.storedCharts.collectAsStateWithLifecycle()
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
                // 이 계좌의 올해·연평균 수익률 (그래프 일별 값으로 계산, 계산 중이면 빈 글)
                periodText = remember(current, stored, accountId) { periodLine(current, stored, setOf(accountId)) },
                onOpenPeriod = onOpenPeriod,
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
    periodText: String = "",
    onOpenPeriod: () -> Unit = {},
) {
    val accountId = summary.account.id
    val allRecords = data.recordsOf(accountId)
    // 입금·출금·이체 기록마다 그 기록까지의 누적 투자금 (걸러 봐도 전체 기록 기준)
    val cumulativeInvested = remember(allRecords, accountId) { PortfolioCalculator.cumulativeInvested(accountId, allRecords) }
    // 계산 순서상 보유 수량이 모자라 계산되지 않는 매도 (이 계좌)
    val shorts = remember(allRecords, accountId) { PortfolioCalculator.shortSells(allRecords).filter { it.record.accountId == accountId } }
    val shortIds = shorts.map { it.record.id }.toSet()
    // 기록 유형 필터 (기본은 전부 보임)
    val presentTypes = remember(allRecords) { RecordFilter.present(allRecords) }
    val records = RecordFilter.apply(allRecords, hiddenTypes)
    val filtered = records.size != allRecords.size
    var visibleRecords by rememberSaveable(accountId) { mutableStateOf(30) }
    // 총 수익률은 보기를 따른다 (원화 보기: 매매일 환율이 모두 있으면 환차익 반영 원화 수익률)
    fun totalRate(v: HoldingValuation): Double? =
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
        item { AccountSummaryCard(summary, allRecords, periodText, onOpenPeriod, onAddRecord) }

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

        item { CashCard(summary, linked, onEnterCash) }

        item { HoldingsHeader(onAddHolding) }
        if (sorted.isNotEmpty()) {
            // 정렬 + 원화/외화를 한 줄로, 티커 색 범례는 ⓘ 를 눌러 본다
            item {
                HoldingsToolbar(
                    holdingSort = holdingSort,
                    onSelectHoldingSort = onSelectHoldingSort,
                    hasUsd = hasUsd,
                    displayCurrency = displayCurrency,
                    onSelectDisplayCurrency = onSelectDisplayCurrency,
                    showLegend = showLegend,
                    onToggleLegend = { showLegend = !showLegend },
                )
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
                HoldingsCard(
                    holdings = holdings,
                    soldOut = soldOut,
                    showSoldOut = showSoldOut,
                    onToggleSoldOut = { showSoldOut = !showSoldOut },
                    data = data,
                    summary = summary,
                    displayCurrency = displayCurrency,
                    rate = ::totalRate,
                    onOpenHolding = onOpenHolding,
                )
            }
        }

        if (shorts.isNotEmpty()) {
            item { ShortSellsCard(shorts, data, onOpenRecord) }
        }
        item {
            Text(
                if (filtered) "기록 ${records.size}건 / 전체 ${allRecords.size}건" else "기록",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (presentTypes.size > 1) {
            item { RecordTypeChips(presentTypes, hiddenTypes, onHiddenTypesChange) }
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
                RecordListCard(
                    records = records,
                    visible = visibleRecords,
                    onShowMore = { visibleRecords += 30 },
                    data = data,
                    accountId = accountId,
                    cumulativeInvested = cumulativeInvested,
                    shortIds = shortIds,
                    onOpenRecord = onOpenRecord,
                )
            }
        }
    }
}
