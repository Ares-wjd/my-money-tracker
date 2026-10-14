package com.mymoneytracker.app.ui.records

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.ChipSelector
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.SegmentedPills
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.CashCalculator
import com.mymoneytracker.core.portfolio.CashFlowImport
import com.mymoneytracker.core.portfolio.DividendImport
import com.mymoneytracker.core.portfolio.ExchangeImport
import com.mymoneytracker.core.portfolio.TradeImport

/** 표 종류: 칸 제목과 칸 폭 (dp). */
private enum class ImportKind(val label: String, val headers: List<String>, val widths: List<Int>) {
    CASH("입출금", CashFlowImport.HEADERS, listOf(104, 64, 120)),
    TRADE("매매", TradeImport.HEADERS, listOf(104, 64, 140, 92, 92, 68, 84)),
    DIVIDEND("배당", DividendImport.HEADERS, listOf(104, 92, 140, 96, 96)),
    EXCHANGE("환전", ExchangeImport.HEADERS, listOf(104, 80, 100, 92, 120)),
    ;

    val columns: Int get() = headers.size

    /** 체크박스로 보여줄 칸 (환전의 달러→원화). */
    val checkColumn: Int? get() = if (this == EXCHANGE) ExchangeImport.CHECK else null
}

private const val INDEX_WIDTH = 28
private const val STATUS_WIDTH = 176

/** 줄 하나의 인식 결과 (표 종류와 상관없이 화면에 보여줄 것). */
private data class RowView(val status: TradeImport.Status, val text: String, val warning: Boolean = false)

/** 표를 화면 회전 등에도 남긴다 (칸 = 탭, 줄 = 줄바꿈. 칸에는 탭·줄바꿈이 들어가지 않는다). */
private fun gridSaver(columns: Int) = Saver<List<List<String>>, String>(
    save = { grid -> grid.joinToString("\n") { it.joinToString("\t") } },
    restore = { text -> text.split("\n").map { line -> List(columns) { line.split("\t").getOrNull(it).orEmpty() } } },
)

/**
 * 표로 일괄 입력 (입출금 · 매매 · 배당): 빈 표에 엑셀·구글시트에서 복사한 칸을 붙여 넣으면, 고른 칸부터 오른쪽·아래로 채운다.
 * 줄마다 인식 결과를 보여주고, [저장]을 누르면 기록(매매는 새 종목도)을 한꺼번에 저장한다.
 * 한투 연결 계좌는 매매를 불러오므로 입출금·배당만.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TradeImportScreen(
    viewModel: PortfolioViewModel,
    accountId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val apiStatus by viewModel.apiStatus.collectAsStateWithLifecycle()
    val linked = apiStatus.linkedAccountId == accountId ||
        data?.let { CashCalculator.isBrokerLinked(accountId, it.records) } == true
    // 한투 연결 계좌는 매매를 불러오고 환전 기록을 쓰지 않는다
    val kinds = ImportKind.entries.filter { !linked || (it != ImportKind.TRADE && it != ImportKind.EXCHANGE) }
    var kindName by rememberSaveable { mutableStateOf(if (linked) ImportKind.CASH.name else ImportKind.TRADE.name) }
    val kind = kinds.firstOrNull { it.name == kindName } ?: kinds.first()
    // 표 종류마다 따로 (바꿔도 입력한 칸이 남는다)
    var cashGrid by rememberSaveable(stateSaver = gridSaver(ImportKind.CASH.columns)) { mutableStateOf(TradeImport.emptyGrid(10, ImportKind.CASH.columns)) }
    var tradeGrid by rememberSaveable(stateSaver = gridSaver(ImportKind.TRADE.columns)) { mutableStateOf(TradeImport.emptyGrid()) }
    var dividendGrid by rememberSaveable(stateSaver = gridSaver(ImportKind.DIVIDEND.columns)) { mutableStateOf(TradeImport.emptyGrid(10, ImportKind.DIVIDEND.columns)) }
    var exchangeGrid by rememberSaveable(stateSaver = gridSaver(ImportKind.EXCHANGE.columns)) { mutableStateOf(TradeImport.emptyGrid(10, ImportKind.EXCHANGE.columns)) }
    val grid = when (kind) {
        ImportKind.CASH -> cashGrid
        ImportKind.TRADE -> tradeGrid
        ImportKind.DIVIDEND -> dividendGrid
        ImportKind.EXCHANGE -> exchangeGrid
    }
    val setGrid: (List<List<String>>) -> Unit = { value ->
        when (kind) {
            ImportKind.CASH -> cashGrid = value
            ImportKind.TRADE -> tradeGrid = value
            ImportKind.DIVIDEND -> dividendGrid = value
            ImportKind.EXCHANGE -> exchangeGrid = value
        }
    }
    val columns = kind.columns
    // 표 아래에서 고친 새 종목 (키 → 시장·유형)
    val overrides = remember { mutableStateMapOf<String, TradeImport.NewHolding>() }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var addRowsText by rememberSaveable { mutableStateOf("") }
    val hasContent = grid.any { row -> row.any { it.isNotBlank() } }
    val anyContent = listOf(cashGrid, tradeGrid, dividendGrid, exchangeGrid).any { g -> g.any { row -> row.any { it.isNotBlank() } } }
    val leave = { if (anyContent) confirmLeave = true else onBack() }
    BackHandler(onBack = leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle("표로 일괄 입력") },
                navigationIcon = {
                    IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        val current = data
        if (current == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val accountHoldings = current.holdings.filter { it.accountId == accountId }
        val accountRecords = current.records.filter { it.accountId == accountId }
        val analysis = remember(tradeGrid, current.holdings, current.records, overrides.toMap()) {
            TradeImport.analyze(tradeGrid, accountId, accountHoldings, current.holdings, accountRecords, overrides.toMap())
        }
        val cashRows = remember(cashGrid, current.records) { CashFlowImport.analyze(cashGrid, accountId, accountRecords) }
        val dividendRows = remember(dividendGrid, current.holdings, current.records) {
            DividendImport.analyze(dividendGrid, accountHoldings, accountRecords)
        }
        val exchangeRows = remember(exchangeGrid, current.records) { ExchangeImport.analyze(exchangeGrid, accountId, accountRecords) }
        // 표 종류와 상관없이 화면에 보여줄 줄별 결과
        val views: List<RowView> = when (kind) {
            ImportKind.TRADE -> analysis.rows.map { row ->
                // 수량 단위 (금현물은 g)
                val unit = row.holding?.assetType?.unit
                    ?: analysis.newHoldings.firstOrNull { it.key == row.newKey }?.assetType?.unit ?: "주"
                RowView(row.status, statusText(row, unit))
            }
            ImportKind.CASH -> cashRows.map { row ->
                RowView(
                    row.status,
                    when (row.status) {
                        TradeImport.Status.EMPTY -> ""
                        TradeImport.Status.ERROR -> row.error.orEmpty()
                        TradeImport.Status.DUPLICATE -> "중복 의심 (저장 안 함)"
                        TradeImport.Status.OK -> (if (row.type == RecordType.DEPOSIT) "입금 " else "출금 ") + MoneyFormat.won(row.amount)
                    },
                )
            }
            ImportKind.DIVIDEND -> dividendRows.map { row ->
                RowView(
                    row.status,
                    when (row.status) {
                        TradeImport.Status.EMPTY -> ""
                        TradeImport.Status.ERROR -> row.error.orEmpty()
                        TradeImport.Status.DUPLICATE -> "중복 의심 (저장 안 함)"
                        TradeImport.Status.OK -> {
                            val h = row.holding!!
                            "${h.name} · 배당 " + MoneyFormat.amount(h.currency, row.amount) + (row.warning?.let { "\n$it" } ?: "")
                        }
                    },
                    warning = row.warning != null,
                )
            }
            ImportKind.EXCHANGE -> exchangeRows.map { row ->
                RowView(
                    row.status,
                    when (row.status) {
                        TradeImport.Status.EMPTY -> ""
                        TradeImport.Status.ERROR -> row.error.orEmpty()
                        TradeImport.Status.DUPLICATE -> "중복 의심 (저장 안 함)"
                        TradeImport.Status.OK -> (if (row.toKrw) "달러→원화 · " else "원화→달러 · ") + MoneyFormat.usd(row.usd) + " · " +
                            MoneyFormat.won(row.krw) + " (환율 ${MoneyFormat.decimal(row.rate, 2)})" + (row.warning?.let { "\n$it" } ?: "")
                    },
                    warning = row.warning != null,
                )
            }
        }
        val errorRows = views.withIndex().filter { it.value.status == TradeImport.Status.ERROR }.map { it.index }
        val saveCount = views.count { it.status == TradeImport.Status.OK }
        val duplicateCount = views.count { it.status == TradeImport.Status.DUPLICATE }
        val hScroll = rememberScrollState()

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegmentedPills(kinds, kind, { it.label }, { kindName = it.name })
                    Text(
                        "엑셀·구글시트에서 복사한 칸을, 채우기 시작할 칸을 누르고 붙여 넣으세요. 고른 칸부터 오른쪽·아래로 채워지고 줄은 자동으로 늘어납니다. " +
                            "열이나 줄을 나눠 여러 번 붙여 넣어도 됩니다. 표는 좌우로 밀어 볼 수 있습니다. " +
                            when (kind) {
                                ImportKind.CASH -> "금액은 입금 +, 출금 −(예: -300000)로 쓰고, 입출금 칸은 비워도 됩니다 (원화). 이체는 기록 추가에서 직접 넣으세요."
                                ImportKind.TRADE -> "수량은 매수 +, 매도 −(예: -3)로 쓰고, 매매구분은 비워도 됩니다."
                                ImportKind.DIVIDEND -> "종목은 종목코드로 찾고 종목명은 확인용입니다. 세후 금액을 국내 종목은 원화배당금, 해외 종목은 외화배당금 칸에 넣으세요."
                                ImportKind.EXCHANGE -> "달러→원화를 체크하지 않으면 원화→달러(달러 매수)입니다. 외화·환율·원화 중 두 칸만 넣으면 나머지는 계산합니다 (양수로)."
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { HeaderRow(kind, hScroll) }
            itemsIndexed(grid) { r, cells ->
                GridRow(
                    index = r,
                    cells = cells,
                    view = views.getOrNull(r),
                    widths = kind.widths,
                    scroll = hScroll,
                    checkColumn = kind.checkColumn,
                    onCellChange = { c, old, value ->
                        setGrid(if (value.contains('\t') || value.contains('\n')) {
                            // 붙여넣기: 원래 글 뒤에 붙었으면 붙인 부분만
                            val pasted = if (old.isNotEmpty() && value.startsWith(old)) value.substring(old.length) else value
                            TradeImport.paste(grid, r, c, pasted, columns)
                        } else {
                            grid.mapIndexed { i, row -> if (i == r) row.mapIndexed { j, v -> if (j == c) value else v } else row }
                        })
                    },
                    onRemove = {
                        setGrid(grid.filterIndexed { i, _ -> i != r }.ifEmpty { TradeImport.emptyGrid(1, columns) })
                    },
                )
            }
            item {
                val room = TradeImport.MAX_ROWS - grid.size
                val addRows = addRowsText.toIntOrNull()?.coerceAtMost(room) ?: 0
                FlowRow(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { setGrid(grid + TradeImport.emptyGrid(minOf(5, room), columns)) },
                        enabled = room > 0,
                    ) { Text("+ 5줄 추가") }
                    // 원하는 만큼 줄 추가 (표 전체 최대 300줄)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Cell(addRowsText, 56) { v -> addRowsText = v.filter { it.isDigit() }.take(3) }
                        OutlinedButton(
                            onClick = {
                                setGrid(grid + TradeImport.emptyGrid(addRows, columns))
                                addRowsText = ""
                            },
                            enabled = addRows > 0,
                        ) { Text("줄 추가") }
                    }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = hasContent) { Text("표 비우기") }
                }
            }
            if (kind == ImportKind.TRADE && analysis.newHoldings.isNotEmpty()) {
                item {
                    SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), title = "새로 만들 종목 ${analysis.newHoldings.size}개") {
                        Text(
                            "이 계좌에 없는 종목은 저장할 때 함께 만듭니다. 시장·자산 유형을 확인하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        analysis.newHoldings.forEach { n ->
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            Text(n.name + if (n.code.isNotEmpty()) " (${n.code})" else "", style = MaterialTheme.typography.titleSmall)
                            if (n.assetType != AssetType.GOLD) {
                                ChipSelector("시장", Market.entries, n.market, { it.label }, { overrides[n.key] = n.copy(market = it) })
                            }
                            ChipSelector("자산 유형", AssetType.entries, n.assetType, { it.label }, {
                                overrides[n.key] = n.copy(assetType = it, market = if (it == AssetType.GOLD) Market.KR else n.market)
                            })
                        }
                    }
                }
            }
            item {
                val errors = errorRows
                val count = saveCount
                val newCount = if (kind == ImportKind.TRADE) analysis.newHoldings.size else 0
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "저장할 ${kind.label} 기록 ${count}건" +
                            (if (newCount > 0) " · 새 종목 ${newCount}개" else "") +
                            (if (duplicateCount > 0) " · 중복 의심 ${duplicateCount}건 제외" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    when {
                        errors.isNotEmpty() -> WarningText(
                            "오류가 있는 줄: " + errors.take(10).joinToString(", ") { "${it + 1}번" } +
                                (if (errors.size > 10) " 외 ${errors.size - 10}줄" else "") + " — 고치거나 줄을 빼세요.",
                        )
                        count == 0 -> WarningText("저장할 줄이 없습니다. 표에 붙여 넣거나 입력하세요.")
                    }
                    Button(
                        onClick = {
                            when (kind) {
                                ImportKind.TRADE -> viewModel.saveImport(accountId, analysis)
                                ImportKind.CASH -> viewModel.saveImportedRecords(CashFlowImport.build(cashRows, accountId))
                                ImportKind.DIVIDEND -> viewModel.saveImportedRecords(DividendImport.build(dividendRows, accountId))
                                ImportKind.EXCHANGE -> viewModel.saveImportedRecords(ExchangeImport.build(exchangeRows, accountId))
                            }
                            onDone()
                        },
                        enabled = errors.isEmpty() && count > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("저장") }
                }
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "표를 버릴까요?",
            text = "저장하지 않은 표는 사라집니다.",
            confirmLabel = "버리기",
            onConfirm = onBack,
            onDismiss = { confirmLeave = false },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "표 비우기",
            text = "이 표(${kind.label})에 입력한 칸을 모두 지웁니다.",
            confirmLabel = "비우기",
            onConfirm = {
                setGrid(TradeImport.emptyGrid(10, columns))
                if (kind == ImportKind.TRADE) overrides.clear()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun HeaderRow(kind: ImportKind, scroll: ScrollState) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", Modifier.width(INDEX_WIDTH.dp), style = MaterialTheme.typography.labelMedium)
        kind.headers.forEachIndexed { i, h ->
            Text(h, Modifier.width(kind.widths[i].dp).padding(horizontal = 2.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Text("인식 결과", Modifier.width(STATUS_WIDTH.dp).padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GridRow(
    index: Int,
    cells: List<String>,
    view: RowView?,
    widths: List<Int>,
    scroll: ScrollState,
    checkColumn: Int?,
    onCellChange: (col: Int, old: String, value: String) -> Unit,
    onRemove: () -> Unit,
) {
    val error = view?.status == TradeImport.Status.ERROR
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", Modifier.width(INDEX_WIDTH.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        cells.forEachIndexed { c, value ->
            if (c == checkColumn) {
                // 체크박스 칸 (붙여 넣은 글이 체크로 읽히면 체크 표시)
                Box(Modifier.width(widths[c].dp), contentAlignment = Alignment.Center) {
                    Checkbox(
                        checked = ExchangeImport.parseCheck(value) == true,
                        onCheckedChange = { onCellChange(c, value, if (it) ExchangeImport.CHECKED else "") },
                    )
                }
            } else {
                Cell(value, widths[c]) { onCellChange(c, value, it) }
            }
        }
        Text(
            view?.text.orEmpty(),
            Modifier.width(STATUS_WIDTH.dp).padding(start = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = when {
                error -> MaterialTheme.colorScheme.error
                view?.status == TradeImport.Status.DUPLICATE -> MaterialTheme.colorScheme.onSurfaceVariant
                view?.warning == true -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "${index + 1}번 줄 빼기", modifier = Modifier.size(18.dp))
        }
    }
}

private fun statusText(row: TradeImport.Row?, unit: String): String = when (row?.status) {
    null, TradeImport.Status.EMPTY -> ""
    TradeImport.Status.ERROR -> row.error.orEmpty()
    TradeImport.Status.DUPLICATE -> "중복 의심 (저장 안 함)"
    TradeImport.Status.OK -> (if (row.holding != null) "기존 종목" else "새 종목") + " · " +
        (if (row.type == RecordType.SELL) "매도 " else "매수 ") + MoneyFormat.decimal(row.quantity) + unit
}

@Composable
private fun Cell(value: String, width: Int, onChange: (String) -> Unit) {
    // 붙여넣기의 탭·줄바꿈을 받으려고 singleLine 대신 maxLines = 1 을 쓴다
    BasicTextField(
        value = value,
        onValueChange = onChange,
        maxLines = 1,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier
            .width(width.dp)
            .padding(horizontal = 2.dp)
            .height(36.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) { inner() }
        },
    )
}
