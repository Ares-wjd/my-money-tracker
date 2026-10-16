package com.mymoneytracker.app.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.MoneyField
import com.mymoneytracker.app.ui.common.DeleteButton
import com.mymoneytracker.app.ui.common.ChipSelector
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.DateField
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.CostInput
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.ExchangeField
import com.mymoneytracker.core.portfolio.ExchangeInput
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
        } else if (existing != null && existing.externalId != null) {
            ImportedRecordView(
                data = current,
                record = existing,
                modifier = Modifier.padding(padding),
                onSave = { record ->
                    viewModel.saveRecord(record)
                    onDone()
                },
            )
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
        // 새 환전 기록은 환율을 비워 둔다 (세 칸 중 입력한 두 칸으로 계산하므로)
        initial == null && type == RecordType.EXCHANGE -> null
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
    // 제비용: 수수료나 세금이 있으면 나눠서, 아니면 제비용합으로 연다
    val initialCost = CostInput.of(initial)
    var costText by rememberSaveable { mutableStateOf(text(initialCost.total)) }
    var feeText by rememberSaveable { mutableStateOf(text(initialCost.fee)) }
    var taxText by rememberSaveable { mutableStateOf(text(initialCost.tax)) }
    // 환전: 입력한 순서 (최근 두 칸으로 나머지 한 칸을 자동 계산). 기존 기록은 원화·달러를 입력한 것으로 본다 → 환율이 자동
    var exchangeEdited by rememberSaveable { mutableStateOf(if (initial?.type == RecordType.EXCHANGE) "KRW,USD" else "") }
    var memo by rememberSaveable { mutableStateOf(initial?.memo.orEmpty()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // 저장하면 계산되지 않게 되는 다른 매도가 있을 때 확인 창에 쓰는 (저장할 기록, 그 매도들)
    var pendingSave by remember { mutableStateOf<Pair<Record, List<PortfolioCalculator.ShortSell>>?>(null) }

    val date = LocalDate.ofEpochDay(day)
    val exEdited = exchangeEdited.split(",").mapNotNull { name -> ExchangeField.entries.firstOrNull { it.name == name } }
    val exAuto = if (type == RecordType.EXCHANGE) ExchangeInput.autoField(exEdited) else null
    val exComputed = exAuto?.let {
        ExchangeInput.compute(it, MoneyFormat.parseDecimal(krwText), MoneyFormat.parseDecimal(amountText), MoneyFormat.parseDecimal(rateText))
    }
    val amount = if (exAuto == ExchangeField.USD) exComputed else MoneyFormat.parseDecimal(amountText)
    val krw = if (exAuto == ExchangeField.KRW) exComputed else MoneyFormat.parseDecimal(krwText)
    val rate = if (exAuto == ExchangeField.RATE) exComputed else MoneyFormat.parseDecimal(rateText)
    fun touchExchange(field: ExchangeField) {
        exchangeEdited = ExchangeInput.touch(exEdited, field).joinToString(",") { it.name }
    }
    // 자동 칸에 보여줄 값 (계산할 수 없으면 빈칸)
    fun exchangeText(field: ExchangeField, input: String, digits: Int): String =
        if (exAuto == field) exComputed?.let { MoneyFormat.decimal(it, digits).replace(",", "") }.orEmpty() else input
    fun exchangeLabel(field: ExchangeField, label: String) = if (exAuto == field) "$label · 자동 계산" else label
    val quantity = MoneyFormat.parseDecimal(quantityText)
    val price = MoneyFormat.parseDecimal(priceText)
    val cost = CostInput(MoneyFormat.parseDecimal(costText), MoneyFormat.parseDecimal(feeText), MoneyFormat.parseDecimal(taxText))
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
            type == RecordType.SELL && quantity > sellHeld(data.records, initial, accountId, holding.id, date, quantity) + 1e-9 ->
                "매도 수량이 이 매도 시점의 보유 수량(${MoneyFormat.decimal(sellHeld(data.records, initial, accountId, holding.id, date, quantity))})보다 많습니다."
            else -> null
        }
        RecordType.DIVIDEND -> when {
            holding == null -> if (holdings.isEmpty()) "먼저 계좌 화면에서 종목을 추가하세요." else "종목을 고르세요."
            amount == null || amount <= 0 -> "배당금을 입력하세요."
            else -> null
        }
        RecordType.CASH_ADJUST -> if (amount == null || amount == 0.0) "조정 금액을 입력하세요." else null
        RecordType.CASH_BALANCE -> if (amount == null) "예수금을 입력하세요." else null
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
            RecordType.DEPOSIT, RecordType.WITHDRAW, RecordType.TRANSFER, RecordType.CASH_ADJUST, RecordType.CASH_BALANCE -> {
                if (type == RecordType.TRANSFER) {
                    val others = data.accounts.filter { it.id != accountId }
                    if (others.isEmpty()) {
                        WarningText("이체할 다른 계좌가 없습니다. 계좌를 먼저 추가하세요.")
                    } else {
                        ChipSelector("받는 계좌", others.map { it.id }, toAccountId, { id -> data.account(id)?.name.orEmpty() }, { toAccountId = it })
                    }
                }
                ChipSelector("통화", Currency.entries, currency, { it.label }, {
                    currency = it
                    // 원화는 정수만 (달러에서 바꾸면 소수점 아래를 버린다)
                    if (it == Currency.KRW) amountText = amountText.substringBefore('.')
                })
                val amountLabel = when (type) {
                    RecordType.CASH_ADJUST -> "조정 금액 (+ 증가 / − 감소)"
                    RecordType.CASH_BALANCE -> "그날의 예수금"
                    else -> "금액"
                }
                val signed = type == RecordType.CASH_ADJUST || type == RecordType.CASH_BALANCE
                if (currency == Currency.KRW) {
                    MoneyField(
                        amountLabel,
                        amountText,
                        { amountText = it },
                        allowNegative = signed,
                        // 딱 떨어지는 금액을 적는 입금·출금·이체에만 빠른 입력 버튼
                        quick = type == RecordType.DEPOSIT || type == RecordType.WITHDRAW || type == RecordType.TRANSFER,
                    )
                } else {
                    NumberField(
                        label = amountLabel,
                        value = amountText,
                        onValueChange = { amountText = it },
                        allowNegative = signed,
                        preview = { MoneyFormat.usd(it) },
                    )
                }
                if (currency == Currency.USD && type != RecordType.CASH_ADJUST && type != RecordType.CASH_BALANCE) {
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
                // 셋 중 마지막으로 입력한 두 칸으로 나머지 한 칸을 자동 계산
                MoneyField(exchangeLabel(ExchangeField.KRW, "원화 금액"), exchangeText(ExchangeField.KRW, krwText, 0), {
                    krwText = it
                    touchExchange(ExchangeField.KRW)
                })
                NumberField(exchangeLabel(ExchangeField.USD, "달러 금액"), exchangeText(ExchangeField.USD, amountText, 2), {
                    amountText = it
                    touchExchange(ExchangeField.USD)
                }, preview = { MoneyFormat.usd(it) })
                NumberField(exchangeLabel(ExchangeField.RATE, "환율 (1달러당 원)"), exchangeText(ExchangeField.RATE, rateText, 2), {
                    rateText = it
                    touchExchange(ExchangeField.RATE)
                }, preview = { "1달러 = ${MoneyFormat.decimal(it, 2)}원" })
                if (exAuto == null) {
                    Text(
                        "원화 금액·달러 금액·환율 중 두 칸을 입력하면 나머지 한 칸은 자동으로 계산합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        preview = { MoneyFormat.amountPreview(holdingCurrency, it) },
                    )
                } else {
                    val gold = holding?.assetType == AssetType.GOLD
                    NumberField(if (gold) "수량 (g)" else "수량", quantityText, { quantityText = it })
                    NumberField(
                        "단가 (${holdingCurrency.label}${if (gold) ", 1g당" else ""})",
                        priceText,
                        { priceText = it },
                        preview = { MoneyFormat.amountPreview(holdingCurrency, it) },
                    )
                    // 수수료·세금을 쓰면 제비용합은 자동 계산(잠김), 제비용합만 쓰면 수수료·세금이 잠긴다
                    SectionCard(title = "제비용") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            NumberField(
                                "제비용합",
                                if (cost.totalLocked) text(cost.effectiveTotal) else costText,
                                { costText = it },
                                enabled = !cost.totalLocked,
                                preview = { MoneyFormat.amountPreview(holdingCurrency, it) },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberField(
                                    "수수료", feeText, { feeText = it },
                                    modifier = Modifier.weight(1f),
                                    enabled = !cost.partsLocked,
                                    preview = { MoneyFormat.amountPreview(holdingCurrency, it) },
                                )
                                NumberField(
                                    "세금", taxText, { taxText = it },
                                    modifier = Modifier.weight(1f),
                                    enabled = !cost.partsLocked,
                                    preview = { MoneyFormat.amountPreview(holdingCurrency, it) },
                                )
                            }
                            Text(
                                when {
                                    cost.breakdown -> "수수료·세금의 합으로 제비용합을 계산합니다."
                                    cost.partsLocked -> "제비용합만 저장합니다. 나눠 쓰려면 제비용합을 비우세요."
                                    else -> "제비용합만 쓰거나, 수수료·세금을 나눠 쓰세요."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (quantity != null && price != null) {
                        val gross = quantity * price
                        val total = if (type == RecordType.BUY) gross + cost.effectiveTotal else gross - cost.effectiveTotal
                        LabeledValue(
                            if (type == RecordType.BUY) "총 매수 금액 (제비용 포함)" else "정산 금액 (제비용 차감)",
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
                    RecordType.BUY, RecordType.SELL -> cost.applyTo(
                        base.copy(holdingId = holdingId, quantity = quantity ?: 0.0, price = price ?: 0.0),
                    )
                    RecordType.DIVIDEND -> base.copy(holdingId = holdingId, amount = amount ?: 0.0)
                    RecordType.CASH_ADJUST, RecordType.CASH_BALANCE -> base.copy(currency = currency, amount = amount ?: 0.0)
                }
                // 이렇게 저장하면 다른 매도가 보유 수량이 모자라 계산되지 않게 되면 한 번 더 묻는다
                val broken = PortfolioCalculator.newlyShortSells(data.records, initial, record)
                if (broken.isEmpty()) onSave(record) else pendingSave = record to broken
            },
            enabled = problem == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장") }

        if (onDelete != null) {
            DeleteButton("기록 삭제", onClick = { confirmDelete = true })
        }
    }

    if (confirmDelete && onDelete != null) {
        val broken = initial?.let { PortfolioCalculator.newlyShortSells(data.records, it, null) }.orEmpty()
        ConfirmDialog(
            title = "기록 삭제",
            text = "이 기록을 삭제할까요? 투자금·평가금·수익률 계산이 다시 이루어집니다." +
                (if (broken.isEmpty()) "" else "\n\n" + brokenSellsText(broken, data)),
            confirmLabel = "삭제",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
    pendingSave?.let { (record, broken) ->
        ConfirmDialog(
            title = "계산되지 않는 매도",
            text = brokenSellsText(broken, data) + "\n\n그래도 저장할까요?",
            confirmLabel = "그래도 저장",
            onConfirm = {
                pendingSave = null
                onSave(record)
            },
            onDismiss = { pendingSave = null },
        )
    }
}

/** 한투에서 불러온 기록: 내용은 고칠 수 없고 메모만 고칠 수 있다. */
@Composable
private fun ImportedRecordView(
    data: PortfolioData,
    record: Record,
    modifier: Modifier,
    onSave: (Record) -> Unit,
) {
    var memo by rememberSaveable { mutableStateOf(record.memo) }
    val accountId = record.accountId
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "한투에서 불러온 기록이라 내용은 고칠 수 없고 메모만 고칠 수 있습니다. 잘못되었다면 한투 앱의 내역을 확인하세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledValue("날짜", formatDate(record.date))
        LabeledValue("내용", recordTitle(record, data, accountId))
        recordDetailText(record, data)?.let { LabeledValue("상세", it) }
        LabeledValue("금액", recordAmountText(record, data, accountId))
        TextInput("메모", memo, { memo = it }, maxLength = 100)
        Button(onClick = { onSave(record.copy(memo = memo.trim())) }, modifier = Modifier.fillMaxWidth()) { Text("메모 저장") }
    }
}

/** 계산 순서상 이 매도 시점의 보유 수량 (새 기록은 그날 맨 뒤, 수정이면 원래 자리). 웹 recordProblem 과 같은 규칙. */
private fun sellHeld(records: List<Record>, initial: Record?, accountId: String, holdingId: String, date: java.time.LocalDate, quantity: Double): Double {
    val target = (initial ?: Record(accountId = accountId, type = RecordType.SELL, date = date))
        .copy(type = RecordType.SELL, date = date, holdingId = holdingId, quantity = quantity)
    return PortfolioCalculator.quantityBefore(holdingId, records, target)
}

/** 확인 창 글: 계산되지 않게 되는 매도 목록 (웹 RecordForm 과 같은 문구). */
private fun brokenSellsText(broken: List<PortfolioCalculator.ShortSell>, data: PortfolioData): String =
    "이렇게 하면 아래 매도가 보유 수량이 모자라 계산되지 않습니다.\n" +
        broken.joinToString("\n") { s -> "· " + shortSellLabel(s, data.holdings.firstOrNull { it.id == s.record.holdingId }) }
