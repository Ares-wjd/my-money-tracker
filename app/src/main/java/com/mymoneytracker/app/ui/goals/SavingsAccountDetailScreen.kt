package com.mymoneytracker.app.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.common.DateField
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.goals.GoalProgress
import com.mymoneytracker.core.goals.SavingsSummary
import com.mymoneytracker.core.model.GoalType
import com.mymoneytracker.core.model.SavingsAccount
import java.time.LocalDate
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsAccountDetailScreen(
    viewModel: GoalsViewModel,
    accountId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddGoal: () -> Unit,
    onOpenGoal: (String) -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val summary = data?.summary(accountId)
    var editBalance by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(summary?.account?.name ?: "목적통장") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "통장 편집") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddGoal,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("목표 추가") },
            )
        },
    ) { padding ->
        if (summary == null) {
            LoadingBox(Modifier.padding(padding))
        } else {
            SavingsDetailContent(summary, padding, onEditBalance = { editBalance = true }, onOpenGoal = onOpenGoal)
            if (editBalance) {
                BalanceDialog(
                    account = summary.account,
                    onDismiss = { editBalance = false },
                    onSave = { balance, date ->
                        viewModel.saveAccount(summary.account.copy(balance = balance, balanceDate = date))
                        editBalance = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SavingsDetailContent(
    summary: SavingsSummary,
    padding: PaddingValues,
    onEditBalance: () -> Unit,
    onOpenGoal: (String) -> Unit,
) {
    val account = summary.account
    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionCard(highlighted = true) {
                Text("이번 달 넣어야 할 금액", style = MaterialTheme.typography.labelLarge)
                Text(
                    MoneyFormat.won(summary.totalMonthlyPayment),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                LabeledValue(
                    "통장 잔액",
                    MoneyFormat.won(account.balance) + (account.balanceDate?.let { " (${formatDate(it)})" } ?: ""),
                )
                LabeledValue("기대수익률", "연 ${MoneyFormat.decimal(account.annualRate * 100, 2)}%")
                LabeledValue("목표 합계", MoneyFormat.won(summary.totalGoalAmount))
                if (summary.unallocated > 0) LabeledValue("배분 후 남는 잔액", MoneyFormat.won(summary.unallocated))
                TextButton(onClick = onEditBalance) { Text("현재 잔액 입력") }
            }
        }
        item {
            Text(
                "날짜가 가까운 목표부터 잔액을 채웁니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (summary.goals.isEmpty()) {
            item {
                Text(
                    "목표가 없습니다. 목표 추가를 눌러 등록하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(summary.goals, key = { it.goal.id }) { progress ->
            GoalCard(progress, onClick = { onOpenGoal(progress.goal.id) })
        }
    }
}

@Composable
private fun GoalCard(progress: GoalProgress, onClick: () -> Unit) {
    val goal = progress.goal
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(goal.name, style = MaterialTheme.typography.titleMedium)
            Text(
                if (goal.type == GoalType.RECURRING) {
                    "${goal.intervalMonths}개월마다 · 다음 ${formatDate(progress.nextDueDate)}"
                } else {
                    "1회 · ${formatDate(progress.nextDueDate)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { progress.achievedRate.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            LabeledValue(
                "모은 금액",
                "${MoneyFormat.won(progress.allocated)} / ${MoneyFormat.won(goal.amount)} (${(progress.achievedRate * 100).roundToLong()}%)",
            )
            LabeledValue(
                "남은 기간",
                if (progress.monthsLeft == 0) "목표일 지남" else "${progress.monthsLeft}개월",
            )
            LabeledValue("매달 넣을 금액", MoneyFormat.won(progress.monthlyPayment), bold = true)
            if (progress.overdue) WarningText("목표일이 지났습니다. 남은 금액을 바로 채우거나 목표를 수정하세요.")
        }
    }
}

@Composable
private fun BalanceDialog(
    account: SavingsAccount,
    onDismiss: () -> Unit,
    onSave: (Double, LocalDate) -> Unit,
) {
    var balanceText by rememberSaveable { mutableStateOf(account.balance.roundToLong().toString()) }
    var day by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val balance = MoneyFormat.parseDecimal(balanceText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("현재 잔액 입력") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CMA·채권 등을 합친 현재 금액을 입력하세요.", style = MaterialTheme.typography.bodySmall)
                NumberField("잔액 (원)", balanceText, { balanceText = it }, allowDecimal = false, preview = { MoneyFormat.won(it) })
                DateField("기준일", LocalDate.ofEpochDay(day), { day = it.toEpochDay() })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { balance?.let { onSave(it, LocalDate.ofEpochDay(day)) } },
                enabled = balance != null && balance >= 0,
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
