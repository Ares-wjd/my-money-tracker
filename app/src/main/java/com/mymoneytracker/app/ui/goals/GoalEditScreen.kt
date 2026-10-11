package com.mymoneytracker.app.ui.goals

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
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.app.ui.common.MoneyField
import com.mymoneytracker.app.ui.common.DeleteButton
import com.mymoneytracker.app.ui.common.ChipSelector
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.DateField
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.goals.GoalCalculator
import com.mymoneytracker.core.model.GoalType
import com.mymoneytracker.core.model.SavingsGoal
import java.time.LocalDate
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalEditScreen(
    viewModel: GoalsViewModel,
    accountId: String,
    goalId: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val existing = goalId?.let { data?.goal(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (goalId == null) "목표 추가" else "목표 편집") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        if (goalId != null && existing == null) {
            LoadingBox(Modifier.padding(padding))
        } else {
            GoalForm(
                accountId = existing?.accountId ?: accountId,
                initial = existing,
                modifier = Modifier.padding(padding),
                onSave = {
                    viewModel.saveGoal(it)
                    onDone()
                },
                onDelete = existing?.let { goal ->
                    {
                        viewModel.deleteGoal(goal.id)
                        onDone()
                    }
                },
            )
        }
    }
}

@Composable
private fun GoalForm(
    accountId: String,
    initial: SavingsGoal?,
    modifier: Modifier,
    onSave: (SavingsGoal) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(initial?.type ?: GoalType.ONE_TIME) }
    var amountText by rememberSaveable { mutableStateOf(initial?.amount?.roundToLong()?.toString().orEmpty()) }
    // 반복 목표는 지금 계산된 다음 지출일을 보여준다
    var day by rememberSaveable {
        mutableLongStateOf((initial?.let { GoalCalculator.nextDueDate(it, LocalDate.now()) } ?: LocalDate.now().plusYears(1)).toEpochDay())
    }
    var intervalText by rememberSaveable { mutableStateOf((initial?.intervalMonths ?: 12).toString()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val amount = MoneyFormat.parseDecimal(amountText)
    val interval = intervalText.toIntOrNull()
    // 저장 버튼이 꺼져 있으면 무엇을 입력해야 하는지 보여준다
    val problem = when {
        name.isBlank() -> "목표 이름을 입력하세요."
        amount == null || amount <= 0 -> "목표 금액을 입력하세요."
        type == GoalType.RECURRING && (interval == null || interval !in 1..600) -> "반복 주기(1~600개월)를 입력하세요."
        else -> null
    }
    val valid = problem == null

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextInput("목표 이름 (예: 노트북, 휴대폰)", name, { name = it }, maxLength = 40)
        ChipSelector("종류", GoalType.entries, type, { it.label }, { type = it })
        MoneyField("목표 금액 (원)", amountText, { amountText = it }, quick = true)
        DateField(
            if (type == GoalType.ONE_TIME) "목표일" else "다음 지출일",
            LocalDate.ofEpochDay(day),
            { day = it.toEpochDay() },
        )
        if (type == GoalType.RECURRING) {
            NumberField(
                "반복 주기 (개월, 예: 24 = 2년마다)",
                intervalText,
                { intervalText = it },
                allowDecimal = false,
                preview = { v -> if (v >= 12 && v % 12 == 0.0) "${(v / 12).toInt()}년마다" else "${v.toInt()}개월마다" },
            )
            Text(
                "실제로 사면 목표 카드의 [소비 기록]을 누르세요. 그날부터 주기를 다시 셉니다. 예정일이 지나도 날짜는 저절로 넘어가지 않습니다." +
                    (initial?.spentDates?.takeIf { it.isNotEmpty() }?.let { dates ->
                        "\n지난 소비: " + dates.takeLast(5).reversed().joinToString(", ") { formatDate(it) }
                    } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        problem?.let { WarningText(it) }
        Button(
            onClick = {
                onSave(
                    SavingsGoal(
                        id = initial?.id.orEmpty(),
                        accountId = accountId,
                        name = name.trim(),
                        type = type,
                        amount = amount ?: 0.0,
                        // 반복 목표에 지난 날짜를 넣으면 주기만큼 더한 날짜로 저장한다
                        dueDate = LocalDate.ofEpochDay(day).let { date ->
                            if (type == GoalType.RECURRING && date.isBefore(LocalDate.now())) {
                                GoalCalculator.advancePast(date, interval ?: 12, LocalDate.now())
                            } else {
                                date
                            }
                        },
                        intervalMonths = interval ?: 12,
                        createdAt = initial?.createdAt ?: 0L,
                        // 그룹·구매 완료는 1회성만, 소비 기록은 반복만
                        groupId = if (type == GoalType.ONE_TIME) initial?.groupId else null,
                        completedDate = if (type == GoalType.ONE_TIME) initial?.completedDate else null,
                        lastSpentDate = if (type == GoalType.RECURRING) initial?.lastSpentDate else null,
                        spentDates = if (type == GoalType.RECURRING) initial?.spentDates.orEmpty() else emptyList(),
                    ),
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장") }
        if (onDelete != null) {
            DeleteButton("목표 삭제", onClick = { confirmDelete = true })
        }
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "목표 삭제",
            text = "이 목표를 삭제할까요?",
            confirmLabel = "삭제",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}
