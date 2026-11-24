package com.mymoneytracker.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.AppFilterChip
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.portfolio.DividendHome
import com.mymoneytracker.core.portfolio.DividendItem
import com.mymoneytracker.core.portfolio.DividendKind
import com.mymoneytracker.core.portfolio.Dividends
import com.mymoneytracker.core.portfolio.TodayFilter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import com.mymoneytracker.app.ui.theme.LocalDarkTheme

/** 배당 화면·홈 카드가 쓰는 계산 결과 (v2.13). */
data class DividendData(val received: List<DividendItem>, val planned: List<DividendItem>) {
    val isEmpty: Boolean get() = received.isEmpty() && planned.isEmpty()
}

fun dividendData(data: PortfolioData, today: LocalDate, ids: Set<String>? = null): DividendData = DividendData(
    Dividends.received(data.holdings, data.records, data.market, today, data.summary.usdKrw, ids),
    Dividends.planned(data.summary, data.records, data.market, today, ids),
)

/** 배당 색 (웹과 같음): 받음 진녹색 · 확정 연녹색 · 추정 노란 바탕 + 점선 · 작년 같은 달 노랑. */
private object DivColors {
    @Composable fun received() = if (LocalDarkTheme.current) Color(0xFF52CDAE) else Color(0xFF0E7565)
    @Composable fun confirmed() = if (LocalDarkTheme.current) Color(0xFF2F7D69) else Color(0xFF8FC7B8)
    @Composable fun estimatedBg() = if (LocalDarkTheme.current) Color(0xFF3A3220) else Color(0xFFFBF3E2)
    @Composable fun estimatedLine() = if (LocalDarkTheme.current) Color(0xFFD9A441) else Color(0xFFB07A10)
    @Composable fun lastYear() = if (LocalDarkTheme.current) Color(0xFFB8913F) else Color(0xFFD9B867)
}

private fun won(v: Double) = MoneyFormat.shortWon(v)
private fun pct(r: Double?) = r?.let { MoneyFormat.decimal(it * 100, 1) + "%" } ?: "-"

/** 비교 두 줄 (v2.14): "작년 ○○" 아래 줄에 짧은 비율. [planned] 이고 지금이 0원이면 "아직 예정 없음". */
@Composable
private fun CompareLine(label: String, before: Double, now: Double, planned: Boolean = false) {
    Text("$label ${won(before)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (planned && now <= 0.5) {
        Text("아직 예정 없음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val change = Dividends.change(now, before) ?: return
    Text(MoneyFormat.rateShort(change), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = profitColor(change))
}

/** 점선 테두리 (추정 표시). */
private fun Modifier.dashedBorder(color: Color, radius: Dp) = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))),
    )
}

// ------------------------------------------------------------ 홈 카드

/** 홈 배당 카드: 올해 받은 배당·작년 같은 기간, 다음 달 예정·작년 같은 달, 1~12월 올해·작년 막대. 누르면 배당 화면. */
@Composable
fun DividendCard(home: DividendHome, onOpen: () -> Unit) {
    val received = DivColors.received()
    val planned = DivColors.confirmed()
    val lastYear = DivColors.lastYear()
    SectionCard(Modifier.clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("배당", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("세후 · 원화 ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("올해 받은 배당", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(won(home.thisYear), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                CompareLine("작년 같은 기간", home.lastYearSamePeriod, home.thisYear)
            }
            Column(Modifier.weight(1f)) {
                Text("${home.nextMonth.monthValue}월 예정", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(won(home.nextMonthPlanned), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                CompareLine("작년 ${home.nextMonth.monthValue}월", home.lastYearNextMonth, home.nextMonthPlanned, planned = true)
            }
        }
        val max = maxOf(1.0, home.months.maxOf { it.total }, home.lastYearMonths.maxOrNull() ?: 0.0)
        Canvas(Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp)) {
            val slot = size.width / 12
            val bar = (slot - 6.dp.toPx()) / 2
            home.months.forEachIndexed { i, t ->
                val x = i * slot + 2.dp.toPx()
                val hTotal = (t.total / max * size.height).toFloat()
                val hReceived = (t.received / max * size.height).toFloat()
                if (hTotal > 0) drawRect(planned, Offset(x, size.height - hTotal), Size(bar, hTotal))
                if (hReceived > 0) drawRect(received, Offset(x, size.height - hReceived), Size(bar, hReceived))
                val hLast = (home.lastYearMonths[i] / max * size.height).toFloat()
                if (hLast > 0) drawRect(lastYear, Offset(x + bar + 1.dp.toPx(), size.height - hLast), Size(bar, hLast))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            (1..12).forEach { m ->
                Text("$m", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(received, "올해 받음")
            Legend(planned, "올해 예정")
            Legend(lastYear, "작년 같은 달")
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// ------------------------------------------------------------ 배당 화면

/** 배당 화면 (디자인 시안 배당 캘린더): 계좌 칩 · 12칸 달력 (달을 누르면 날짜 달력) · 연도별 · 종목별 배당수익률. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DividendScreen(viewModel: PortfolioViewModel, onBack: () -> Unit) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    var hidden by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val today = remember { LocalDate.now() }
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var month by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle("배당") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } },
            )
        },
    ) { padding ->
        val current = data
        if (current == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val accountIds = current.summary.accounts.map { it.account.id }
        val hiddenSet = hidden.toSet()
        val ids = if (hiddenSet.isEmpty()) null else accountIds.filter { it !in hiddenSet }.toSet()
        val div = remember(current, ids) { dividendData(current, today, ids) }
        val all = div.received + div.planned
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppFilterChip(TodayFilter.allShown(accountIds, hiddenSet), "전체") { hidden = TodayFilter.toggleAll(accountIds, hiddenSet).toList() }
                        current.summary.accounts.forEach { a ->
                            AppFilterChip(a.account.id !in hiddenSet, a.account.name) { hidden = TodayFilter.toggle(a.account.id, hiddenSet).toList() }
                        }
                    }
                }
            }
            if (div.isEmpty) {
                item {
                    SectionCard(title = "배당") {
                        Text(
                            "배당 기록이 없습니다. 계좌에서 배당을 기록하면 달·해·종목별로 모아 보고, 한투 연결이 있으면 배당 일정도 받아 옵니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@LazyColumn
            }
            item {
                val ym = month?.let(YearMonth::parse)
                if (ym == null) {
                    YearGrid(all, year, today, onYear = { year = it }, onMonth = { month = it.toString() })
                } else {
                    MonthCalendar(all.filter { it.month == ym }, ym, today, onMonth = { month = it.toString(); year = it.year }, onBack = { month = null })
                }
            }
            item { YearsCard(all, today) }
            item { YieldsCard(current, div, today, ids) }
        }
    }
}

/** 12칸 달력: 칸마다 그 달 금액 (받은 금액이 많을수록 진하게), 예정이 있는 달은 점선 칸 "확정 ○ · 추정 ○". */
@Composable
private fun YearGrid(items: List<DividendItem>, year: Int, today: LocalDate, onYear: (Int) -> Unit, onMonth: (YearMonth) -> Unit) {
    val months = Dividends.months(items, year)
    val totals = Dividends.totals(items.filter { it.month.year == year })
    val max = maxOf(1.0, months.maxOf { it.received })
    val base = DivColors.received()
    val line = DivColors.estimatedLine()
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onYear(year - 1) }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${year}년", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("받음 ${won(totals.received)} · 예정 ${won(totals.planned)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onYear(year + 1) }) { Text("›", style = MaterialTheme.typography.titleLarge) }
        }
        (0 until 4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until 3).forEach { col ->
                    val m = row * 3 + col + 1
                    val t = months[m - 1]
                    val ym = YearMonth.of(year, m)
                    val isNow = ym == YearMonth.from(today)
                    val shade = (0.08 + 0.32 * (t.received / max)).toFloat()
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 72.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (t.received > 0) base.copy(alpha = shade) else MaterialTheme.colorScheme.surfaceContainer)
                            .then(if (t.planned > 0 && t.received <= 0) Modifier.dashedBorder(line, 14.dp) else Modifier)
                            .then(if (isNow) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(14.dp)) else Modifier)
                            .clickable { onMonth(ym) }
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("${m}월" + if (isNow) " · 이번 달" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(if (t.total > 0) won(t.total).removeSuffix("원") else "-", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (t.planned > 0) {
                            Text(
                                listOfNotNull(
                                    t.confirmed.takeIf { it > 0 }?.let { "확정 ${won(it).removeSuffix("원")}" },
                                    t.estimated.takeIf { it > 0 }?.let { "추정 ${won(it).removeSuffix("원")}" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
        Text(
            "칸이 진할수록 많이 받은 달 · 점선 칸은 예정만 있는 달 · 칸을 누르면 그 달 날짜 달력",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 그 달 날짜 달력: 지급일 칸에 금액 (받음 · 확정), 날짜를 모르는 확정·추정은 아래 따로, 그 아래 그 달 목록. */
@Composable
private fun MonthCalendar(items: List<DividendItem>, ym: YearMonth, today: LocalDate, onMonth: (YearMonth) -> Unit, onBack: () -> Unit) {
    val received = DivColors.received()
    val confirmed = DivColors.confirmed()
    val estBg = DivColors.estimatedBg()
    val estLine = DivColors.estimatedLine()
    val totals = Dividends.totals(items)
    val dated = items.filter { it.kind != DividendKind.ESTIMATED && it.date != null }.groupBy { it.date!! }
    val undated = items.filter { it.kind == DividendKind.ESTIMATED || it.date == null }
    SectionCard {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹ ${ym.year}년 12칸 달력으로") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonth(ym.minusMonths(1)) }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${ym.year}년 ${ym.monthValue}월", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        totals.received.takeIf { it > 0 }?.let { "받음 ${won(it)}" },
                        totals.planned.takeIf { it > 0 }?.let {
                            "예정 ${won(it)} (" + listOfNotNull(
                                totals.confirmed.takeIf { v -> v > 0 }?.let { v -> "확정 ${won(v)}" },
                                totals.estimated.takeIf { v -> v > 0 }?.let { v -> "추정 ${won(v)}" },
                            ).joinToString(" · ") + ")"
                        },
                    ).joinToString(" · ").ifEmpty { "배당 없음" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            IconButton(onClick = { onMonth(ym.plusMonths(1)) }) { Text("›", style = MaterialTheme.typography.titleLarge) }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val lead = ym.atDay(1).dayOfWeek.value % 7 // 일요일 = 0
        val cells = List(lead) { null } + (1..ym.lengthOfMonth()).map { ym.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (0 until 7).forEach { i ->
                    val day = week.getOrNull(i)
                    val list = day?.let { dated[it] }.orEmpty()
                    val kindReceived = list.any { it.kind == DividendKind.RECEIVED }
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    list.isEmpty() -> Color.Transparent
                                    kindReceived -> received.copy(alpha = 0.18f)
                                    else -> confirmed.copy(alpha = 0.28f)
                                },
                            )
                            .then(if (day == today) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(10.dp)) else Modifier)
                            .padding(top = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (day != null) {
                            Text(
                                "${day.dayOfMonth}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (list.isNotEmpty()) FontWeight.Bold else null,
                                color = when (day.dayOfWeek) {
                                    DayOfWeek.SUNDAY -> profitColor(1.0)
                                    DayOfWeek.SATURDAY -> profitColor(-1.0)
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (list.isNotEmpty()) Text(won(list.sumOf { it.amountKrw }).removeSuffix("원"), fontSize = 9.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        undated.forEach { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (d.kind == DividendKind.ESTIMATED) estBg else confirmed.copy(alpha = 0.2f))
                    .then(if (d.kind == DividendKind.ESTIMATED) Modifier.dashedBorder(estLine, 12.dp) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (d.kind == DividendKind.ESTIMATED) "추정 · 약 ${d.date?.monthValue}/${d.date?.dayOfMonth}" else "확정 · 지급일 모름",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (d.kind == DividendKind.ESTIMATED) estLine else MaterialTheme.colorScheme.onSurface,
                )
                Text(d.holding.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((if (d.kind == DividendKind.ESTIMATED) "약 " else "") + won(d.amountKrw), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
        }
        if (items.isNotEmpty()) {
            Text("${ym.monthValue}월 목록", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            items.sortedWith(compareBy({ it.date ?: ym.atEndOfMonth() }, { it.holding.name })).forEach { d ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.date?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: "날짜 모름", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(58.dp))
                    Text(d.holding.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    KindTag(d.kind)
                    Text(won(d.amountKrw), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun KindTag(kind: DividendKind) {
    val (text, bg, fg) = when (kind) {
        DividendKind.RECEIVED -> Triple("받음", MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.onSurfaceVariant)
        DividendKind.CONFIRMED -> Triple("확정 · 한투", DivColors.confirmed().copy(alpha = 0.25f), MaterialTheme.colorScheme.onSurface)
        DividendKind.ESTIMATED -> Triple("추정", DivColors.estimatedBg(), DivColors.estimatedLine())
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .then(if (kind == DividendKind.ESTIMATED) Modifier.dashedBorder(DivColors.estimatedLine(), 4.dp) else Modifier)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 연도별 배당: 해마다 받은 배당 (올해는 받음 · 확정 · 추정), 올해 요약 한 줄. */
@Composable
private fun YearsCard(items: List<DividendItem>, today: LocalDate) {
    val years = Dividends.years(items, today)
    val max = maxOf(1.0, years.maxOfOrNull { it.second.total } ?: 1.0)
    val received = DivColors.received()
    val confirmed = DivColors.confirmed()
    val estBg = DivColors.estimatedBg()
    val estLine = DivColors.estimatedLine()
    SectionCard(title = "연도별 배당") {
        Text("세후 · 받은 배당은 받은 날 환율, 예정은 지금 환율", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        years.reversed().forEach { (y, t) ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$y", style = MaterialTheme.typography.bodyMedium, fontWeight = if (y == today.year) FontWeight.Bold else null, modifier = Modifier.width(44.dp))
                Row(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    val parts = listOf(t.received to received, t.confirmed to confirmed, t.estimated to estBg)
                    var used = 0f
                    parts.forEach { (v, c) ->
                        val frac = (v / max).toFloat().coerceIn(0f, 1f - used)
                        if (frac > 0.001f) {
                            used += frac
                            Box(Modifier.weight(frac).height(14.dp).background(c).then(if (c == estBg) Modifier.dashedBorder(estLine, 2.dp) else Modifier))
                        }
                    }
                    if (used < 0.999f) Spacer(Modifier.weight(1f - used))
                }
                Text(won(t.total), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(76.dp))
            }
        }
        val now = years.firstOrNull { it.first == today.year }?.second
        val last = years.firstOrNull { it.first == today.year - 1 }?.second
        if (now != null) {
            val change = last?.let { Dividends.change(now.total, it.received) }
            Text(
                "올해 ${won(now.received)} 받음 + 예정 ${won(now.planned)}" +
                    (change?.let { " · 작년보다 ${MoneyFormat.rate(it)}" } ?: "") + " · 월평균 ${won(now.total / 12)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 종목별 (최근 12개월): 받은 배당 순, 배당수익률 현재가 기준 · 내 평단 기준, 맨 위 전체 두 가지. */
@Composable
private fun YieldsCard(data: PortfolioData, div: DividendData, today: LocalDate, ids: Set<String>?) {
    val report = remember(data, div, ids) { Dividends.yields(data.summary, div.received, today, ids) }
    SectionCard(title = "종목별 (최근 12개월)") {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YieldBox("배당수익률 · 현재가", pct(report.totalOnPrice), Modifier.weight(1f), highlight = false)
            YieldBox("배당수익률 · 내 평단", pct(report.totalOnCost), Modifier.weight(1f), highlight = true)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            listOf("종목" to 1f, "받은 배당" to 0f, "현재가" to 0f, "내 평단" to 0f).forEach { (label, w) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (w > 0f) TextAlign.Start else TextAlign.End,
                    modifier = if (w > 0f) Modifier.weight(1f) else Modifier.width(if (label == "받은 배당") 70.dp else 56.dp),
                )
            }
        }
        if (report.items.isEmpty()) {
            Text("최근 12개월에 받은 배당이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        report.items.forEach { y ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(y.holding.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(won(y.last12Krw), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
                Text(pct(y.yieldOnPrice), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                Text(pct(y.yieldOnCost), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
            }
        }
        Text(
            "현재가 = 최근 12개월 받은 배당 ÷ 지금 평가금 · 내 평단 = ÷ 매수 원금(보유 수량 × 평균단가) · 세후, 같은 환율로 계산 · 다 판 종목은 \"-\"",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun YieldBox(label: String, value: String, modifier: Modifier, highlight: Boolean) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .padding(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}
