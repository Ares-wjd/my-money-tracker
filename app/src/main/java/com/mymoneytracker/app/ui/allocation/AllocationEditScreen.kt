package com.mymoneytracker.app.ui.allocation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import com.mymoneytracker.app.ui.common.MoneyField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.AppFilterChip
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.appCardColors
import com.mymoneytracker.app.ui.common.DeleteButton
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.portfolio.AllocationCalculator
import com.mymoneytracker.core.portfolio.AllocationTarget
import com.mymoneytracker.core.portfolio.ToleranceRule
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllocationEditScreen(
    viewModel: PortfolioViewModel,
    targetId: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val targets by viewModel.allocationTargets.collectAsStateWithLifecycle()
    val existing = targetId?.let { id -> targets?.firstOrNull { it.id == id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle(if (targetId == null) "비중 설정 추가" else "비중 설정 편집") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } },
            )
        },
    ) { padding ->
        val current = data
        if (current == null || targets == null || (targetId != null && existing == null)) {
            LoadingBox(Modifier.padding(padding))
        } else {
            AllocationForm(
                initial = existing,
                accounts = current.accounts,
                holdings = current.holdings,
                modifier = Modifier.padding(padding),
                onSave = {
                    viewModel.selectAllocation(viewModel.saveAllocationTarget(it))
                    onDone()
                },
                onDelete = existing?.let { t ->
                    {
                        viewModel.deleteAllocationTarget(t.id)
                        onDone()
                    }
                },
            )
        }
    }
}

private fun numberText(value: Double): String = MoneyFormat.decimal(value, 4).replace(",", "")

private fun isForeignKey(key: String) =
    key.startsWith(AllocationCalculator.US_PREFIX) || key.startsWith(AllocationCalculator.MANUAL_US_PREFIX)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllocationForm(
    initial: AllocationTarget?,
    accounts: List<InvestmentAccount>,
    holdings: List<Holding>,
    modifier: Modifier,
    onSave: (AllocationTarget) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    val accountIds = remember {
        mutableStateListOf<String>().apply { addAll(initial?.accountIds ?: if (accounts.size == 1) listOf(accounts.first().id) else emptyList()) }
    }
    var includeCash by rememberSaveable { mutableStateOf(initial?.includeCash ?: false) }
    // 허용 범위: 절대 기준 A (%p), 상대 기준 R (%). 새 설정은 5/25 룰, 예전 설정은 A 만 (R 없음)
    fun bandText(v: Double?) = v?.takeIf { it > 0 }?.let { MoneyFormat.decimal(it, 2).replace(",", "") }.orEmpty()
    var absoluteText by rememberSaveable {
        mutableStateOf(if (initial != null) bandText(initial.threshold) else bandText(AllocationCalculator.DEFAULT_ABSOLUTE))
    }
    var relativeText by rememberSaveable {
        mutableStateOf(if (initial != null) bandText(initial.relative) else bandText(AllocationCalculator.DEFAULT_RELATIVE))
    }
    // 상단(초과) 기준을 따로 쓸지 (기본: 상단·하단 같은 기준)
    var sameBands by rememberSaveable { mutableStateOf(initial?.upper == null) }
    var upperAbsoluteText by rememberSaveable { mutableStateOf(bandText(initial?.upper?.threshold)) }
    var upperRelativeText by rememberSaveable { mutableStateOf(bandText(initial?.upper?.relative)) }
    val texts = remember { mutableStateMapOf<String, String>().apply { initial?.targets?.forEach { (k, v) -> put(k, numberText(v)) } } }
    // 매수 단위 (비우면 1주 단위, 금액이면 그 금액 단위)
    val unitTexts = remember { mutableStateMapOf<String, String>().apply { initial?.units?.forEach { (k, v) -> put(k, numberText(v)) } } }
    val excluded = remember { mutableStateListOf<String>().apply { addAll(initial?.excluded.orEmpty()) } }
    // 지금 매수 안 함 (비중 표에는 보이고 매수 계산기에서만 뺌)
    val noBuy = remember { mutableStateListOf<String>().apply { addAll(initial?.noBuy.orEmpty()) } }
    // 계좌에 없는(아직 안 산) 종목: 종목 키 → 이름, 직접 입력한 현재가
    val manual = remember { mutableStateMapOf<String, String>().apply { putAll(initial?.names.orEmpty()) } }
    val priceTexts = remember { mutableStateMapOf<String, String>().apply { initial?.prices?.forEach { (k, v) -> put(k, numberText(v)) } } }
    var bulkMoney by rememberSaveable { mutableStateOf(false) }
    var bulkText by rememberSaveable { mutableStateOf("1000") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var addForeign by rememberSaveable { mutableStateOf(true) }
    var addCode by rememberSaveable { mutableStateOf("") }
    var addName by rememberSaveable { mutableStateOf("") }
    var addPrice by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // 대상 계좌의 종목 (같은 종목은 하나로, 0주 포함)
    val items = linkedMapOf<String, String>()
    holdings.filter { it.accountId in accountIds }.forEach { h -> items.putIfAbsent(AllocationCalculator.keyOf(h), h.name) }
    // 매수 단위 칸의 기본 표시 (금현물은 1g)
    val goldKeys = holdings.filter { it.accountId in accountIds && it.assetType == AssetType.GOLD }.map { AllocationCalculator.keyOf(it) }.toSet()
    val held = items.keys.toSet()
    // 직접 추가한 종목, 그리고 이미 목표가 있는데 대상 계좌에 없는 종목(지웠거나 계좌를 뺐을 때)도 남겨 둔다
    manual.forEach { (k, n) -> items.putIfAbsent(k, n) }
    initial?.targets?.keys?.filter { it != AllocationCalculator.CASH && it !in items }?.forEach { items[it] = it.substringAfter(':') }
    val stockKeys = items.keys.toList()
    val keys = stockKeys + if (includeCash) listOf(AllocationCalculator.CASH) else emptyList()

    // 제외한 종목은 목표 % 를 받지 않고 계산에서 뺀다
    fun percent(k: String) = if (k in excluded) 0.0 else MoneyFormat.parseDecimal(texts[k].orEmpty()) ?: 0.0
    fun bad(text: String?, max: Double = Double.MAX_VALUE): Boolean {
        val t = text.orEmpty()
        if (t.isBlank()) return false
        val n = MoneyFormat.parseDecimal(t) ?: return true
        return n < 0 || n > max
    }
    val sum = keys.sumOf { percent(it) }
    val absolute = MoneyFormat.parseDecimal(absoluteText) ?: 0.0
    val relative = MoneyFormat.parseDecimal(relativeText)?.takeIf { it > 0 }
    val lowerRule = ToleranceRule(absolute, relative)
    val upperRule = if (sameBands) {
        null
    } else {
        ToleranceRule(MoneyFormat.parseDecimal(upperAbsoluteText) ?: 0.0, MoneyFormat.parseDecimal(upperRelativeText)?.takeIf { it > 0 })
    }
    val problem = when {
        name.isBlank() -> "이름을 입력하세요."
        accountIds.isEmpty() -> "대상 계좌를 고르세요."
        keys.any { it !in excluded && bad(texts[it], 100.0) } -> "목표 비중은 0~100% 로 입력하세요."
        stockKeys.any { bad(unitTexts[it]) || bad(priceTexts[it]) } -> "매수 단위·현재가를 확인하세요."
        abs(sum - 100) > 0.01 -> "목표 합계가 100% 가 되어야 합니다 (지금 ${MoneyFormat.decimal(sum, 2)}%)."
        bad(absoluteText, 100.0) || bad(relativeText, 100.0) ||
            (!sameBands && (bad(upperAbsoluteText, 100.0) || bad(upperRelativeText, 100.0))) -> "허용 범위의 절대·상대 기준은 0~100 으로 입력하세요."
        !lowerRule.isSet || upperRule?.isSet == false ->
            if (sameBands) "허용 범위의 절대·상대 기준 중 하나는 넣으세요." else "상단·하단마다 절대·상대 기준 중 하나는 넣으세요."
        else -> null
    }
    val relinkTargets = held.filter { percent(it) == 0.0 && it !in excluded }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextInput("이름 (예: ISA 자산배분, 미국 개별주)", name, { name = it }, maxLength = 40)
        Text("대상 계좌 (여러 개면 같은 종목을 합쳐서 계산)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        accounts.forEach { a ->
            Row(
                Modifier.fillMaxWidth().clickable { if (a.id in accountIds) accountIds.remove(a.id) else accountIds.add(a.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = a.id in accountIds, onCheckedChange = { if (it) accountIds.add(a.id) else accountIds.remove(a.id) })
                Text(a.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("예수금도 비중에 넣기", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = includeCash, onCheckedChange = { includeCash = it })
        }
        // 허용 범위 (5/25 룰): 허용 편차 = min(A, 목표 × R)
        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = appCardColors()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("허용 범위 (벗어나면 부족·초과로 강조)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        absoluteText = bandText(AllocationCalculator.DEFAULT_ABSOLUTE)
                        relativeText = bandText(AllocationCalculator.DEFAULT_RELATIVE)
                        sameBands = true
                    }) { Text("5/25 기본값") }
                }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        // 끄면 상단 칸은 지금 값으로 시작
                        if (sameBands) {
                            upperAbsoluteText = absoluteText
                            upperRelativeText = relativeText
                        }
                        sameBands = !sameBands
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = sameBands, onCheckedChange = null)
                    Text("상단·하단 같은 기준", style = MaterialTheme.typography.bodyMedium)
                }
                if (!sameBands) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("상단 절대 A (%p)", upperAbsoluteText, { upperAbsoluteText = it }, modifier = Modifier.weight(1f), supporting = "비우면 안 씀")
                        NumberField("상단 상대 R (%)", upperRelativeText, { upperRelativeText = it }, modifier = Modifier.weight(1f), supporting = "비우면 안 씀")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(if (sameBands) "절대 기준 A (%p)" else "하단 절대 A (%p)", absoluteText, { absoluteText = it }, modifier = Modifier.weight(1f), supporting = "비우면 안 씀")
                    NumberField(if (sameBands) "상대 기준 R (%)" else "하단 상대 R (%)", relativeText, { relativeText = it }, modifier = Modifier.weight(1f), supporting = "비우면 안 씀")
                }
                val example = listOf(40.0, 10.0).mapNotNull { w ->
                    val low = lowerRule.band(w) ?: return@mapNotNull null
                    val high = (upperRule ?: lowerRule).band(w) ?: return@mapNotNull null
                    val p = { v: Double -> MoneyFormat.decimal(v, 2) }
                    if (upperRule == null) {
                        "목표 ${p(w)}% → ±${p(low)}%p"
                    } else {
                        "목표 ${p(w)}% → +${p(high)}%p / −${p(low)}%p (${p(maxOf(0.0, w - low))}~${p(w + high)}%)"
                    }
                }
                Text(
                    "허용 편차 = min(A, 목표 × R). 목표 0% 종목은 강조하지 않습니다." +
                        if (example.isNotEmpty()) "\n예: " + example.joinToString(", ") else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 매수 단위 일괄 적용
        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = appCardColors()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("매수 단위 일괄 적용", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppFilterChip(selected = !bulkMoney, label = "1주 단위", onClick = { bulkMoney = false })
                    AppFilterChip(selected = bulkMoney, label = "금액 단위", onClick = { bulkMoney = true })
                }
                if (bulkMoney) {
                    MoneyField("금액 단위 (원)", bulkText, { bulkText = it })
                }
                OutlinedButton(
                    onClick = { stockKeys.forEach { k -> unitTexts[k] = if (bulkMoney) bulkText else "" } },
                    enabled = !bulkMoney || (MoneyFormat.parseDecimal(bulkText) ?: 0.0) > 0,
                ) { Text("모든 종목에 적용") }
                Text(
                    "적용한 뒤에도 아래에서 종목마다 다시 고칠 수 있습니다. 매수 단위를 비우면 1주 단위입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("목표 비중 (%)", style = MaterialTheme.typography.titleMedium)
        Text(
            "아직 안 산 종목(상장 전 공모주, 장외 채권 등)은 아래 [+ 계좌에 없는 종목 직접 추가]로 넣을 수 있습니다. 비중에서 뺄 종목은 [제외]를 고르세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        keys.forEach { k ->
            val off = k in excluded
            val isCash = k == AllocationCalculator.CASH
            val unheld = !isCash && k !in held
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (isCash) "예수금" else items[k].orEmpty()) + when {
                            off -> " (제외)"
                            unheld -> " (미보유)"
                            else -> ""
                        } + if (!off && k in noBuy) " · 지금 매수 안 함" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    if (!isCash && !k.startsWith("id:") && !AllocationCalculator.isCodeless(k)) {
                        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!off) {
                    OutlinedTextField(
                        value = texts[k].orEmpty(),
                        onValueChange = { v -> texts[k] = v.filter { it.isDigit() || it == '.' }.take(6) },
                        placeholder = { Text("0") },
                        suffix = { Text("%") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(96.dp),
                    )
                }
                if (unheld) {
                    IconButton(onClick = {
                        manual.remove(k)
                        texts.remove(k)
                        unitTexts.remove(k)
                        priceTexts.remove(k)
                    }) { Icon(Icons.Filled.Close, contentDescription = "${items[k]} 빼기") }
                }
                if (!isCash && !unheld) {
                    Row(
                        Modifier.clickable { if (off) excluded.remove(k) else excluded.add(k) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = off, onCheckedChange = { if (it) excluded.add(k) else excluded.remove(k) })
                        Text("제외", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (!isCash && !off) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = unitTexts[k].orEmpty(),
                        onValueChange = { v -> unitTexts[k] = v.filter { it.isDigit() }.take(9) },
                        label = { Text("매수 단위") },
                        placeholder = { Text(if (k in goldKeys) "1g" else "1주") },
                        suffix = { Text("원") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    if (unheld) {
                        OutlinedTextField(
                            value = priceTexts[k].orEmpty(),
                            onValueChange = { v -> priceTexts[k] = v.filter { it.isDigit() || it == '.' }.take(12) },
                            label = { Text("현재가") },
                            placeholder = { Text("모름") },
                            suffix = { Text(if (isForeignKey(k)) "$" else "원") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    Modifier.clickable { if (k in noBuy) noBuy.remove(k) else noBuy.add(k) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = k in noBuy, onCheckedChange = { if (it) noBuy.add(k) else noBuy.remove(k) })
                    Text("지금 매수 안 함 (계산기에서 빼기)", style = MaterialTheme.typography.bodyMedium)
                }
                if (AllocationCalculator.isCodeless(k) && relinkTargets.isNotEmpty()) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { open = true }) { Text("계좌 종목으로 바꾸기 ▾") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            relinkTargets.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(items[t].orEmpty()) },
                                    onClick = {
                                        // 목표 %·매수 단위를 실제 종목으로 옮긴다
                                        texts[t] = texts[k].orEmpty()
                                        unitTexts[k]?.let { unitTexts[t] = it }
                                        texts.remove(k)
                                        unitTexts.remove(k)
                                        manual.remove(k)
                                        priceTexts.remove(k)
                                        open = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (adding) {
            val addKey = addCode.trim().takeIf { it.isNotEmpty() }?.let { AllocationCalculator.manualKey(addForeign, it) }
            val addProblem = if (addKey != null && addKey in items) "이미 목록에 있는 종목입니다." else null
            val canAdd = (addName.isNotBlank() || addCode.isNotBlank()) && addProblem == null && !bad(addPrice)
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = appCardColors()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("계좌에 없는 종목 직접 추가", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppFilterChip(selected = addForeign, label = "해외", onClick = { addForeign = true })
                        AppFilterChip(selected = !addForeign, label = "국내", onClick = { addForeign = false })
                    }
                    TextInput(if (addForeign) "종목 이름 (예: Invesco QQQM)" else "종목 이름 (예: 삼성전자, 장외 국채 2030)", addName, { addName = it }, maxLength = 60)
                    OutlinedTextField(
                        value = addCode,
                        onValueChange = { v -> addCode = v.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.take(20) },
                        label = { Text("종목코드 (없으면 비워 두기)") },
                        placeholder = { Text(if (addForeign) "예: QQQM" else "예: 005930") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NumberField(
                        "현재가 (선택, ${if (addForeign) "달러" else "원"})",
                        addPrice,
                        { addPrice = it },
                        preview = { if (addForeign) MoneyFormat.usd(it) else MoneyFormat.won(it) },
                    )
                    Text(
                        "종목코드가 있으면 나중에 사서 계좌에 생길 때 자동으로 연결됩니다. 없으면 [계좌 종목으로 바꾸기]로 연결하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    addProblem?.let { WarningText(it) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { adding = false }) { Text("취소") }
                        TextButton(
                            onClick = {
                                val key = addKey ?: AllocationCalculator.codelessKey(addForeign, System.currentTimeMillis().toString(36))
                                manual[key] = addName.trim().ifBlank { addCode.trim().uppercase() }
                                if (addPrice.isNotBlank()) priceTexts[key] = addPrice.trim()
                                addCode = ""
                                addName = ""
                                addPrice = ""
                                adding = false
                            },
                            enabled = canAdd,
                        ) { Text("추가") }
                    }
                }
            }
        } else {
            TextButton(onClick = { adding = true }) { Text("+ 계좌에 없는 종목 직접 추가") }
        }

        if (keys.isNotEmpty()) {
            val left = 100 - sum
            Text(
                "합계 ${MoneyFormat.decimal(sum, 2)}%" + when {
                    abs(left) <= 0.01 -> " ✓"
                    left > 0 -> " · ${MoneyFormat.decimal(left, 2)}% 남음"
                    else -> " · ${MoneyFormat.decimal(-left, 2)}% 넘음"
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (abs(left) <= 0.01) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
        problem?.let { WarningText(it) }
        Button(
            onClick = {
                fun numbers(map: Map<String, String>, allowed: (String) -> Boolean) = map
                    .filterKeys(allowed)
                    .mapValues { MoneyFormat.parseDecimal(it.value) ?: 0.0 }
                    .filterValues { it > 0 }
                onSave(
                    AllocationTarget(
                        id = initial?.id.orEmpty(),
                        name = name.trim(),
                        accountIds = accountIds.toList(),
                        targets = keys.filter { percent(it) > 0 }.associateWith { percent(it) },
                        includeCash = includeCash,
                        threshold = absolute,
                        relative = relative,
                        upper = upperRule,
                        createdAt = initial?.createdAt ?: 0L,
                        // 지금 대상 계좌에 있는 종목만 제외 목록에 남긴다
                        excluded = excluded.filter { it in held },
                        // 직접 추가한 이름·현재가는 아직 계좌에 없는 종목만 (사서 계좌에 생기면 종목 이름·시세를 쓴다)
                        names = manual.filterKeys { it !in held },
                        prices = numbers(priceTexts) { it !in held && it in items },
                        units = numbers(unitTexts) { it in items },
                        noBuy = noBuy.filter { it in items && it !in excluded },
                    ),
                )
            },
            enabled = problem == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장") }
        if (onDelete != null) DeleteButton("비중 설정 삭제", onClick = { confirmDelete = true })
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "비중 설정 삭제",
            text = "이 비중 설정을 삭제할까요? 종목·기록은 그대로입니다.",
            confirmLabel = "삭제",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}
