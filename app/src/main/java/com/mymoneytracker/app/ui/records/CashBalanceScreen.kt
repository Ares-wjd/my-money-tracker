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
import com.mymoneytracker.app.ui.common.DateField
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.portfolio.AccountSummary
import java.time.LocalDate
import kotlin.math.roundToLong

/** 증권사 앱에 보이는 예수금을 직접 입력한다. 계산하지 않고 입력한 값이 그대로 계좌의 예수금이 된다. */
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
            CashBalanceForm(
                summary = summary,
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
            "증권사 앱에 보이는 예수금을 그대로 입력하세요. 매매·입출금으로 계산하지 않고, 입력한 값이 이 계좌의 예수금이 됩니다. " +
                "예수금이 바뀌었을 때(매매·입출금·이자 등) 다시 입력하면 됩니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NumberField("원화 예수금", krwText, { krwText = it }, allowDecimal = false, allowNegative = true, preview = { MoneyFormat.manwon(it) })
        if (showUsd) {
            NumberField("달러 예수금", usdText, { usdText = it }, allowNegative = true, preview = { MoneyFormat.usd(it) })
        }
        DateField("기준일", date, { day = it.toEpochDay() })
        TextInput("메모 (선택)", memo, { memo = it }, maxLength = 100)
        summary.cashDate?.let { last ->
            if (date.isBefore(last)) WarningText("기준일이 마지막 입력일(${formatDate(last)})보다 이전이라 지금 예수금은 바뀌지 않습니다.")
        }
        Button(
            onClick = { onSave(date, krw, usd, memo.trim()) },
            enabled = krw != null || usd != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장") }
    }
}
