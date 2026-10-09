package com.mymoneytracker.app.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import com.mymoneytracker.app.ui.common.GoalProgressBar
import com.mymoneytracker.app.ui.common.InkCard
import com.mymoneytracker.app.ui.common.appCardColors
import com.mymoneytracker.core.goals.SavingsSummary
import kotlin.math.roundToLong
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.PlaceholderContent
import com.mymoneytracker.core.MoneyFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(
    viewModel: GoalsViewModel,
    onOpenAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("목표") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddAccount,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("목적통장 추가") },
            )
        },
    ) { padding ->
        val current = data
        when {
            current == null -> LoadingBox(Modifier.padding(padding))
            current.accounts.isEmpty() -> PlaceholderContent(
                "목적통장이 없습니다",
                "IT기기금, 지출 통장처럼 목적별 통장을 만들고 목표를 등록하면\n매달 넣어야 할 금액을 계산해 드립니다.",
                Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    InkCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("이번 달 넣을 금액", style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current.copy(alpha = 0.8f))
                                Text(
                                    MoneyFormat.won(current.summaries.sumOf { it.totalMonthlyPayment }),
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("통장 잔액 합계", style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.8f))
                                Text(MoneyFormat.won(current.accounts.sumOf { it.balance }), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
                items(current.summaries, key = { it.account.id }) { summary ->
                    SavingsSummaryCard(summary, onClick = { onOpenAccount(summary.account.id) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavingsSummaryCard(summary: SavingsSummary, onClick: () -> Unit) {
    val account = summary.account
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = appCardColors()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(account.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "연 ${MoneyFormat.decimal(account.annualRate * 100, 2)}% · 잔액 ${MoneyFormat.won(account.balance)}" +
                            (account.balanceDate?.let { " (${it.monthValue}.${it.dayOfMonth})" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "월 ${MoneyFormat.won(summary.totalMonthlyPayment)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (account.subAccounts.size > 1 || account.subAccounts.any { it.number.isNotBlank() }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    account.subAccounts.forEach { sub ->
                        Text(
                            "${sub.name} ${MoneyFormat.won(sub.balance).removeSuffix("원")}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            summary.goals.forEach { progress ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progress.goal.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${(progress.achievedRate * 100).roundToLong()}%", style = MaterialTheme.typography.labelLarge)
                    }
                    GoalProgressBar(progress.achievedRate.toFloat())
                    Row {
                        Text(
                            "${MoneyFormat.won(progress.allocated)} / ${MoneyFormat.won(progress.goal.amount)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text("월 ${MoneyFormat.won(progress.monthlyPayment)}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
