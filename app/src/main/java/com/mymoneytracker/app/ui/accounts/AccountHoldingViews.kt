package com.mymoneytracker.app.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.SegmentedPills
import com.mymoneytracker.app.ui.common.TickerBadge
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.portfolio.AccountSummary
import com.mymoneytracker.core.portfolio.DisplayConversion
import com.mymoneytracker.core.portfolio.DisplayCurrency
import com.mymoneytracker.core.portfolio.HoldingSort
import com.mymoneytracker.core.portfolio.HoldingValuation

// 계좌 상세의 보유 종목: 제목 줄 · 정렬·원화/외화 줄 · 종목 목록 (매도 완료 접기).

/** "보유 종목" 제목과 [종목 추가]. */
@Composable
internal fun HoldingsHeader(onAddHolding: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("보유 종목", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onAddHolding) { Text("종목 추가") }
    }
}

/** 정렬 + 원화/외화를 한 줄로, 티커 색 범례는 ⓘ 를 눌러 본다. */
@Composable
internal fun HoldingsToolbar(
    holdingSort: HoldingSort,
    onSelectHoldingSort: (HoldingSort) -> Unit,
    hasUsd: Boolean,
    displayCurrency: DisplayCurrency,
    onSelectDisplayCurrency: (DisplayCurrency) -> Unit,
    showLegend: Boolean,
    onToggleLegend: () -> Unit,
) {
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
        IconButton(onClick = onToggleLegend, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = if (showLegend) "티커 색 설명 닫기" else "티커 색 설명")
        }
    }
}

/** 보유 종목 목록. 0주 종목(다 판 종목)은 맨 아래 "매도 완료 종목" 안에 접어 둔다. [rate] 는 보기에 따른 총 수익률. */
@Composable
internal fun HoldingsCard(
    holdings: List<HoldingValuation>,
    soldOut: List<HoldingValuation>,
    showSoldOut: Boolean,
    onToggleSoldOut: () -> Unit,
    data: PortfolioData,
    summary: AccountSummary,
    displayCurrency: DisplayCurrency,
    rate: (HoldingValuation) -> Double?,
    onOpenHolding: (String) -> Unit,
) {
    SectionCard {
        holdings.forEachIndexed { index, valuation ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            HoldingRow(valuation, data, summary, displayCurrency, rate(valuation)) { onOpenHolding(valuation.holding.id) }
        }
        if (soldOut.isNotEmpty()) {
            if (holdings.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = onToggleSoldOut) {
                Text("매도 완료 종목 (${soldOut.size}) " + if (showSoldOut) "▴" else "▾")
            }
            if (showSoldOut) {
                soldOut.forEach { valuation ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HoldingRow(valuation, data, summary, displayCurrency, rate(valuation)) { onOpenHolding(valuation.holding.id) }
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
