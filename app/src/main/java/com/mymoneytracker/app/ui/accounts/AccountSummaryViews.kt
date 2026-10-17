package com.mymoneytracker.app.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mymoneytracker.app.ui.ApiStatus
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.StatTile
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.app.ui.records.shortSellLabel
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.AccountSummary
import com.mymoneytracker.core.portfolio.CashDetail
import com.mymoneytracker.core.portfolio.PortfolioCalculator

// 계좌 상세 위쪽 카드들: 평가금 · 한투 연결 · 예수금 · 계산되지 않는 매도.

/** 평가금 카드: 투자금·수익금·수익률, 수익률이 없는 이유, 기간 수익 한 줄, 경고. */
@Composable
internal fun AccountSummaryCard(
    summary: AccountSummary,
    allRecords: List<Record>,
    periodText: String,
    onOpenPeriod: () -> Unit,
    onAddRecord: (RecordType) -> Unit,
) {
    val accountId = summary.account.id
    SectionCard {
        // 핵심 3개(투자금·수익금·수익률)를 크게, 나머지는 아래 한 줄로
        Text("평가금", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(MoneyFormat.won(summary.valueKrw), style = MaterialTheme.typography.headlineMedium)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("투자금", MoneyFormat.won(summary.investedKrw), Modifier.weight(1f), shortValue = MoneyFormat.shortWon(summary.investedKrw))
            StatTile(
                "수익금",
                MoneyFormat.signedWon(summary.profitKrw),
                Modifier.weight(1f),
                valueColor = profitColor(summary.profitKrw),
                shortValue = (if (summary.profitKrw > 0) "+" else "") + MoneyFormat.shortWon(summary.profitKrw),
            )
            StatTile("수익률", MoneyFormat.rate(summary.returnRate), Modifier.weight(0.8f), valueColor = profitColor(summary.returnRate))
        }
        if (summary.returnRate == null) {
            // 투자금이 0원 이하면 수익률을 계산할 수 없다 → 이유 (입금 기록이 없을 때만 바로가기)
            val reason = PortfolioCalculator.noRateReason(accountId, summary.investedKrw, allRecords)
            Text(
                "${noRateText(reason)} · 누적 배당 ${MoneyFormat.won(summary.dividendsKrw)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (reason == PortfolioCalculator.NoRateReason.NO_DEPOSIT) {
                TextButton(onClick = { onAddRecord(RecordType.DEPOSIT) }, contentPadding = PaddingValues(0.dp)) { Text("입금 기록하기") }
            }
        } else {
            Text(
                "배당 미포함 ${MoneyFormat.rate(summary.returnRateExDividends)} · 누적 배당 ${MoneyFormat.won(summary.dividendsKrw)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (periodText.isNotEmpty()) {
            Text(
                "$periodText ›",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp).clickable(onClick = onOpenPeriod),
            )
        }
        if (summary.missingFx) WarningText("환율이 없어 달러 금액이 원화 합계에서 빠져 있습니다. 설정에서 환율을 입력하세요.")
        if (summary.missingPrice) WarningText("현재가가 없는 종목이 있습니다. 종목을 눌러 가격을 입력하세요.")
    }
}

/** 한투 연결 계좌: 매매와 예수금은 불러오고, 입출금(투자금)과 배당은 직접 입력한다. */
@Composable
internal fun LinkedAccountCard(
    apiStatus: ApiStatus,
    syncing: Boolean,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    SectionCard(title = "한투 연결 계좌") {
        Text(
            "매수·매도 체결과 예수금은 한투에서 불러옵니다. 입금·출금(투자금)과 배당은 직접 기록하세요." +
                (apiStatus.lastSync?.let { "\n마지막 불러오기 ${formatDate(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onSync, enabled = !syncing) { Text("한투에서 불러오기") }
            if (syncing) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
            TextButton(onClick = onOpenSettings) { Text("연결 설정") }
        }
    }
}

/** 예수금 카드: 한투 연결 계좌는 불러온 값, 직접 입력 계좌는 입력값 + 이후 기록 근거. */
@Composable
internal fun CashCard(summary: AccountSummary, linked: Boolean, onEnterCash: () -> Unit) {
    SectionCard(title = "예수금") {
        val cashDate = summary.cashDate
        LabeledValue(if (linked) "원화 (D+2)" else "원화", MoneyFormat.won(summary.cash.krw))
        if (summary.cash.usd != 0.0 || summary.holdings.any { it.holding.currency == Currency.USD }) {
            LabeledValue("달러", MoneyFormat.usd(summary.cash.usd))
        }
        val broker = linked || summary.brokerLinked
        if (broker) {
            Text(
                "한투에서 불러온 값 (새로고침할 때마다 갱신, 하루 한 번은 기록)" + (cashDate?.let { " · ${formatDate(it)} 기록" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 혼합 방식: 입력값(기준점) + 이후 기록. 통화마다 근거를 보여준다.
            val showUsd = summary.cash.usd != 0.0 || summary.holdings.any { it.holding.currency == Currency.USD }
            listOfNotNull(Currency.KRW, Currency.USD.takeIf { showUsd }).forEach { currency ->
                Text(
                    cashBasis(summary.cashDetails[currency], currency, showUsd),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary.cash.krw < 0 || summary.cash.usd < 0) {
                WarningText("계산상 예수금이 음수입니다. 입금 기록이나 예수금 입력을 확인하세요.")
            }
        }
        if (!linked) TextButton(onClick = onEnterCash) { Text("예수금 입력") }
    }
}

/** 계산 순서상 보유 수량이 모자라 계산되지 않는 매도 안내 (누르면 그 기록). */
@Composable
internal fun ShortSellsCard(shorts: List<PortfolioCalculator.ShortSell>, data: PortfolioData, onOpenRecord: (String) -> Unit) {
    SectionCard(Modifier.padding(top = 12.dp)) {
        Text(
            "⚠ 계산되지 않는 매도 ${shorts.size}건",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "그 매도 시점의 보유 수량이 모자랍니다. 같은 날이면 매수를 먼저 입력하세요. 누르면 그 기록을 엽니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        shorts.forEach { s ->
            TextButton(onClick = { onOpenRecord(s.record.id) }, contentPadding = PaddingValues(0.dp)) {
                Text(shortSellLabel(s, data.holdings.firstOrNull { it.id == s.record.holdingId }))
            }
        }
    }
}

/** 직접 입력 계좌의 예수금 근거: "10/1 입력 1,000,000원 + 이후 기록 −687,600원" 등. */
private fun cashBasis(detail: CashDetail?, currency: Currency, withLabel: Boolean): String {
    val label = if (withLabel) (if (currency == Currency.KRW) "원화: " else "달러: ") else ""
    val amount = { v: Double -> if (currency == Currency.KRW) MoneyFormat.won(v) else MoneyFormat.usd(v) }
    val signed = { v: Double -> if (currency == Currency.KRW) MoneyFormat.signedWon(v) else MoneyFormat.signedUsd(v) }
    val anchor = detail?.anchor
    val date = detail?.anchorDate
    return label + when {
        detail == null || (anchor == null && !detail.estimated) -> "아직 입력하지 않았습니다. 증권사 앱의 예수금을 입력하세요."
        anchor == null || date == null -> "입력한 예수금이 없어 기록(입금·매매 등)으로 계산한 값"
        !detail.estimated -> "${formatDate(date)} 입력한 값"
        else -> "${formatDate(date)} 입력 ${amount(anchor)} + 이후 기록 ${signed(detail.change)} (계산)"
    }
}

/** 수익률을 계산할 수 없는 이유 안내 (웹 AccountDetailPage 와 같은 문구). */
private fun noRateText(reason: PortfolioCalculator.NoRateReason?): String = when (reason) {
    PortfolioCalculator.NoRateReason.BALANCED -> "넣은 돈과 뺀 돈(출금·이체)이 같아 투자금이 0원이라 수익률을 계산할 수 없어요"
    PortfolioCalculator.NoRateReason.NEGATIVE -> "넣은 돈보다 뺀 돈(출금·이체)이 많아 투자금이 0원 이하라 수익률을 계산할 수 없어요"
    else -> "입금 기록이 없어 수익률을 계산할 수 없어요"
}
