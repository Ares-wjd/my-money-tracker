package com.mymoneytracker.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mymoneytracker.app.ui.ChartState
import com.mymoneytracker.app.ui.common.ChipSelector
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.SegmentedPills
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.chart.ChartInterval
import com.mymoneytracker.core.chart.ChartPoint
import com.mymoneytracker.core.model.InvestmentAccount

/** 자산 추이 그래프: 평가금(실선)과 투자금(점선). 그래프를 누르거나 끌면 그 날짜의 값을 보여준다. */
@Composable
fun AssetChartCard(
    state: ChartState,
    accounts: List<InvestmentAccount>,
    onSelectInterval: (ChartInterval) -> Unit,
    onSelectAccount: (String?) -> Unit,
    onShowEarlier: () -> Unit,
) {
    SectionCard(title = "자산 추이") {
        SegmentedPills(ChartInterval.entries, state.selection.interval, { it.label }, onSelectInterval)
        if (accounts.size > 1) {
            ChipSelector(
                "계좌",
                listOf<String?>(null) + accounts.map { it.id },
                state.selection.accountId,
                { id -> if (id == null) "전체" else accounts.firstOrNull { it.id == id }?.name.orEmpty() },
                onSelectAccount,
            )
        }
        if (state.points.size < 2) {
            Text(
                if (state.loading) "과거 시세를 받는 중입니다..." else "기록이 쌓이면 그래프가 표시됩니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LineChart(state.points)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(MaterialTheme.colorScheme.primary, "평가금")
            Legend(MaterialTheme.colorScheme.outline, "투자금")
            if (state.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        if (state.hasEarlier) {
            TextButton(onClick = onShowEarlier) { Text("이전 기간 더 보기") }
        }
        state.errors.take(3).forEach { WarningText(it) }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp).clip(CircleShape)) { drawCircle(color) }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LineChart(points: List<ChartPoint>) {
    var selected by remember(points) { mutableStateOf(points.lastIndex) }
    val valueColor = MaterialTheme.colorScheme.primary
    val investedColor = MaterialTheme.colorScheme.outline
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val markerColor = MaterialTheme.colorScheme.onSurface

    val maxValue = points.maxOf { maxOf(it.valueKrw, it.investedKrw) }
    val minValue = points.minOf { minOf(it.valueKrw, it.investedKrw) }
    val padding = ((maxValue - minValue) * 0.1).coerceAtLeast(1.0)
    val top = maxValue + padding
    val bottom = (minValue - padding).coerceAtLeast(0.0)

    Column {
        val point = points[selected.coerceIn(0, points.lastIndex)]
        Text(formatDate(point.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LabeledValue("평가금", MoneyFormat.won(point.valueKrw), bold = true)
        LabeledValue("투자금", MoneyFormat.won(point.investedKrw))
        LabeledValue(
            "수익금",
            MoneyFormat.signedWon(point.profitKrw) +
                (if (point.investedKrw > 0) " (${MoneyFormat.percent(point.profitKrw / point.investedKrw)})" else ""),
            valueColor = profitColor(point.profitKrw),
        )
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(points) {
                    detectTapGestures { offset -> selected = indexAt(offset.x, size.width.toFloat(), points.size) }
                }
                .pointerInput(points) {
                    detectDragGestures { change, _ ->
                        selected = indexAt(change.position.x, size.width.toFloat(), points.size)
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            fun x(i: Int) = if (points.size == 1) w / 2 else w * i / (points.size - 1)
            fun y(v: Double) = (h - (v - bottom) / (top - bottom) * h).toFloat()

            for (k in 0..3) {
                val gy = h * k / 3
                drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            }
            val investedPath = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), y(p.investedKrw)) else lineTo(x(i), y(p.investedKrw)) }
            }
            drawPath(investedPath, investedColor, style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
            val valuePath = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), y(p.valueKrw)) else lineTo(x(i), y(p.valueKrw)) }
            }
            drawPath(valuePath, valueColor, style = Stroke(width = 6f))

            val si = selected.coerceIn(0, points.lastIndex)
            drawLine(markerColor.copy(alpha = 0.4f), Offset(x(si), 0f), Offset(x(si), h), strokeWidth = 2f)
            drawCircle(valueColor, radius = 9f, center = Offset(x(si), y(points[si].valueKrw)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDate(points.first().date), style = MaterialTheme.typography.labelSmall)
            Text(formatDate(points.last().date), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun indexAt(x: Float, width: Float, count: Int): Int {
    if (count <= 1 || width <= 0f) return 0
    return ((x / width) * (count - 1)).let { kotlin.math.round(it).toInt() }.coerceIn(0, count - 1)
}
