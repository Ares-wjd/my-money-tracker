package com.mymoneytracker.app.ui.goals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.style.TextOverflow
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.DeleteButton
import com.mymoneytracker.app.ui.common.TextInput
import com.mymoneytracker.core.goals.GoalUnit
import com.mymoneytracker.core.model.SavingsGoal
import com.mymoneytracker.core.model.SavingsGoalGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.common.fabContainerColor
import com.mymoneytracker.app.ui.common.fabContentColor
import com.mymoneytracker.app.ui.common.DateField
import com.mymoneytracker.app.ui.common.GoalProgressBar
import com.mymoneytracker.app.ui.common.appCardColors
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.MoneyField
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.app.ui.theme.LocalMoneyColors
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.goals.GoalProgress
import com.mymoneytracker.core.goals.SavingsSummary
import com.mymoneytracker.core.model.GoalType
import com.mymoneytracker.core.model.SavingsAccount
import com.mymoneytracker.core.model.SavingsSubAccount
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
    // 소비 기록할 목표, 잔액 고칠지 묻기, 편집할 그룹("" = 새 그룹)
    var spendGoalId by rememberSaveable { mutableStateOf<String?>(null) }
    var askBalance by rememberSaveable { mutableStateOf(false) }
    var editGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // 스크롤을 내리면 목표 추가 버튼을 작게 (카드를 덜 가리도록)
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle(summary?.account?.name ?: "목적통장") },
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
                expanded = fabExpanded,
                containerColor = fabContainerColor(),
                contentColor = fabContentColor(),
                icon = { Icon(Icons.Filled.Add, contentDescription = "목표 추가") },
                text = { Text("목표 추가") },
            )
        },
    ) { padding ->
        if (summary == null) {
            LoadingBox(Modifier.padding(padding))
        } else {
            SavingsDetailContent(
                summary = summary,
                padding = padding,
                listState = listState,
                onEditBalance = { editBalance = true },
                onOpenGoal = onOpenGoal,
                onSpend = { spendGoalId = it.id },
                onComplete = { goals ->
                    viewModel.setCompleted(goals, LocalDate.now())
                    askBalance = true
                },
                onUndoComplete = { viewModel.setCompleted(listOf(it), null) },
                onToggleCombined = { group, members -> viewModel.saveGroup(group.copy(combined = !group.combined), members) },
                onEditGroup = { editGroupId = it?.id ?: "" },
            )
            if (editBalance) {
                BalanceDialog(
                    account = summary.account,
                    onDismiss = { editBalance = false },
                    onSave = { subAccounts, date ->
                        viewModel.saveAccount(summary.account.copy(subAccounts = subAccounts, balanceDate = date))
                        editBalance = false
                    },
                )
            }
            spendGoalId?.let { id -> data?.goal(id) }?.let { goal ->
                SpendDialog(
                    goal = goal,
                    onDismiss = { spendGoalId = null },
                    onSave = { date ->
                        viewModel.recordSpending(goal, date)
                        spendGoalId = null
                        askBalance = true
                    },
                )
            }
            if (askBalance) {
                // 돈이 나간 것은 잔액을 고쳐야 반영된다
                AlertDialog(
                    onDismissRequest = { askBalance = false },
                    title = { Text("통장 잔액도 고칠까요?") },
                    text = { Text("돈이 나간 것은 통장 잔액을 고쳐야 목표 계산에 반영됩니다.") },
                    confirmButton = {
                        TextButton(onClick = {
                            askBalance = false
                            editBalance = true
                        }) { Text("잔액 입력") }
                    },
                    dismissButton = { TextButton(onClick = { askBalance = false }) { Text("나중에") } },
                )
            }
            editGroupId?.let { id ->
                val current = data ?: return@let
                GroupDialog(
                    accountId = accountId,
                    existing = current.group(id),
                    goals = current.goals,
                    groups = current.groups,
                    onDismiss = { editGroupId = null },
                    onSave = { group, members ->
                        viewModel.saveGroup(group, members)
                        editGroupId = null
                    },
                    onUngroup = { group ->
                        viewModel.deleteGroup(group.id)
                        editGroupId = null
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
    listState: LazyListState,
    onEditBalance: () -> Unit,
    onOpenGoal: (String) -> Unit,
    onSpend: (SavingsGoal) -> Unit,
    onComplete: (List<SavingsGoal>) -> Unit,
    onUndoComplete: (SavingsGoal) -> Unit,
    onToggleCombined: (SavingsGoalGroup, Set<String>) -> Unit,
    onEditGroup: (SavingsGoalGroup?) -> Unit,
) {
    val account = summary.account
    var showCompleted by rememberSaveable(account.id) { mutableStateOf(false) }
    LazyColumn(
        state = listState,
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
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
                // 금액만 크게, 기준일은 아래 작은 글씨 (괄호가 겹치지 않게)
                LabeledValue("통장 잔액", MoneyFormat.won(account.balance))
                account.balanceDate?.let {
                    Text(
                        "${formatDate(it)} 기준",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMoneyColors.current.onHero.copy(alpha = 0.85f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )
                }
                account.subAccounts.forEach { sub ->
                    LabeledValue(
                        "  · " + listOf(sub.name, sub.number).filter { it.isNotBlank() }.joinToString(" "),
                        MoneyFormat.won(sub.balance),
                    )
                }
                LabeledValue("기대수익률", "연 ${MoneyFormat.decimal(account.annualRate * 100, 2)}%")
                LabeledValue("목표 합계", MoneyFormat.won(summary.totalGoalAmount))
                if (summary.unallocated > 0) LabeledValue("배분 후 남는 잔액", MoneyFormat.won(summary.unallocated))
                // 초록 카드 위에서도 잘 보이도록 테두리 버튼
                OutlinedButton(
                    onClick = onEditBalance,
                    modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalMoneyColors.current.onHero),
                    border = BorderStroke(1.dp, LocalMoneyColors.current.onHero.copy(alpha = 0.7f)),
                ) { Text("현재 잔액 입력") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "날짜가 가까운 목표부터 채우고, 같은 날짜는 금액 비율로 나눕니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // 1회성 목표가 2개 이상이면 그룹으로 묶을 수 있다
                if (summary.goals.count { it.goal.type == GoalType.ONE_TIME } >= 2) {
                    TextButton(onClick = { onEditGroup(null) }) { Text("그룹 만들기") }
                }
            }
        }
        if (summary.units.isEmpty()) {
            item {
                Text(
                    "목표가 없습니다. 목표 추가를 눌러 등록하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(summary.units, key = { unit -> if (unit is GoalUnit.Group) "group:" + unit.group.id else "goal:" + (unit as GoalUnit.Single).progress.goal.id }) { unit ->
            when (unit) {
                is GoalUnit.Single -> GoalCard(
                    unit.progress,
                    onClick = { onOpenGoal(unit.progress.goal.id) },
                    onSpend = { onSpend(unit.progress.goal) },
                    onComplete = { onComplete(listOf(unit.progress.goal)) },
                )
                is GoalUnit.Group -> GroupCard(
                    unit,
                    onOpenGoal = onOpenGoal,
                    onSpend = onSpend,
                    onComplete = onComplete,
                    onToggleCombined = { onToggleCombined(unit.group, unit.members.map { it.goal.id }.toSet()) },
                    onEdit = { onEditGroup(unit.group) },
                )
            }
        }
        if (summary.completed.isNotEmpty()) {
            item {
                TextButton(onClick = { showCompleted = !showCompleted }) {
                    Text("완료한 목표 (${summary.completed.size}) " + if (showCompleted) "▴" else "▾")
                }
            }
            if (showCompleted) {
                items(summary.completed, key = { "done:" + it.id }) { goal ->
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = appCardColors()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(goal.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${MoneyFormat.shortWon(goal.amount)} · ${goal.completedDate?.let(::formatDate).orEmpty()} 완료",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onUndoComplete(goal) }) { Text("되돌리기") }
                        }
                    }
                }
            }
        }
    }
}

private fun schedule(goal: SavingsGoal, due: LocalDate): String =
    if (goal.type == GoalType.RECURRING) {
        val every = if (goal.intervalMonths % 12 == 0) "${goal.intervalMonths / 12}년마다" else "${goal.intervalMonths}개월마다"
        "$every · 다음 ${formatDate(due)}"
    } else {
        "1회성 · ${formatDate(due)}"
    }

/** 진행 막대 옆에 % (한 줄). */
@Composable
private fun ProgressRow(rate: Double) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GoalProgressBar(rate.toFloat(), Modifier.weight(1f).padding(vertical = 4.dp))
        Text("${(rate * 100).roundToLong()}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GoalCard(progress: GoalProgress, onClick: () -> Unit, onSpend: () -> Unit, onComplete: () -> Unit) {
    val goal = progress.goal
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = appCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GoalBody(progress)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    goal.lastSpentDate?.let { "지난 소비 ${formatDate(it)}" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (goal.type == GoalType.RECURRING) {
                    OutlinedButton(onClick = onSpend) { Text("소비 기록") }
                } else {
                    OutlinedButton(onClick = onComplete) { Text("구매 완료") }
                }
            }
        }
    }
}

/** 목표 하나의 이름·일정·막대·금액 (그룹의 개별 계산에서도 쓴다). */
@Composable
private fun GoalBody(progress: GoalProgress) {
    val goal = progress.goal
    Text(goal.name, style = MaterialTheme.typography.titleMedium)
    Text(schedule(goal, progress.nextDueDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ProgressRow(progress.achievedRate)
    LabeledValue("모은 금액", "${MoneyFormat.shortWon(progress.allocated).removeSuffix("원")} / ${MoneyFormat.shortWon(goal.amount)}")
    LabeledValue("남은 기간", if (progress.monthsLeft == 0) "예정일 지남" else "${progress.monthsLeft}개월")
    LabeledValue("매달 넣을 금액", MoneyFormat.won(progress.monthlyPayment), bold = true)
    if (progress.overdue) {
        WarningText(
            if (goal.type == GoalType.RECURRING) "예정일이 지났습니다. 소비했으면 [소비 기록]을 누르세요."
            else "목표일이 지났습니다. 샀으면 [구매 완료]를, 아니면 날짜를 고치세요.",
        )
    }
}

@Composable
private fun GroupCard(
    unit: GoalUnit.Group,
    onOpenGoal: (String) -> Unit,
    onSpend: (SavingsGoal) -> Unit,
    onComplete: (List<SavingsGoal>) -> Unit,
    onToggleCombined: () -> Unit,
    onEdit: () -> Unit,
) {
    val combined = unit.combined
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = appCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(unit.group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("그룹 · ${formatDate(unit.dueDate)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("하나로 계산", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(checked = unit.group.combined, onCheckedChange = { onToggleCombined() }, modifier = Modifier.padding(start = 6.dp))
            }
            if (combined != null) {
                ProgressRow(combined.achievedRate)
                LabeledValue("모은 금액", "${MoneyFormat.shortWon(combined.allocated).removeSuffix("원")} / ${MoneyFormat.shortWon(combined.amount)}")
                LabeledValue("남은 기간", if (combined.monthsLeft == 0) "예정일 지남" else "${combined.monthsLeft}개월")
                LabeledValue("매달 넣을 금액", MoneyFormat.won(combined.monthlyPayment), bold = true)
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                unit.members.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenGoal(m.goal.id) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m.goal.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${MoneyFormat.shortWon(m.allocated).removeSuffix("원")} / ${MoneyFormat.shortWon(m.goal.amount)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // 개별 계산: 각 목표를 원래 날짜로
                unit.members.forEach { m ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.fillMaxWidth().clickable { onOpenGoal(m.goal.id) }) { GoalBody(m) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { onComplete(unit.members.map { it.goal }) }) { Text("그룹 구매 완료") }
                TextButton(onClick = onEdit) { Text("그룹 편집") }
            }
        }
    }
}

/** 반복 목표 소비 기록: 산 날짜만 고른다 (기본 오늘). */
@Composable
private fun SpendDialog(goal: SavingsGoal, onDismiss: () -> Unit, onSave: (LocalDate) -> Unit) {
    var day by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val date = LocalDate.ofEpochDay(day)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${goal.name} 소비 기록") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("실제로 산 날짜를 고르세요. 그날부터 주기(${goal.intervalMonths}개월)를 다시 셉니다.", style = MaterialTheme.typography.bodySmall)
                DateField("소비한 날", date, { day = it.toEpochDay() })
                LabeledValue("다음 지출일", formatDate(date.plusMonths(goal.intervalMonths.toLong().coerceAtLeast(1))), bold = true)
                goal.lastSpentDate?.let { Text("지난 소비 ${formatDate(it)}", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(date) }) { Text("기록") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 그룹 만들기·편집: 이름, 묶을 1회성 목표, 구매 날짜, 계산 방식. */
@Composable
private fun GroupDialog(
    accountId: String,
    existing: SavingsGoalGroup?,
    goals: List<SavingsGoal>,
    groups: List<SavingsGoalGroup>,
    onDismiss: () -> Unit,
    onSave: (SavingsGoalGroup, Set<String>) -> Unit,
    onUngroup: (SavingsGoalGroup) -> Unit,
) {
    // 묶을 수 있는 목표: 이 통장의 진행 중인 1회성 목표 중 다른 그룹에 없는 것
    val candidates = goals.filter { g ->
        g.accountId == accountId && g.type == GoalType.ONE_TIME && !g.completed &&
            (g.groupId == null || g.groupId == existing?.id || groups.none { it.id == g.groupId })
    }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    val selected = remember {
        mutableStateListOf<String>().apply { addAll(candidates.filter { it.groupId != null && it.groupId == existing?.id }.map { it.id }) }
    }
    var pickedDay by rememberSaveable { mutableStateOf(existing?.dueDate?.toEpochDay()) }
    var combined by rememberSaveable { mutableStateOf(existing?.combined ?: true) }
    var confirmUngroup by rememberSaveable { mutableStateOf(false) }
    val earliest = candidates.filter { it.id in selected }.minOfOrNull { it.dueDate }
    val date = pickedDay?.let(LocalDate::ofEpochDay) ?: earliest ?: LocalDate.now().plusYears(1)
    val problem = when {
        name.isBlank() -> "그룹 이름을 입력하세요."
        selected.isEmpty() -> "묶을 목표를 고르세요."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "그룹 만들기" else "그룹 편집") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("그룹 이름 (예: IT 세트)", name, { name = it }, maxLength = 40)
                Text("묶을 목표 (1회성)", style = MaterialTheme.typography.labelLarge)
                if (candidates.isEmpty()) Text("묶을 수 있는 1회성 목표가 없습니다.", style = MaterialTheme.typography.bodySmall)
                candidates.forEach { g ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (g.id in selected) selected.remove(g.id) else selected.add(g.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = g.id in selected, onCheckedChange = { if (it) selected.add(g.id) else selected.remove(g.id) })
                        Column(Modifier.weight(1f)) {
                            Text(g.name, style = MaterialTheme.typography.bodyMedium)
                            Text("${MoneyFormat.shortWon(g.amount)} · ${formatDate(g.dueDate)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                DateField("그룹 구매 날짜", date, { pickedDay = it.toEpochDay() })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("하나로 계산", style = MaterialTheme.typography.bodyMedium)
                        Text("끄면 각 목표를 원래 날짜로 따로 계산", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = combined, onCheckedChange = { combined = it })
                }
                problem?.let { WarningText(it) }
                if (existing != null) DeleteButton("그룹 풀기", onClick = { confirmUngroup = true })
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        SavingsGoalGroup(
                            id = existing?.id.orEmpty(),
                            accountId = accountId,
                            name = name.trim(),
                            dueDate = date,
                            combined = combined,
                            createdAt = existing?.createdAt ?: 0L,
                        ),
                        selected.toSet(),
                    )
                },
                enabled = problem == null,
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
    if (confirmUngroup && existing != null) {
        ConfirmDialog(
            title = "그룹 풀기",
            text = "그룹을 풀까요? 목표는 그대로 남고 원래 날짜로 계산합니다.",
            confirmLabel = "풀기",
            onConfirm = { onUngroup(existing) },
            onDismiss = { confirmUngroup = false },
        )
    }
}

@Composable
private fun BalanceDialog(
    account: SavingsAccount,
    onDismiss: () -> Unit,
    onSave: (List<SavingsSubAccount>, LocalDate) -> Unit,
) {
    val rows = rememberSaveable(saver = subAccountRowsSaver) {
        account.subAccounts.map(SubAccountRow::from).toMutableStateList()
    }
    var day by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val valid = rows.all { it.balance.isBlank() || (MoneyFormat.parseDecimal(it.balance) ?: -1.0) >= 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("현재 잔액 입력") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("계좌별 현재 금액을 입력하세요.", style = MaterialTheme.typography.bodySmall)
                rows.forEachIndexed { index, row ->
                    MoneyField(
                        listOf(row.name, row.number).filter { it.isNotBlank() }.joinToString(" ") + " (원)",
                        row.balance,
                        { rows[index] = row.copy(balance = it) },
                    )
                }
                LabeledValue("합계", MoneyFormat.won(rows.sumOf { MoneyFormat.parseDecimal(it.balance) ?: 0.0 }), bold = true)
                DateField("기준일", LocalDate.ofEpochDay(day), { day = it.toEpochDay() })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(rows.mapIndexed { i, row -> row.toSubAccount(i) }, LocalDate.ofEpochDay(day)) },
                enabled = valid,
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
