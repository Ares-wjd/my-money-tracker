package com.mymoneytracker.app.ui.holdings

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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.DeleteButton
import com.mymoneytracker.app.ui.common.ChipSelector
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.DateField
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.AccountKind
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingEditScreen(
    viewModel: PortfolioViewModel,
    accountId: String,
    holdingId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val existing = holdingId?.let { data?.holding(it) }
    // 금현물 통장에는 금만 넣는다
    val goldAccount = data?.account(accountId)?.kind == AccountKind.GOLD

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (holdingId == null) "종목 추가" else "종목 편집") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        if (data == null || (holdingId != null && existing == null)) {
            LoadingBox(Modifier.padding(padding))
        } else {
            HoldingForm(
                accountId = accountId,
                initial = existing,
                goldAccount = goldAccount,
                modifier = Modifier.padding(padding),
                onSave = { holding, initialBuy ->
                    val id = viewModel.saveHolding(holding)
                    initialBuy?.let { viewModel.saveRecord(it.copy(holdingId = id)) }
                    onSaved(id)
                },
                onDelete = existing?.let { holding ->
                    {
                        viewModel.deleteHolding(holding.id)
                        onDeleted()
                    }
                },
            )
        }
    }
}

@Composable
private fun HoldingForm(
    accountId: String,
    initial: Holding?,
    goldAccount: Boolean,
    modifier: Modifier,
    onSave: (Holding, Record?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var code by rememberSaveable { mutableStateOf(initial?.code ?: if (goldAccount) AssetType.GOLD_CODE else "") }
    var market by rememberSaveable { mutableStateOf(initial?.market ?: Market.KR) }
    var assetType by rememberSaveable {
        mutableStateOf(if (goldAccount) AssetType.GOLD else initial?.assetType ?: AssetType.STOCK)
    }
    var name by rememberSaveable { mutableStateOf(initial?.name ?: if (goldAccount) "KRX 금현물" else "") }
    val gold = assetType == AssetType.GOLD
    val unitLabel = if (gold) " (g)" else ""
    var hasInitial by rememberSaveable { mutableStateOf(false) }
    var initialQuantity by rememberSaveable { mutableStateOf("") }
    var initialAverage by rememberSaveable { mutableStateOf("") }
    var initialDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val quantity = MoneyFormat.parseDecimal(initialQuantity)
    val average = MoneyFormat.parseDecimal(initialAverage)
    val initialValid = !hasInitial || (quantity != null && quantity > 0 && average != null && average >= 0)

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextInput("종목 이름 (예: 삼성전자, Apple)", name, { name = it }, maxLength = 50)
        TextInput("종목 코드 (국내 6자리 / 해외 티커, 펀드·채권은 비워도 됨)", code, { code = it.uppercase().trim() }, maxLength = 20)
        if (gold) {
            Text(
                "금현물: KRX 금 99.99 1kg (종목코드 ${AssetType.GOLD_CODE}, 미니금 100g 은 M04020100). 국내(원) 시장, 수량은 g, 가격은 1g당.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ChipSelector("시장", Market.entries, market, { "${it.label} (${it.currency.label})" }, { market = it })
        }
        if (!goldAccount) {
            ChipSelector("자산 유형", AssetType.entries, assetType, { it.label }, {
                assetType = it
                // 금현물은 국내(원) 고정, 코드가 비어 있으면 KRX 금 1kg 코드
                if (it == AssetType.GOLD) {
                    market = Market.KR
                    if (code.isBlank()) code = AssetType.GOLD_CODE
                }
            })
        }

        if (initial == null) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hasInitial, onCheckedChange = { hasInitial = it })
                    Text("이미 보유 중인 종목입니다 (초기 보유분 입력)")
                }
                if (hasInitial) {
                    Text(
                        "현재 수량과 평균단가를 기준일의 매수 기록으로 남깁니다. " +
                            "기존 투자금은 입금 기록으로, 예수금은 계좌 화면의 예수금 입력으로 넣어 주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NumberField("보유 수량$unitLabel", initialQuantity, { initialQuantity = it })
                    NumberField(
                        "평균단가 (${market.currency.label}${if (gold) ", 1g당" else ""})",
                        initialAverage,
                        { initialAverage = it },
                        preview = { MoneyFormat.amountPreview(market.currency, it) },
                    )
                    DateField("기준일", LocalDate.ofEpochDay(initialDay), { initialDay = it.toEpochDay() })
                }
            }
        }

        Button(
            onClick = {
                val holding = Holding(
                    id = initial?.id.orEmpty(),
                    accountId = accountId,
                    name = name.trim(),
                    code = code.trim(),
                    market = if (gold) Market.KR else market,
                    assetType = assetType,
                    manualPrice = initial?.manualPrice,
                    manualPriceDate = initial?.manualPriceDate,
                    createdAt = initial?.createdAt ?: 0L,
                )
                val initialBuy = if (initial == null && hasInitial && quantity != null && average != null) {
                    Record(
                        accountId = accountId,
                        type = RecordType.BUY,
                        date = LocalDate.ofEpochDay(initialDay),
                        quantity = quantity,
                        price = average,
                        initial = true,
                    )
                } else {
                    null
                }
                onSave(holding, initialBuy)
            },
            enabled = name.isNotBlank() && initialValid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장") }

        if (onDelete != null) {
            DeleteButton("종목 삭제", onClick = { confirmDelete = true })
        }
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "종목 삭제",
            text = "이 종목의 매수·매도·배당 기록이 모두 함께 삭제됩니다. 되돌릴 수 없습니다.",
            confirmLabel = "삭제",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}
