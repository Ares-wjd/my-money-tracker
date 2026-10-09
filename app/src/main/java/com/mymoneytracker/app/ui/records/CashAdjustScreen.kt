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
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.CashBalance
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

/** 실제 예수금을 입력하면 기록으로 계산한 값과의 차이를 "예수금 조정" 기록으로 남긴다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashAdjustScreen(
    viewModel: PortfolioViewModel,
    accountId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val cash = data?.accountSummary(accountId)?.computedCash

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("예수금 수정") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        if (cash == null) {
            LoadingBox(Modifier.padding(padding))
        } else {
            CashAdjustForm(
                computed = cash,
                modifier = Modifier.padding(padding),
                onSave = { date, krwDiff, usdDiff, memo ->
                    if (abs(krwDiff) >= 0.5) {
                        viewModel.saveRecord(
                            Record(accountId = accountId, type = RecordType.CASH_ADJUST, date = date, currency = Currency.KRW, amount = krwDiff.roundToLong().toDouble(), memo = memo),
                        )
                    }
                    if (abs(usdDiff) >= 0.005) {
                        viewModel.saveRecord(
                            Record(accountId = accountId, type = RecordType.CASH_ADJUST, date = date, currency = Currency.USD, amount = usdDiff, memo = memo),
                        )
                    }
                    onDone()
                },
            )
        }
    }
}

@Composable
private fun CashAdjustForm(
    computed: CashBalance,
    modifier: Modifier,
    onSave: (LocalDate, Double, Double, String) -> Unit,
) {
    var krwText by rememberSaveable { mutableStateOf(computed.krw.roundToLong().toString()) }
    var usdText by rememberSaveable { mutableStateOf(MoneyFormat.decimal(computed.usd, 2).replace(",", "")) }
    var day by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var memo by rememberSaveable { mutableStateOf("예탁금 이용료 등") }

    val krwDiff = (MoneyFormat.parseDecimal(krwText) ?: computed.krw) - computed.krw
    val usdDiff = (MoneyFormat.parseDecimal(usdText) ?: computed.usd) - computed.usd

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "증권사 앱에 보이는 실제 예수금을 입력하세요. 기록으로 계산한 값과의 차이가 \"예수금 조정\" 기록으로 남습니다. " +
                "이 금액은 투자금이 아니며, 계좌 전체 수익에 반영됩니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard(title = "기록으로 계산한 예수금") {
            LabeledValue("원화", MoneyFormat.won(computed.krw))
            LabeledValue("달러", MoneyFormat.usd(computed.usd))
        }
        NumberField("실제 원화 예수금", krwText, { krwText = it }, allowDecimal = false, allowNegative = true, preview = { MoneyFormat.won(it) })
        NumberField("실제 달러 예수금", usdText, { usdText = it }, allowNegative = true, preview = { MoneyFormat.usd(it) })
        DateField("기준일", LocalDate.ofEpochDay(day), { day = it.toEpochDay() })
        TextInput("메모", memo, { memo = it }, maxLength = 100)
        SectionCard(title = "조정될 금액") {
            LabeledValue("원화", MoneyFormat.signedWon(krwDiff))
            LabeledValue("달러", MoneyFormat.signedUsd(usdDiff))
        }
        Button(
            onClick = { onSave(LocalDate.ofEpochDay(day), krwDiff, usdDiff, memo.trim()) },
            enabled = abs(krwDiff) >= 0.5 || abs(usdDiff) >= 0.005,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("조정 기록 저장") }
    }
}
