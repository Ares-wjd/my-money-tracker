package com.mymoneytracker.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.chart.ChartPoint
import com.mymoneytracker.core.chart.StoredChart
import com.mymoneytracker.core.portfolio.Benchmark
import com.mymoneytracker.core.portfolio.BenchmarkIndex
import com.mymoneytracker.core.portfolio.FlowPoint
import com.mymoneytracker.core.portfolio.PeriodReturns
import java.time.LocalDate

/** 그래프에 겹쳐 그릴 지수 선 하나: 그래프 점마다 가상 평가금, 오늘 기준 값과 차이 (v2.11, 웹 BenchmarkLine 과 같은 내용). */
data class BenchmarkLine(val index: BenchmarkIndex, val values: List<Double?>, val today: Benchmark.Today?)

/** 고른 계좌(null 이면 전체)의 일별 평가금·투자금. 그래프 일별 값을 계산 중이면 null. */
fun flowSeries(data: PortfolioData, stored: Map<String, StoredChart>?, accountId: String?, today: LocalDate): List<FlowPoint>? =
    stored?.let { PeriodReturns.forAccounts(data.summary, data.records, it, today, accountId?.let(::setOf)) }

/** 고른 지수들의 선 (지수 시세가 없는 지수는 빠진다). */
fun benchmarkLines(
    data: PortfolioData,
    series: List<FlowPoint>?,
    points: List<ChartPoint>,
    chosen: Set<BenchmarkIndex>,
    today: LocalDate,
): List<BenchmarkLine> {
    if (series.isNullOrEmpty() || points.size < 2) return emptyList()
    return BenchmarkIndex.entries.filter { it in chosen }.mapNotNull { index ->
        val sim = Benchmark.simulate(series, index, data.market, today) ?: return@mapNotNull null
        BenchmarkLine(index, Benchmark.valuesAt(sim, points.map { it.date }), Benchmark.today(series, sim, index))
    }
}

/** 지수 색 (웹과 같은 값): S&P500 주황, 나스닥 자주, KOSPI 보라. 다크 모드는 밝게. */
@Composable
fun benchmarkColor(index: BenchmarkIndex): Color {
    val dark = isSystemInDarkTheme()
    return when (index) {
        BenchmarkIndex.SPX -> if (dark) Color(0xFFE8A15A) else Color(0xFFD9822B)
        BenchmarkIndex.COMP -> if (dark) Color(0xFFE07AA6) else Color(0xFFC2417A)
        BenchmarkIndex.KOSPI -> if (dark) Color(0xFF9A8EF0) else Color(0xFF6A5ACD)
    }
}

/** 지수마다 다른 선 모양 (색만으로 구분하지 않도록): S&P500 점선, 나스닥 긴 점선, KOSPI 1점 쇄선. */
fun benchmarkDash(index: BenchmarkIndex, density: Density): PathEffect = with(density) {
    when (index) {
        BenchmarkIndex.SPX -> PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), 3.5.dp.toPx()))
        BenchmarkIndex.COMP -> PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 2.dp.toPx()))
        BenchmarkIndex.KOSPI -> PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 3.dp.toPx(), 2.dp.toPx(), 3.dp.toPx()))
    }
}

@Composable
private fun DashSample(index: BenchmarkIndex) {
    val color = benchmarkColor(index)
    Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
        drawLine(
            color,
            Offset(1f, size.height / 2),
            Offset(size.width - 1f, size.height / 2),
            strokeWidth = 2.5.dp.toPx(),
            cap = if (index == BenchmarkIndex.SPX) StrokeCap.Round else StrokeCap.Butt,
            pathEffect = benchmarkDash(index, this),
        )
    }
}

/** 그래프 아래 "지수와 비교": 지수 칩, 오늘 기준 지수마다 한 줄. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BenchmarkSection(
    chosen: Set<BenchmarkIndex>,
    lines: List<BenchmarkLine>,
    computing: Boolean,
    onToggle: (BenchmarkIndex) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("지수와 비교", style = MaterialTheme.typography.titleSmall)
            Text(" · 같은 날 같은 돈을 넣었다면", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BenchmarkIndex.entries.forEach { index ->
                FilterChip(
                    selected = index in chosen,
                    onClick = { onToggle(index) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DashSample(index)
                            Spacer(Modifier.width(6.dp))
                            Text(index.label)
                        }
                    },
                    leadingIcon = if (index in chosen) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
        chosen.sortedBy { it.ordinal }.forEach { index ->
            val today = lines.firstOrNull { it.index == index }?.today
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index.label}에 넣었다면", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.weight(1f))
                if (today == null) {
                    Text(if (computing) "계산 중" else "지수 시세 없음", style = MaterialTheme.typography.bodySmall, color = muted)
                } else {
                    Text(MoneyFormat.won(today.valueKrw), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "내가 " + signedShortWon(today.diffKrw),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = profitColor(today.diffKrw),
                    )
                }
            }
        }
        if (chosen.isNotEmpty()) {
            Text(
                if (chosen.any { i -> lines.none { it.index == i } } && !computing) {
                    "지수 시세는 한투 연결이 있는 폰이 받아 둡니다 · S&P500·나스닥은 그날 환율로 원화 환산 · 지수는 배당 제외"
                } else {
                    "S&P500·나스닥은 그날 환율로 원화 환산 · 지수는 배당 제외"
                },
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
    }
}

/** "+420만원" / "-290만원" (짧은 금액, 0.5원보다 크면 +). */
fun signedShortWon(v: Double): String = (if (v > 0.5) "+" else "") + MoneyFormat.shortWon(v)
