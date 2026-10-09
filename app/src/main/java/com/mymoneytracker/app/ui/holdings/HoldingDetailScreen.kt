package com.mymoneytracker.app.ui.holdings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.DateField
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.ListRow
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.app.ui.records.recordAmountText
import com.mymoneytracker.app.ui.records.recordDetailText
import com.mymoneytracker.app.ui.records.recordTitle
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.HoldingValuation
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingDetailScreen(
    viewModel: PortfolioViewModel,
    holdingId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddRecord: (RecordType) -> Unit,
    onOpenRecord: (String) -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val current = data
    val holding = current?.holding(holdingId)
    val valuation = holding?.let { h ->
        current.accountSummary(h.accountId)?.holdings?.firstOrNull { it.holding.id == holdingId }
    }
    var editPrice by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(holding?.name ?: "종목") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "종목 편집") }
                },
            )
        },
    ) { padding ->
        if (current == null || holding == null || valuation == null) {
            LoadingBox(Modifier.padding(padding))
        } else {
            HoldingDetailContent(
                data = current,
                valuation = valuation,
                padding = padding,
                onEditPrice = { editPrice = true },
                onAddRecord = onAddRecord,
                onOpenRecord = onOpenRecord,
            )
            if (editPrice) {
                PriceDialog(
                    holding = holding,
                    onDismiss = { editPrice = false },
                    onSave = { price, date ->
                        viewModel.saveHolding(holding.copy(manualPrice = price, manualPriceDate = date))
                        editPrice = false
                    },
                )
            }
        }
    }
}

@Composable
private fun HoldingDetailContent(
    data: PortfolioData,
    valuation: HoldingValuation,
    padding: PaddingValues,
    onEditPrice: () -> Unit,
    onAddRecord: (RecordType) -> Unit,
    onOpenRecord: (String) -> Unit,
) {
    val holding = valuation.holding
    val position = valuation.position
    val currency = holding.currency
    val records = data.recordsOfHolding(holding.id)

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                listOfNotNull(holding.market.label, holding.assetType.label, holding.code.ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SectionCard(highlighted = true) {
                LabeledValue(
                    "평가금",
                    valuation.marketValue?.let { MoneyFormat.amount(currency, it) } ?: "가격 입력 필요",
                    bold = true,
                )
                if (currency != Currency.KRW) {
                    LabeledValue("원화 환산", valuation.marketValueKrw?.let { MoneyFormat.won(it) } ?: "환율 필요")
                }
                LabeledValue(
                    "평가손익",
                    valuation.unrealizedProfit?.let { MoneyFormat.signedAmount(currency, it) } ?: "-",
                    valueColor = profitColor(valuation.unrealizedProfit),
                )
                LabeledValue("수익률 (배당 미포함)", MoneyFormat.percent(valuation.returnRate), valueColor = profitColor(valuation.returnRate))
                LabeledValue(
                    "수익률 (배당 포함)",
                    MoneyFormat.percent(valuation.returnRateWithDividends),
                    valueColor = profitColor(valuation.returnRateWithDividends),
                )
            }
        }
        item {
            SectionCard {
                LabeledValue("보유 수량", MoneyFormat.decimal(position.quantity))
                LabeledValue("평균단가", MoneyFormat.amount(currency, position.averagePrice))
                LabeledValue("매입금액", MoneyFormat.amount(currency, position.costBasis))
                LabeledValue(
                    "현재가",
                    valuation.price?.let { p ->
                        MoneyFormat.amount(currency, p.price) + (p.date?.let { " (${formatDate(it)})" } ?: "")
                    } ?: "없음",
                )
                TextButton(onClick = onEditPrice) { Text("현재가 직접 입력") }
                LabeledValue("누적 배당", MoneyFormat.amount(currency, position.dividends))
                LabeledValue("실현손익", MoneyFormat.signedAmount(currency, position.realizedProfit), valueColor = profitColor(position.realizedProfit))
                LabeledValue("수수료·세금 합계", MoneyFormat.amount(currency, position.feesAndTaxes))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAddRecord(RecordType.BUY) }, modifier = Modifier.weight(1f)) { Text("매수") }
                OutlinedButton(onClick = { onAddRecord(RecordType.SELL) }, modifier = Modifier.weight(1f)) { Text("매도") }
                OutlinedButton(onClick = { onAddRecord(RecordType.DIVIDEND) }, modifier = Modifier.weight(1f)) { Text("배당") }
            }
        }
        item { Text("기록", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(records, key = { it.id }) { record ->
            ListRow(
                title = recordTitle(record, data, holding.accountId),
                subtitle = listOfNotNull(formatDate(record.date), recordDetailText(record, data)).joinToString(" · "),
                value = recordAmountText(record, data, holding.accountId),
                onClick = { onOpenRecord(record.id) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PriceDialog(
    holding: Holding,
    onDismiss: () -> Unit,
    onSave: (Double?, LocalDate?) -> Unit,
) {
    var priceText by rememberSaveable { mutableStateOf(holding.manualPrice?.let { MoneyFormat.decimal(it, 6).replace(",", "") }.orEmpty()) }
    var day by rememberSaveable { mutableLongStateOf((holding.manualPriceDate ?: LocalDate.now()).toEpochDay()) }
    val price = MoneyFormat.parseDecimal(priceText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("현재가 직접 입력") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "펀드 기준가처럼 자동으로 받을 수 없는 가격을 입력합니다. 비우고 저장하면 가격이 지워집니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumberField(
                    "현재가 (${holding.currency.label})",
                    priceText,
                    { priceText = it },
                    preview = { MoneyFormat.amount(holding.currency, it) },
                )
                DateField("기준일", LocalDate.ofEpochDay(day), { day = it.toEpochDay() })
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (priceText.isBlank()) onSave(null, null) else onSave(price, LocalDate.ofEpochDay(day))
                },
                enabled = priceText.isBlank() || (price != null && price >= 0),
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
