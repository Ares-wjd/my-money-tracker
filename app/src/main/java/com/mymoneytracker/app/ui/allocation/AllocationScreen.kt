package com.mymoneytracker.app.ui.allocation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.AppFilterChip
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.MoneyField
import com.mymoneytracker.app.ui.common.PlaceholderBadge
import com.mymoneytracker.app.ui.common.PlaceholderContent
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.SegmentedPills
import com.mymoneytracker.app.ui.common.TickerBadge
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.portfolio.AllocationCalculator
import com.mymoneytracker.core.portfolio.AllocationRow
import com.mymoneytracker.core.portfolio.AllocationView
import com.mymoneytracker.core.portfolio.TradeSuggestion
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

private enum class Ring(val label: String) { CURRENT("실제"), TARGET("목표"), COMPARE("비교") }

/** 색을 따로 주는 종목 수. 7번째부터는 "기타" (검증한 범주 팔레트의 앞 6색, 웹과 같은 값). */
private const val MAX_COLORS = 6
private const val OTHER_KEY = "__other"

@Composable
private fun seriesColor(index: Int): Color {
    val dark = isSystemInDarkTheme()
    val light = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300)
    val darkColors = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300)
    return when {
        index < MAX_COLORS -> Color(if (dark) darkColors[index] else light[index])
        else -> if (dark) Color(0xFF5E6B65) else Color(0xFF9AA5A0)
    }
}

private data class Slice(val key: String, val name: String, val color: Color, val weight: Double)

@Composable
private fun slicesOf(rows: List<AllocationRow>, weight: (AllocationRow) -> Double): List<Slice> {
    val slices = rows.take(MAX_COLORS).mapIndexed { i, r -> Slice(r.key, r.name, seriesColor(i), weight(r)) }.toMutableList()
    val other = rows.drop(MAX_COLORS).sumOf(weight)
    if (other > 0) slices += Slice(OTHER_KEY, "기타", seriesColor(MAX_COLORS), other)
    return slices.filter { it.weight > 0 }
}

private fun pct(w: Double) = "${MoneyFormat.decimal(w * 100, 1)}%"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllocationScreen(
    viewModel: PortfolioViewModel,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val targets by viewModel.allocationTargets.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedAllocation.collectAsStateWithLifecycle()
    val ringName by viewModel.allocationRing.collectAsStateWithLifecycle()
    val useCash by viewModel.allocationUseCash.collectAsStateWithLifecycle()
    val ring = Ring.entries.firstOrNull { it.name == ringName } ?: Ring.COMPARE

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("비중") },
                actions = { IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "비중 설정 추가") } },
            )
        },
    ) { padding ->
        val current = data
        val list = targets
        when {
            current == null || list == null -> LoadingBox(Modifier.padding(padding))
            list.isEmpty() -> PlaceholderContent(
                "비중 설정이 없습니다",
                "ISA 자산배분, 미국 개별주처럼 계좌별 목표 비중을 정하면 실제 비중과 비교하고, 비중에 맞춰 얼마씩 사면 되는지 계산해 드립니다.",
                Modifier.padding(padding),
            ) { TextButton(onClick = onAdd) { Text("비중 설정 추가") } }
            else -> {
                val selected = list.firstOrNull { it.id == selectedId } ?: list.first()
                val view = AllocationCalculator.view(selected, current.summary.accounts, current.summary.usdKrw)
                AllocationContent(
                    view = view,
                    names = list.map { it.id to it.name },
                    accountNames = selected.accountIds.mapNotNull { id -> current.accounts.firstOrNull { it.id == id }?.name },
                    excludedNames = selected.excluded.map { key ->
                        current.holdings.firstOrNull { AllocationCalculator.keyOf(it) == key }?.name ?: key.substringAfter(':')
                    },
                    usdKrw = current.summary.usdKrw,
                    ring = ring,
                    padding = padding,
                    onSelect = viewModel::selectAllocation,
                    onRing = { viewModel.setAllocationRing(it.name) },
                    useCash = useCash,
                    onUseCash = viewModel::setAllocationUseCash,
                    onEdit = { onEdit(selected.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllocationContent(
    view: AllocationView,
    names: List<Pair<String, String>>,
    accountNames: List<String>,
    excludedNames: List<String>,
    usdKrw: Double?,
    ring: Ring,
    padding: PaddingValues,
    onSelect: (String) -> Unit,
    onRing: (Ring) -> Unit,
    useCash: Boolean,
    onUseCash: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var showRebalance by rememberSaveable { mutableStateOf(false) }
    val target = view.target
    val amount = MoneyFormat.parseDecimal(amountText) ?: 0.0

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (names.size > 1) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    names.forEach { (id, name) -> AppFilterChip(selected = id == target.id, label = name, onClick = { onSelect(id) }) }
                }
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(target.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "비중 설정 편집") }
                }
                Text(
                    listOf(
                        accountNames.joinToString(", ").ifBlank { "대상 계좌 없음" },
                        "합계 ${MoneyFormat.won(view.totalKrw)}" + if (target.includeCash) " (예수금 포함)" else "",
                        "강조 ±${MoneyFormat.decimal(target.threshold, 2)}%p",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (excludedNames.isNotEmpty()) {
                    Text("제외: " + excludedNames.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (view.missingPrice) WarningText("현재가가 없는 종목이 있어 비중에서 빠져 있습니다.")
                SegmentedPills(Ring.entries, ring, { it.label }, onRing, Modifier.padding(top = 8.dp))
                Donut(view, ring)
            }
        }
        item {
            SectionCard(title = "목표와 실제") {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("종목", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    listOf("목표", "실제", "차이").forEach {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(if (it == "차이") 84.dp else 56.dp), textAlign = TextAlign.End)
                    }
                }
                view.rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TableRow(row, view.highlighted(row))
                }
            }
        }
        item {
            SectionCard(title = "매수 계산기") {
                Text(
                    "넣을 돈을 입력하면, 팔지 않고 사기만 해서 목표에 가장 가까워지도록 종목별로 얼마를 살지 알려 드립니다. 매수 단위(1주 또는 금액 단위)에 맞춰 내림하고, 남은 돈은 가장 부족한 종목부터 더 삽니다. 계좌에 없는 종목은 현재가를 넣어야 주 수가 나옵니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MoneyField("이번에 넣을 돈", amountText, { amountText = it }, quick = true, modifier = Modifier.padding(top = 4.dp))
                if (view.cashKrw > 0) {
                    Row(Modifier.fillMaxWidth().clickable { onUseCash(!useCash) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useCash, onCheckedChange = onUseCash)
                        Text("현재 예수금도 함께 쓰기 (${MoneyFormat.won(view.cashKrw)})", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val plan = AllocationCalculator.buyOnly(view, amount, useCash)
                if (plan.poolKrw > 0 && plan.cashUsedKrw > 0) {
                    Text(
                        "사용할 돈: 넣을 돈 ${MoneyFormat.won(amount)} + 예수금 ${MoneyFormat.won(plan.cashUsedKrw)} = ${MoneyFormat.won(plan.poolKrw)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (plan.poolKrw > 0 && plan.trades.isEmpty()) Text("목표 비중이 있는 종목이 없습니다.", style = MaterialTheme.typography.bodySmall)
                plan.trades.forEach { TradeLine(it, usdKrw, prefix = "") }
                if (target.noBuy.isNotEmpty()) {
                    Text(
                        "지금 매수 안 함: " + target.noBuy.joinToString(", ") { key -> view.rows.firstOrNull { it.key == key }?.name ?: key.substringAfter(':') },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (plan.poolKrw > 0 && plan.leftoverKrw >= 1) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("남는 돈 (매수 단위 때문에)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(MoneyFormat.won(plan.leftoverKrw), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { showRebalance = !showRebalance }, contentPadding = PaddingValues(0.dp)) {
                    Text("팔기까지 해서 정확히 맞추려면 " + if (showRebalance) "▴" else "▾")
                }
                if (showRebalance) {
                    val trades = AllocationCalculator.rebalance(view)
                    if (trades.isEmpty()) Text("이미 목표와 같습니다.", style = MaterialTheme.typography.bodySmall)
                    trades.forEach { TradeLine(it, usdKrw, prefix = if (it.amountKrw > 0) "사기 " else "팔기 ") }
                }
            }
        }
    }
}

/** 비중 차이 색: 부족 = 파랑, 초과 = 빨강 (줄 배경은 옅게, 태그는 진하게 — 태그 글자 대비 4.5:1 이상, 웹과 같은 값). */
private data class DiffColors(val row: Color, val tag: Color, val tagInk: Color)

@Composable
private fun diffColors(over: Boolean): DiffColors {
    val dark = isSystemInDarkTheme()
    return when {
        over && !dark -> DiffColors(Color(0xFFFDECEE), Color(0xFFC62436), Color.White)
        over -> DiffColors(Color(0xFF3B1F24), Color(0xFFFF8A96), Color(0xFF16201C))
        !dark -> DiffColors(Color(0xFFEAF1FD), Color(0xFF1F5FD1), Color.White)
        else -> DiffColors(Color(0xFF1D2B45), Color(0xFF8FB4FF), Color(0xFF16201C))
    }
}

@Composable
private fun TableRow(row: AllocationRow, highlighted: Boolean) {
    val over = row.diffPoints > 0
    val colors = diffColors(over)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (highlighted) Modifier.background(colors.row, RoundedCornerShape(8.dp)) else Modifier)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 배지가 없는 줄(예수금·미보유)도 같은 폭의 회색 배지로 이름 시작 위치를 맞춘다
            val holding = row.holding
            if (holding != null) {
                TickerBadge(holding.code, holding.name, holding.market, holding.assetType)
            } else {
                PlaceholderBadge(
                    when {
                        row.isCash -> "현금"
                        AllocationCalculator.isCodeless(row.key) || !row.key.contains(':') -> "티커 없음"
                        else -> row.key.substringAfter(':')
                    },
                )
            }
            Text(row.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(pct(row.targetWeight), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        Text(pct(row.currentWeight), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        Column(Modifier.width(84.dp), horizontalAlignment = Alignment.End) {
            Text(
                (if (row.diffPoints > 0) "+" else "") + MoneyFormat.decimal(row.diffPoints, 1) + "%p",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            // 방향마다 색이 다른 태그 (색만으로 구분하지 않도록 ▲▼·글자 함께)
            if (highlighted) {
                Text(
                    if (over) "▲ 초과" else "▼ 부족",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.tagInk,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .background(colors.tag, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun TradeLine(s: TradeSuggestion, usdKrw: Double?, prefix: String) {
    val parts = mutableListOf(MoneyFormat.won(kotlin.math.abs(s.amountKrw)))
    if (s.row.isForeign && usdKrw != null && usdKrw > 0) parts += MoneyFormat.usd(kotlin.math.abs(s.amountKrw) / usdKrw)
    // 1주 단위면 정확한 주 수, 금액 단위(소수점 매수)면 대략 주 수
    s.shares?.let { parts += if (s.fractional) "약 ${MoneyFormat.decimal(it, 2)}주" else "${MoneyFormat.decimal(it, 0)}주" }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (s.row.isCash) "예수금으로 남김" else s.row.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(prefix + parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/** 도넛: 실제 / 목표 / 비교(바깥 실제·안쪽 목표). 조각을 누르면 가운데에 그 종목 비중. */
@Composable
private fun Donut(view: AllocationView, ring: Ring) {
    var picked by remember(view.target.id, ring) { mutableStateOf<String?>(null) }
    val current = slicesOf(view.rows) { it.currentWeight }
    val target = slicesOf(view.rows) { it.targetWeight }
    val surface = MaterialTheme.colorScheme.surfaceContainerLowest
    val otherRows = view.rows.drop(MAX_COLORS)
    fun weightsOf(key: String): Pair<Double, Double> {
        val row = view.rows.firstOrNull { it.key == key }
        return if (row != null) row.currentWeight to row.targetWeight
        else otherRows.sumOf { it.currentWeight } to otherRows.sumOf { it.targetWeight }
    }
    val legend = view.rows.take(MAX_COLORS).mapIndexed { i, r -> Triple(r.key, r.name, i) } +
        if (view.rows.size > MAX_COLORS) listOf(Triple(OTHER_KEY, "기타 ${view.rows.size - MAX_COLORS}종목", MAX_COLORS)) else emptyList()

    val pickedName = picked?.let { key -> legend.firstOrNull { it.first == key }?.second }
    val centerLabel = pickedName ?: if (ring == Ring.TARGET) "목표" else "합계"
    val centerValue = picked?.let { key ->
        val (cw, tw) = weightsOf(key)
        when (ring) {
            Ring.CURRENT -> pct(cw)
            Ring.TARGET -> pct(tw)
            Ring.COMPARE -> "${pct(cw)} / ${pct(tw)}"
        }
    } ?: if (ring == Ring.TARGET) pct(view.rows.sumOf { it.targetWeight }) else MoneyFormat.shortWon(view.totalKrw)

    // 링 반지름 (dp 비율, 지름 200dp 기준)
    val rings: List<Triple<List<Slice>, Float, Float>> = when (ring) {
        Ring.CURRENT -> listOf(Triple(current, 0.60f, 1f))
        Ring.TARGET -> listOf(Triple(target, 0.60f, 1f))
        Ring.COMPARE -> listOf(Triple(current, 0.72f, 1f), Triple(target, 0.46f, 0.66f))
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(200.dp)
                    .semantics { contentDescription = "비중 도넛 그래프 (${ring.label})" }
                    .pointerInput(view, ring) {
                        detectTapGestures { offset ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val r = hypot(offset.x - c.x, offset.y - c.y) / (size.width / 2f)
                            val hit = rings.firstOrNull { (_, r0, r1) -> r in r0..r1 } ?: run { picked = null; return@detectTapGestures }
                            // 12시 방향부터 시계 방향 각도 (0~1)
                            var a = (atan2((offset.x - c.x).toDouble(), (c.y - offset.y).toDouble()) / (2 * PI))
                            if (a < 0) a += 1.0
                            val total = hit.first.sumOf { it.weight }
                            var acc = 0.0
                            val slice = hit.first.firstOrNull { s -> acc += s.weight / total; a <= acc }
                            picked = if (slice?.key == picked) null else slice?.key
                        }
                    },
            ) {
                val radius = size.width / 2f
                rings.forEach { (slices, r0, r1) ->
                    val total = slices.sumOf { it.weight }
                    if (total <= 0) return@forEach
                    val thickness = radius * (r1 - r0)
                    val mid = radius * (r0 + r1) / 2f
                    var start = -90f
                    slices.forEach { s ->
                        val sweep = (s.weight / total * 360.0).toFloat()
                        val alpha = if (picked != null && picked != s.key) 0.35f else 1f
                        drawArc(
                            color = s.color.copy(alpha = alpha),
                            startAngle = start,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = Offset(center.x - mid, center.y - mid),
                            size = Size(mid * 2, mid * 2),
                            style = Stroke(width = thickness),
                        )
                        // 조각 사이 2dp 바탕색 틈
                        if (slices.size > 1) {
                            val rad = Math.toRadians(start.toDouble())
                            val inner = Offset(center.x + (radius * r0) * kotlin.math.cos(rad).toFloat(), center.y + (radius * r0) * kotlin.math.sin(rad).toFloat())
                            val outer = Offset(center.x + (radius * r1) * kotlin.math.cos(rad).toFloat(), center.y + (radius * r1) * kotlin.math.sin(rad).toFloat())
                            drawLine(surface, inner, outer, strokeWidth = 2.dp.toPx())
                        }
                        start += sweep
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(110.dp)) {
                Text(centerLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(centerValue, style = if (ring == Ring.COMPARE && picked != null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        // 범례: 종목 순서(목표 큰 순) 그대로, 실제·목표 % 함께 (색만으로 구분하지 않도록)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("종목", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("실제", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
            Text("목표", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
        }
        legend.forEach { (key, name, index) ->
            val (cw, tw) = weightsOf(key)
            Row(
                Modifier.fillMaxWidth().clickable { picked = if (picked == key) null else key }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).background(seriesColor(index), RoundedCornerShape(3.dp)))
                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pct(cw), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
                Text(pct(tw), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
            }
        }
        if (ring == Ring.COMPARE) {
            Text("바깥 고리: 실제 · 안쪽 고리: 목표", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
