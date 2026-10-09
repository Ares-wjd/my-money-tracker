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
import androidx.compose.material3.OutlinedButton
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
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.ChipSelector
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.DateField
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordEditScreen(
    viewModel: PortfolioViewModel,
    accountId: String,
    type: RecordType,
    recordId: String?,
    presetHoldingId: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val current = data
    val existing = recordId?.let { current?.record(it) }
    val effectiveType = existing?.type ?: type

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(effectiveType.label + if (existing == null) " 기록" else " 수정") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        if (current == null || (recordId != null && existing == null)) {
            LoadingBox(Modifier.padding(padding))
        } else {
            RecordForm(
                data = current,
                accountId = existing?.accountId ?: accountId,
                type = effectiveType,
                initial = existing,
                presetHoldingId = presetHoldingId,
                modifier = Modifier.padding(padding),
                onSave = { record ->
                    viewModel.saveRecord(record)
                    onDone()
                },
                onDelete = existing?.let { record ->
                    {
                        viewModel.deleteRecord(record.id)
                        onDone()
                    }
                },
            )
        }
    }
}

@Composable
private fun RecordForm(
    data: PortfolioData,
    accountId: String,
    type: RecordType,
    initial: Record?,
    presetHoldingId: String?,
    modifier: Modifier,
    onSave: (Record) -> Unit,
    onDelete: (() -> Unit)?,
) {
    fun text(value: Double?): String =
        if (value == null || value == 0.0) "" else MoneyFormat.decimal(value, 6).replace(",", "")

    val initialRate = when {
        initial != null && initial.currency == Currency.USD && initial.amount > 0 -> initial.krwAmount / initial.amount
        initial?.type == RecordType.EXCHANGE && initial.amount > 0 -> initial.krwAmount / initial.amount
        else -> data.usdKrw
    }

    var day by rememberSaveable { mutableLongStateOf((initial?.date ?: LocalDate.now()).toEpochDay()) }
    var currency by rememberSaveable { mutableStateOf(initial?.currency ?: Currency.KRW) }
    var amountText by rememberSaveable { mutableStateOf(text(initial?.amount)) }
    var krwText by rememberSaveable { mutableStateOf(text(initial?.krwAmount)) }
    var rateText by rememberSaveable { mutableStateOf(text(initialRate)) }
    var toAccountId by rememberSaveable { mutableStateOf(initial?.toAccountId) }
    var holdingId by rememberSaveable { mutableStateOf(initial?.holdingId ?: presetHoldingId) }
    var quantityText by rememberSaveable { mutableStateOf(text(initial?.quantity)) }
    var priceText by rememberSaveable { mutableStateOf(text(initial?.price)) }
    var feeText by rememberSaveable { mutableStateOf(text(initial?.fee)) }
    var taxText by rememberSaveable { mutableStateOf(text(initial?.tax)) }
    var memo by rememberSaveable { mutableStateOf(initial?.memo.orEmpty()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val date = LocalDate.ofEpochDay(day)
    val amount = MoneyFormat.parseDecimal(amountText)
    val krw = MoneyFormat.parseDecimal(krwText)
    val rate = MoneyFormat.parseDecimal(rateText)
    val quantity = MoneyFormat.parseDecimal(quantityText)
    val price = MoneyFormat.parseDecimal(priceText)
    val fee = MoneyFormat.parseDecimal(feeText) ?: 0.0
    val tax = MoneyFormat.parseDecimal(taxText) ?: 0.0
    val holdings = data.holdingsOf(accountId)
    val holding = holdingId?.let { data.holding(it) }
    val holdingCurrency = holding?.currency ?: Currency.KRW

    // 유형별 유효성 검사. 문제가 있으면 안내 문구, 없으면 null.
    val problem: String? = when (type) {
        RecordType.DEPOSIT, RecordType.WITHDRAW, RecordType.TRANSFER -> when {
            type == RecordType.TRANSFER && toAccountId == null -> "받는 계좌를 고르세요."
            amount == null || amount <= 0 -> "금액을 입력하세요."
            currency == Currency.USD && (rate == null || rate <= 0) -> "원화 환산에 쓸 환율을 입력하세요."
            else -> null
        }
        RecordType.EXCHANGE -> when {
            krw == null || krw <= 0 -> "원화 금액을 입력하세요."
            amount == null || amount <= 0 -> "달러 금액을 입력하세요."
            else -> null
        }
        RecordType.BUY, RecordType.SELL -> when {
            holding == null -> if (holdings.isEmpty()) "먼저 계좌 화면에서 종목을 추가하세요." else "종목을 고르세요."
            quantity == null || quantity <= 0 -> "수량을 입력하세요."
            price == null || price < 0 -> "단가를 입력하세요."
            type == RecordType.SELL &&
                quantity > PortfolioCalculator.quantityAt(holding, data.records, date, initial?.id) + 1e-9 ->
                "매도 수량이 그날 보유 수량(${MoneyFormat.decimal(PortfolioCalculator.quantityAt(holding, data.records, date, initial?.id))})보다 많습니다."
            else -> null
        }
        RecordType.DIVIDEND -> when {
            holding == null -> if (holdings.isEmpty()) "먼저 계좌 화면에서 종목을 추가하세요." else "종목을 고르세요."
            amount == null || amount <= 0 -> "배당금을 입력하세요."
            else -> null
        }
        RecordType.CASH_ADJUST -> if (amount == null || amount == 0.0) "조정 금액을 입력하세요." else null
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "계좌: ${data.account(accountId)?.name.orEmpty()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DateField("날짜", date, { day = it.toEpochDay() })

        when (type) {
            RecordType.DEPOSIT, RecordType.WITHDRAW, RecordType.TRANSFER, RecordType.CASH_ADJUST -> {
                if (type == RecordType.TRANSFER) {
                    val others = data.accounts.filter { it.id != accountId }
                    if (others.isEmpty()) {
                        WarningText("이체할 다른 계좌가 없습니다. 계좌를 먼저 추가하세요.")
                    } else {
                        ChipSelector("받는 계좌", others.map { it.id }, toAccountId, { id -> data.account(id)?.name.orEmpty() }, { toAccountId = it })
                    }
                }
                ChipSelector("통화", Currency.entries, currency, { it.label }, { currency = it })
                NumberField(
                    label = if (type == RecordType.CASH_ADJUST) "조정 금액 (+ 증가 / − 감소)" else "금액",
                    value = amountText,
                    onValueChange = { amountText = it },
                    allowDecimal = currency == Currency.USD,
                    allowNegative = type == RecordType.CASH_ADJUST,
                    preview = { MoneyFormat.amount(currency, it) },
                )
                if (currency == Currency.USD && type != RecordType.CASH_ADJUST) {
                    NumberField(
                        label = "적용 환율 (원/달러, 투자금 원화 환산용)",
                        value = rateText,
                        onValueChange = { rateText = it },
                        preview = { "1달러 = ${MoneyFormat.decimal(it, 2)}원" },
                    )
                    if (amount != null && rate != null) {
                        LabeledValue("원화 환산 투자금", MoneyFormat.won(amount * rate))
                    }
                }
            }

            RecordType.EXCHANGE -> {
                ChipSelector(
                    "방향",
                    listOf(Currency.KRW, Currency.USD),
                    currency,
                    { if (it == Currency.KRW) "원화 → 달러" else "달러 → 원화" },
                    { currency = it },
                )
                NumberField("원화 금액", krwText, { krwText = it }, allowDecimal = false, preview = { MoneyFormat.won(it) })
                NumberField("달러 금액", amountText, { amountText = it }, preview = { MoneyFormat.usd(it) })
                if (krw != null && amount != null && amount > 0) {
                    LabeledValue("적용 환율", "${MoneyFormat.decimal(krw / amount, 2)}원")
                }
            }

            RecordType.BUY, RecordType.SELL, RecordType.DIVIDEND -> {
                if (holdings.isNotEmpty()) {
                    ChipSelector("종목", holdings.map { it.id }, holdingId, { id -> data.holding(id)?.name.orEmpty() }, { holdingId = it })
                }
                if (type == RecordType.DIVIDEND) {
                    NumberField(
                        "배당금 (세후 실수령액, ${holdingCurrency.label})",
                        amountText,
                        { amountText = it },
                        preview = { MoneyFormat.amount(holdingCurrency, it) },
                    )
                } else {
                    NumberField("수량", quantityText, { quantityText = it })
                    NumberField(
                        "단가 (${holdingCurrency.label})",
                        priceText,
                        { priceText = it },
                        preview = { MoneyFormat.amount(holdingCurrency, it) },
                    )
                    NumberField("수수료", feeText, { feeText = it }, preview = { MoneyFormat.amount(holdingCurrency, it) })
                    NumberField("세금", taxText, { taxText = it }, preview = { MoneyFormat.amount(holdingCurrency, it) })
                    if (quantity != null && price != null) {
                        val gross = quantity * price
                        val total = if (type == RecordType.BUY) gross + fee + tax else gross - fee - tax
                        LabeledValue(
                            if (type == RecordType.BUY) "총 매수 금액 (수수료·세금 포함)" else "정산 금액 (수수료·세금 차감)",
                            MoneyFormat.amount(holdingCurrency, total),
                        )
                    }
                }
            }
        }

        TextInput("메모 (선택)", memo, { memo = it }, maxLength = 100)
        problem?.let { WarningText(it) }

        Button(
            onClick = {
                val base = Record(
                    id = initial?.id.orEmpty(),
                    accountId = accountId,
                    type = type,
                    date = date,
                    memo = memo.trim(),
                    initial = initial?.initial ?: false,
                    createdAt = initial?.createdAt ?: 0L,
                )
                val record = when (type) {
                    RecordType.DEPOSIT, RecordType.WITHDRAW, RecordType.TRANSFER -> base.copy(
                        currency = currency,
                        amount = amount ?: 0.0,
                        krwAmount = if (currency == Currency.KRW) amount ?: 0.0 else (amount ?: 0.0) * (rate ?: 0.0),
                        toAccountId = if (type == RecordType.TRANSFER) toAccountId else null,
                    )
                    RecordType.EXCHANGE -> base.copy(currency = currency, amount = amount ?: 0.0, krwAmount = krw ?: 0.0)
                    RecordType.BUY, RecordType.SELL -> base.copy(
                        holdingId = holdingId,
                        quantity = quantity ?: 0.0,
                        price = price ?: 0.0,
                        fee = fee,
                        tax = tax,
                    )
                    RecordType.DIVIDEND -> base.copy(holdingId = holdingId, amount = amount ?: 0.0)
                    RecordType.CASH_ADJUST -> base.copy(currency = currency, amount = amount ?: 0.0)
                }
                onSave(record)
            },
            enabled = problem == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장") }

        if (onDelete != null) {
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("기록 삭제") }
        }
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "기록 삭제",
            text = "이 기록을 삭제할까요? 투자금·예수금·수익률 계산이 다시 이루어집니다.",
            confirmLabel = "삭제",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}
