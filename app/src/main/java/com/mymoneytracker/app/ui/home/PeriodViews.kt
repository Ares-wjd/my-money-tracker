package com.mymoneytracker.app.ui.home

import androidx.compose.foundation.background
import com.mymoneytracker.core.portfolio.BenchmarkIndex
import com.mymoneytracker.core.portfolio.Benchmark
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.AppFilterChip
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.chart.StoredChart
import com.mymoneytracker.core.portfolio.PeriodResult
import com.mymoneytracker.core.portfolio.PeriodReturns
import com.mymoneytracker.core.portfolio.TodayFilter
import java.time.LocalDate
import kotlin.math.abs

/** "올해 ▲8.20% (+234.0만원) · 연평균 ▲11.40%" ([ids] 계좌, null 이면 전체). 그래프 일별 값을 계산 중이거나 기록이 없으면 빈 글. */
fun periodLine(data: PortfolioData, stored: Map<String, StoredChart>?, ids: Set<String>?): String {
    stored ?: return ""
    val today = LocalDate.now()
    val series = PeriodReturns.forAccounts(data.summary, data.records, stored, today, ids) ?: return ""
    if (series.isEmpty()) return ""
    return PeriodReturns.summaryText(PeriodReturns.yearToDate(series, today), PeriodReturns.longTerm(series, today))
}

private fun monthTitle(r: PeriodResult) = "${r.from.year}년 ${r.from.monthValue}월"

private fun signedShort(v: Double) = (if (v > 0.5) "+" else "") + MoneyFormat.shortWon(v)

/** 기간별 수익 화면: 계좌 칩 · 올해·연평균 · 최근 12개월 월별 막대 · 연도별 표 (웹 PeriodPage 와 같은 내용). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PeriodScreen(viewModel: PortfolioViewModel, onBack: () -> Unit) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val stored by viewModel.storedCharts.collectAsStateWithLifecycle()
    var hidden by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val dotIndex by viewModel.periodBenchmark.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle("기간별 수익") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        val current = data
        if (current == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val accounts = current.summary.accounts.map { it.account }
        val accountIds = accounts.map { it.id }
        val hiddenSet = hidden.toSet()
        val ids = if (hiddenSet.isEmpty()) null else accountIds.filter { it !in hiddenSet }.toSet()
        val today = LocalDate.now()
        val series = remember(current, stored, ids) { stored?.let { PeriodReturns.forAccounts(current.summary, current.records, it, today, ids) } }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppFilterChip(
                            selected = TodayFilter.allShown(accountIds, hiddenSet),
                            label = "전체",
                            onClick = { hidden = TodayFilter.toggleAll(accountIds, hiddenSet).toList() },
                        )
                        accounts.forEach { a ->
                            AppFilterChip(selected = a.id !in hiddenSet, label = a.name, onClick = { hidden = TodayFilter.toggle(a.id, hiddenSet).toList() })
                        }
                    }
                }
            }
            if (series == null || series.isEmpty()) {
                item {
                    SectionCard(title = "기간별 수익") {
                        Text(
                            when {
                                series == null -> "그래프 일별 값을 계산하는 중입니다. 계산이 끝나면 보여드립니다."
                                hiddenSet.size == accountIds.size -> "계좌를 고르세요."
                                else -> "기록이 없습니다."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@LazyColumn
            }
            val ytd = PeriodReturns.yearToDate(series, today)
            val long = PeriodReturns.longTerm(series, today)
            val months = PeriodReturns.months(series, today)
            val years = PeriodReturns.years(series, today)
            val market = current.market
            val sims = BenchmarkIndex.entries.associateWith { Benchmark.simulate(series, it, market, today) }
            fun indexRate(index: BenchmarkIndex, r: PeriodResult?): Double? = r?.let { Benchmark.period(sims[index], index, market, it.from, it.to)?.rate }
            item {
                SectionCard(title = "기간별 수익") {
                    Text(
                        "배당 포함 · 그래프 일별 평가금·투자금으로 계산 (넣고 뺀 돈은 수익에서 뺌)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LabeledValue("올해 수익", ytd?.let { MoneyFormat.signedWon(it.profitKrw) } ?: "-", profitColor(ytd?.profitKrw), bold = true)
                    LabeledValue("올해 수익률", MoneyFormat.rate(ytd?.rate), profitColor(ytd?.rate))
                    if (long?.annual != null) {
                        LabeledValue("연평균 수익률 (XIRR)", MoneyFormat.rate(long.annual), profitColor(long.annual), bold = true)
                    } else {
                        LabeledValue(
                            "기간 수익률" + (long?.let { " (${PeriodReturns.lengthText(it.days)})" } ?: ""),
                            MoneyFormat.rate(long?.period),
                            profitColor(long?.period),
                            bold = true,
                        )
                        Text(
                            "1년이 안 되어 연으로 바꾸지 않습니다.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                YtdCompareCard(ytd, BenchmarkIndex.entries.map { it to indexRate(it, ytd) })
            }
            item {
                SectionCard(title = "월별 수익 (최근 12개월)") {
                    // 점 = 그 달 내 수익률이 지수와 같았다면의 수익금 (막대와 같은 금액 눈금)
                    val dots = months.map { m -> dotIndex?.let { i -> indexRate(i, m)?.takeIf { m.rate != null }?.let { it * m.baseKrw } } }
                    val max = maxOf(1.0, months.maxOfOrNull { abs(it.profitKrw) } ?: 1.0, dots.maxOfOrNull { abs(it ?: 0.0) } ?: 0.0)
                    val selected = months.firstOrNull { it.from.toString() == picked } ?: months.lastOrNull()
                    val dotColor = dotIndex?.let { benchmarkColor(it) } ?: Color.Transparent
                    Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        months.forEachIndexed { k, m ->
                            MonthBar(m, max, m == selected, Modifier.weight(1f), dots[k], dotColor) { picked = m.from.toString() }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BenchmarkIndex.entries.forEach { i ->
                            AppFilterChip(selected = dotIndex == i, label = "● ${i.label}", onClick = { viewModel.setPeriodBenchmark(i) })
                        }
                        AppFilterChip(selected = dotIndex == null, label = "끄기", onClick = { viewModel.setPeriodBenchmark(null) })
                    }
                    if (dotIndex != null) {
                        Text(
                            "점: 그 달 수익률이 ${dotIndex?.label}와 같았다면의 수익금",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    selected?.let { s ->
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Text(monthTitle(s) + if (s.to == today) " (오늘까지)" else "", style = MaterialTheme.typography.titleSmall)
                        LabeledValue("수익금", MoneyFormat.signedWon(s.profitKrw), profitColor(s.profitKrw))
                        LabeledValue("수익률", MoneyFormat.rate(s.rate), profitColor(s.rate))
                        LabeledValue("넣은 돈 (뺀 돈은 −)", MoneyFormat.signedWon(s.netFlowKrw))
                        indexLine(BenchmarkIndex.entries.map { it to indexRate(it, s) })?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                SectionCard(title = "연도별 수익") {
                    YearRow("연도", "수익금", "수익률", "넣은 돈", header = true)
                    years.reversed().forEach { y ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        YearRow(
                            "${y.from.year}" + if (y.to == today) " (올해)" else "",
                            signedShort(y.profitKrw),
                            MoneyFormat.rate(y.rate),
                            signedShort(y.netFlowKrw),
                            profitSign = y.profitKrw,
                            rateSign = y.rate,
                        )
                        indexLine(BenchmarkIndex.entries.map { it to indexRate(it, y) })?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "S&P500 ▲12.10% · 나스닥 ▲15.00% · KOSPI ▲5.30%" (지수 시세가 그 기간을 덮지 못하면 "-"). 모두 없으면 null. */
private fun indexLine(rates: List<Pair<BenchmarkIndex, Double?>>): String? =
    if (rates.all { it.second == null }) null else rates.joinToString(" · ") { (i, r) -> "${i.label} ${MoneyFormat.rate(r)}" }

/** 올해 수익률 비교: 내 자산 · 지수 막대와 비교 한 줄 (v2.11). */
@Composable
private fun YtdCompareCard(ytd: PeriodResult?, indices: List<Pair<BenchmarkIndex, Double?>>) {
    if (ytd == null || indices.all { it.second == null }) {
        SectionCard(title = "올해 수익률 비교") {
            Text(
                "지수 시세가 아직 없습니다. 한투 연결이 있는 폰이 과거 시세와 함께 받아 둡니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val rows = listOf(Triple("내 자산", ytd.rate, chartMineColor())) + indices.map { (i, r) -> Triple(i.label, r, benchmarkColor(i)) }
    val max = maxOf(1e-9, rows.maxOf { abs(it.second ?: 0.0) })
    SectionCard(title = "올해 수익률 비교") {
        rows.forEachIndexed { k, (label, rate, color) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (k == 0) FontWeight.SemiBold else null, modifier = Modifier.width(64.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    val frac = (abs(rate ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
                    if (frac > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(4.dp)).background(color))
                }
                Text(
                    MoneyFormat.rate(rate),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (k == 0) FontWeight.SemiBold else null,
                    color = profitColor(rate),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
        Benchmark.compareText(ytd.rate, indices)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        Text(
            "같은 날 같은 돈을 넣고 뺐다고 보고 같은 방식(수정 디츠)으로 계산 · S&P500·나스닥은 원화 환산 · 지수는 배당 제외 · 막대 길이는 크기만",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun chartMineColor(): Color = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF27957A) else Color(0xFF007A5E)

/** 월별 막대 하나: 가운데 선 위는 수익(빨강), 아래는 손실(파랑). [dot] 고른 지수의 점 (같은 금액 눈금). 누르면 그 달을 고른다. */
@Composable
private fun MonthBar(
    m: PeriodResult,
    max: Double,
    selected: Boolean,
    modifier: Modifier,
    dot: Double? = null,
    dotColor: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    val frac = (abs(m.profitKrw) / max).toFloat().coerceIn(0f, 1f).let { if (it > 0f) maxOf(it, 0.02f) else 0f }
    val dotFrac = dot?.let { (it / max).toFloat().coerceIn(-1f, 1f) }
    val ring = MaterialTheme.colorScheme.surface
    Column(modifier.fillMaxHeight().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp)) else Modifier),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    if (m.profitKrw > 0 && frac > 0f) Box(Modifier.fillMaxWidth(0.64f).fillMaxHeight(frac).background(profitColor(1.0)))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    if (m.profitKrw < 0 && frac > 0f) Box(Modifier.fillMaxWidth(0.64f).fillMaxHeight(frac).background(profitColor(-1.0)))
                }
            }
            if (dotFrac != null) {
                // 고른 지수의 점: 가운데 선에서 같은 금액 눈금만큼
                Canvas(Modifier.matchParentSize()) {
                    val center = Offset(size.width / 2, size.height / 2 - dotFrac * (size.height / 2 - 4.dp.toPx()))
                    drawCircle(ring, radius = 5.dp.toPx(), center = center)
                    drawCircle(dotColor, radius = 3.5.dp.toPx(), center = center)
                }
            }
        }
        Text(
            "${m.from.monthValue}월",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun YearRow(
    year: String,
    profit: String,
    rate: String,
    flow: String,
    header: Boolean = false,
    profitSign: Double? = null,
    rateSign: Double? = null,
) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(year, style = style, color = if (header) muted else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1.1f))
        Text(profit, style = style, fontWeight = if (header) null else FontWeight.SemiBold, color = if (header) muted else profitColor(profitSign), textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(rate, style = style, color = if (header) muted else profitColor(rateSign), textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(flow, style = style, color = muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
