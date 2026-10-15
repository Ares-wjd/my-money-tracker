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
import com.mymoneytracker.app.ui.accounts.HoldingCurrencyToggle
import com.mymoneytracker.app.ui.common.TopBarTitle
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
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.DisplayConversion
import com.mymoneytracker.core.portfolio.DisplayCurrency
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
    val apiStatus by viewModel.apiStatus.collectAsStateWithLifecycle()
    val displayCurrency by viewModel.displayCurrency.collectAsStateWithLifecycle()
    val current = data
    val holding = current?.holding(holdingId)
    val valuation = holding?.let { h ->
        current.accountSummary(h.accountId)?.holdings?.firstOrNull { it.holding.id == holdingId }
    }
    var editPrice by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle(holding?.name ?: "종목") },
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
                linked = apiStatus.linkedAccountId == holding.accountId,
                displayCurrency = displayCurrency,
                onSelectDisplayCurrency = viewModel::setDisplayCurrency,
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
    linked: Boolean,
    displayCurrency: DisplayCurrency,
    onSelectDisplayCurrency: (DisplayCurrency) -> Unit,
    onEditPrice: () -> Unit,
    onAddRecord: (RecordType) -> Unit,
    onOpenRecord: (String) -> Unit,
) {
    val holding = valuation.holding
    val position = valuation.position
    val currency = holding.currency
    val records = data.recordsOfHolding(holding.id)
    // 금현물은 g 단위, 가격은 1g당
    val perUnit = if (holding.assetType == AssetType.GOLD) " (1g당)" else ""
    // 원화 보기의 해외 종목: 매매일 환율이 모두 있으면 환차익을 반영하고, 없으면 오늘 환율로 단순 환산한다.
    val conversion = DisplayConversion.of(currency, displayCurrency, data.usdKrw)
    val krw = if (displayCurrency == DisplayCurrency.KRW) data.krwView(valuation) else null
    val missingTradeFx = displayCurrency == DisplayCurrency.KRW && currency == Currency.USD && data.usdKrw != null && krw == null
    val unrealized = krw?.unrealizedProfitKrw ?: valuation.unrealizedProfit?.let { conversion.convert(it) }
    val returnRate = krw?.returnRate ?: valuation.returnRate
    val returnRateWithDividends = krw?.returnRateWithDividends ?: valuation.returnRateWithDividends
    fun shown(value: Double): String = conversion.convert(value)?.let { MoneyFormat.amount(conversion.currency, it) } ?: "환율 필요"
    fun shownSigned(value: Double): String = conversion.convert(value)?.let { MoneyFormat.signedAmount(conversion.currency, it) } ?: "환율 필요"

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
        if (currency == Currency.USD) {
            item { HoldingCurrencyToggle(displayCurrency, data.usdKrw, onSelectDisplayCurrency) }
        }
        item {
            // 초록 바탕 위에서는 빨강·파랑 대신 카드 글자색과 ▲▼ 로 수익/손실을 나타낸다 (글자 대비 확보)
            SectionCard(highlighted = true) {
                LabeledValue(
                    "평가금",
                    valuation.marketValue?.let(::shown) ?: "가격 입력 필요",
                    bold = true,
                )
                if (currency != Currency.KRW && displayCurrency == DisplayCurrency.FOREIGN) {
                    LabeledValue("원화 환산", valuation.marketValueKrw?.let { MoneyFormat.won(it) } ?: "환율 필요")
                } else if (currency != Currency.KRW) {
                    LabeledValue("달러 기준", valuation.marketValue?.let { MoneyFormat.usd(it) } ?: "-")
                }
                LabeledValue(
                    "평가손익",
                    unrealized?.let { MoneyFormat.signedAmount(conversion.currency, it) } ?: "-",
                )
                if (krw != null) {
                    LabeledValue("└ 주가 손익", krw.stockProfitKrw?.let { MoneyFormat.signedWon(it) } ?: "-")
                    LabeledValue("└ 환차익", MoneyFormat.signedWon(krw.fxProfitKrw))
                }
                val rateLabel = if (displayCurrency == DisplayCurrency.KRW && currency == Currency.USD && krw != null) "원화 수익률" else "수익률"
                LabeledValue("$rateLabel (배당 미포함)", MoneyFormat.rate(returnRate))
                LabeledValue(
                    "$rateLabel (배당 포함)",
                    MoneyFormat.rate(returnRateWithDividends),
                )
                data.todayRate(valuation)?.let { today ->
                    LabeledValue("오늘 수익률", MoneyFormat.rate(today))
                }
                if (missingTradeFx) {
                    Text(
                        "매매일 환율이 아직 없어 오늘 환율로 단순 환산했습니다 (한투가 연결되어 있으면 자동으로 받아 옵니다).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            SectionCard {
                LabeledValue("보유 수량", MoneyFormat.decimal(position.quantity) + holding.assetType.unit)
                LabeledValue("평균단가$perUnit", krw?.let { MoneyFormat.won(it.averagePriceKrw) + " (매수일 환율)" } ?: shown(position.averagePrice))
                LabeledValue("매입금액", krw?.let { MoneyFormat.won(it.costBasisKrw) } ?: shown(position.costBasis))
                LabeledValue(
                    "현재가$perUnit",
                    valuation.price?.let { p ->
                        shown(p.price) + (p.date?.let { " (${formatDate(it)})" } ?: "")
                    } ?: "없음",
                )
                TextButton(onClick = onEditPrice) { Text("현재가 직접 입력") }
                LabeledValue("누적 배당", krw?.let { MoneyFormat.won(it.dividendsKrw) } ?: shown(position.dividends))
                val realized = krw?.realizedProfitKrw
                LabeledValue(
                    "실현손익",
                    realized?.let { MoneyFormat.signedWon(it) } ?: shownSigned(position.realizedProfit),
                    valueColor = profitColor(realized ?: position.realizedProfit),
                )
                LabeledValue("수수료·세금 합계", krw?.let { MoneyFormat.won(it.feesAndTaxesKrw) } ?: shown(position.feesAndTaxes))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!linked) {
                    OutlinedButton(onClick = { onAddRecord(RecordType.BUY) }, modifier = Modifier.weight(1f)) { Text("매수") }
                    OutlinedButton(onClick = { onAddRecord(RecordType.SELL) }, modifier = Modifier.weight(1f)) { Text("매도") }
                }
                OutlinedButton(onClick = { onAddRecord(RecordType.DIVIDEND) }, modifier = Modifier.weight(1f)) { Text("배당") }
            }
            if (linked) {
                Text(
                    "한투 연결 계좌의 종목이라 매수·매도는 한투에서 불러옵니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    "현재가 (${holding.currency.label}${if (holding.assetType == AssetType.GOLD) ", 1g당" else ""})",
                    priceText,
                    { priceText = it },
                    preview = { MoneyFormat.amountPreview(holding.currency, it) },
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
