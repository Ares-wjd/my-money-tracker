package com.mymoneytracker.app.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.MoneyField
import com.mymoneytracker.app.ui.common.DateField
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.portfolio.AccountSummary
import com.mymoneytracker.core.portfolio.CashCalculator
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * 증권사 앱에 보이는 예수금을 직접 입력한다. 입력한 값은 그날의 기준점이 되고, 그 사이 날짜는 매매·입출금 기록으로 계산한다 (혼합 방식).
 * 입력 전에 그날의 계산값을 보여주고, 입력값과의 차이(이자·수수료 등 기록되지 않은 금액)를 알려준다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashBalanceScreen(
    viewModel: PortfolioViewModel,
    accountId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val summary = data?.accountSummary(accountId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("예수금 입력") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        if (summary == null) {
            LoadingBox(Modifier.padding(padding))
        } else {
            val current = data!!
            CashBalanceForm(
                summary = summary,
                // 그날의 계산값 (그날 입력한 값은 빼고 계산)
                computedAt = { date, currency ->
                    CashCalculator.detail(
                        accountId, currency,
                        current.records.filter { it.accountId == accountId || it.toAccountId == accountId },
                        current.holdings.filter { it.accountId == accountId },
                        date, linked = false, excludeAnchorOn = date,
                    ).amount
                },
                modifier = Modifier.padding(padding),
                onSave = { date, krw, usd, memo ->
                    viewModel.saveCashBalance(accountId, date, krw, usd, memo)
                    onDone()
                },
            )
        }
    }
}

@Composable
private fun CashBalanceForm(
    summary: AccountSummary,
    computedAt: (LocalDate, Currency) -> Double,
    modifier: Modifier,
    onSave: (LocalDate, Double?, Double?, String) -> Unit,
) {
    val cash = summary.cash
    val showUsd = cash.usd != 0.0 || summary.holdings.any { it.holding.currency == Currency.USD }
    var krwText by rememberSaveable { mutableStateOf(cash.krw.roundToLong().toString()) }
    var usdText by rememberSaveable { mutableStateOf(MoneyFormat.decimal(cash.usd, 2).replace(",", "")) }
    var day by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var memo by rememberSaveable { mutableStateOf("") }

    val krw = MoneyFormat.parseDecimal(krwText)
    val usd = if (showUsd) MoneyFormat.parseDecimal(usdText) else null
    val date = LocalDate.ofEpochDay(day)

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "증권사 앱에 보이는 예수금을 그대로 입력하세요. 입력한 값이 그날의 기준이 되고, 그 사이 날짜는 매매·입출금·배당·환전 기록으로 계산합니다. " +
                "가끔 입력하면 기록되지 않은 이자·수수료만큼의 차이가 맞춰집니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoneyField("원화 예수금", krwText, { krwText = it }, allowNegative = true)
        if (showUsd) {
            NumberField("달러 예수금", usdText, { usdText = it }, allowNegative = true, preview = { MoneyFormat.usd(it) })
        }
        DateField("기준일", date, { day = it.toEpochDay() })
        // 그날의 계산값과 입력값의 차이
        SectionCard {
            val krwComputed = computedAt(date, Currency.KRW)
            LabeledValue("${formatDate(date)} 계산값 (원화)", MoneyFormat.won(krwComputed))
            krw?.let { LabeledValue("차이", MoneyFormat.signedWon(it - krwComputed)) }
            if (showUsd) {
                val usdComputed = computedAt(date, Currency.USD)
                LabeledValue("${formatDate(date)} 계산값 (달러)", MoneyFormat.usd(usdComputed))
                usd?.let { LabeledValue("차이", MoneyFormat.signedUsd(it - usdComputed)) }
            }
            Text(
                "차이는 이자·수수료 등 기록되지 않은 금액입니다. 입력하면 그날부터 입력값 기준으로 다시 계산합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextInput("메모 (선택)", memo, { memo = it }, maxLength = 100)
        summary.cashDate?.let { last ->
            if (date.isBefore(last)) WarningText("기준일이 마지막 입력일(${formatDate(last)})보다 이전이라 그 사이 날짜의 예수금만 바뀝니다 (지금 예수금은 마지막 입력 기준).")
        }
        Button(
            onClick = { onSave(date, krw, usd, memo.trim()) },
            enabled = krw != null || usd != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장") }
    }
}
