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
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.TradeImport

/** 칸 폭 (dp): 날짜 / 매매구분 / 종목명 / 종목코드 / 평단가 / 수량 / 제비용합 */
private val CELL_WIDTHS = listOf(104, 64, 140, 92, 92, 68, 84)
private const val INDEX_WIDTH = 28
private const val STATUS_WIDTH = 176

/** 표를 화면 회전 등에도 남긴다 (칸 = 탭, 줄 = 줄바꿈. 칸에는 탭·줄바꿈이 들어가지 않는다). */
private val GridSaver = Saver<List<List<String>>, String>(
    save = { grid -> grid.joinToString("\n") { it.joinToString("\t") } },
    restore = { text -> text.split("\n").map { line -> List(TradeImport.COLUMNS) { line.split("\t").getOrNull(it).orEmpty() } } },
)

/**
 * 표로 일괄 입력 (매수·매도): 빈 표에 엑셀·구글시트에서 복사한 칸을 붙여 넣으면, 고른 칸부터 오른쪽·아래로 채운다.
 * 줄마다 인식 결과를 보여주고, [저장]을 누르면 새 종목과 기록을 한꺼번에 저장한다.
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
    var grid by rememberSaveable(stateSaver = GridSaver) { mutableStateOf(TradeImport.emptyGrid()) }
    // 표 아래에서 고친 새 종목 (키 → 시장·유형)
    val overrides = remember { mutableStateMapOf<String, TradeImport.NewHolding>() }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var addRowsText by rememberSaveable { mutableStateOf("") }
    val hasContent = grid.any { row -> row.any { it.isNotBlank() } }
    val leave = { if (hasContent) confirmLeave = true else onBack() }
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
        val analysis = remember(grid, current.holdings, current.records, overrides.toMap()) {
            TradeImport.analyze(grid, accountId, accountHoldings, current.holdings, accountRecords, overrides.toMap())
        }
        val hScroll = rememberScrollState()

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Text(
                    "엑셀·구글시트에서 복사한 칸을, 채우기 시작할 칸을 누르고 붙여 넣으세요. 고른 칸부터 오른쪽·아래로 채워지고 줄은 자동으로 늘어납니다. " +
                        "열이나 줄을 나눠 여러 번 붙여 넣어도 됩니다. 수량은 매수 +, 매도 −(예: -3)로 쓰고, 매매구분은 비워도 됩니다. 표는 좌우로 밀어 볼 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item { HeaderRow(hScroll) }
            itemsIndexed(grid) { r, cells ->
                val row = analysis.rows.getOrNull(r)
                // 수량 단위 (금현물은 g)
                val unit = row?.holding?.assetType?.unit
                    ?: analysis.newHoldings.firstOrNull { it.key == row?.newKey }?.assetType?.unit ?: "주"
                GridRow(
                    index = r,
                    cells = cells,
                    row = row,
                    unit = unit,
                    scroll = hScroll,
                    onCellChange = { c, old, value ->
                        grid = if (value.contains('\t') || value.contains('\n')) {
                            // 붙여넣기: 원래 글 뒤에 붙었으면 붙인 부분만
                            val pasted = if (old.isNotEmpty() && value.startsWith(old)) value.substring(old.length) else value
                            TradeImport.paste(grid, r, c, pasted)
                        } else {
                            grid.mapIndexed { i, row -> if (i == r) row.mapIndexed { j, v -> if (j == c) value else v } else row }
                        }
                    },
                    onRemove = {
                        grid = grid.filterIndexed { i, _ -> i != r }.ifEmpty { TradeImport.emptyGrid(1) }
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
                        onClick = { grid = grid + TradeImport.emptyGrid(minOf(5, room)) },
                        enabled = room > 0,
                    ) { Text("+ 5줄 추가") }
                    // 원하는 만큼 줄 추가 (표 전체 최대 300줄)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Cell(addRowsText, 56) { v -> addRowsText = v.filter { it.isDigit() }.take(3) }
                        OutlinedButton(
                            onClick = {
                                grid = grid + TradeImport.emptyGrid(addRows)
                                addRowsText = ""
                            },
                            enabled = addRows > 0,
                        ) { Text("줄 추가") }
                    }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = hasContent) { Text("표 비우기") }
                }
            }
            if (analysis.newHoldings.isNotEmpty()) {
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
                val errors = analysis.errorRows
                val count = analysis.saveCount
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "저장할 기록 ${count}건" +
                            (if (analysis.newHoldings.isNotEmpty()) " · 새 종목 ${analysis.newHoldings.size}개" else "") +
                            (if (analysis.duplicateCount > 0) " · 중복 의심 ${analysis.duplicateCount}건 제외" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    when {
                        errors.isNotEmpty() -> WarningText(
                            "오류가 있는 줄: " + errors.take(10).joinToString(", ") { "${it.index + 1}번" } +
                                (if (errors.size > 10) " 외 ${errors.size - 10}줄" else "") + " — 고치거나 줄을 빼세요.",
                        )
                        count == 0 -> WarningText("저장할 줄이 없습니다. 표에 붙여 넣거나 입력하세요.")
                    }
                    Button(
                        onClick = {
                            viewModel.saveImport(accountId, analysis)
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
            text = "입력한 칸을 모두 지웁니다.",
            confirmLabel = "비우기",
            onConfirm = {
                grid = TradeImport.emptyGrid()
                overrides.clear()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun HeaderRow(scroll: ScrollState) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", Modifier.width(INDEX_WIDTH.dp), style = MaterialTheme.typography.labelMedium)
        TradeImport.HEADERS.forEachIndexed { i, h ->
            Text(h, Modifier.width(CELL_WIDTHS[i].dp).padding(horizontal = 2.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Text("인식 결과", Modifier.width(STATUS_WIDTH.dp).padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GridRow(
    index: Int,
    cells: List<String>,
    row: TradeImport.Row?,
    unit: String,
    scroll: ScrollState,
    onCellChange: (col: Int, old: String, value: String) -> Unit,
    onRemove: () -> Unit,
) {
    val error = row?.status == TradeImport.Status.ERROR
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", Modifier.width(INDEX_WIDTH.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        cells.forEachIndexed { c, value ->
            Cell(value, CELL_WIDTHS[c]) { onCellChange(c, value, it) }
        }
        Text(
            statusText(row, unit),
            Modifier.width(STATUS_WIDTH.dp).padding(start = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = when {
                error -> MaterialTheme.colorScheme.error
                row?.status == TradeImport.Status.DUPLICATE -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
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
