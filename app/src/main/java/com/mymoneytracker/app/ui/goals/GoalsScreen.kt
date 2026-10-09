package com.mymoneytracker.app.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.ListRow
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.PlaceholderContent
import com.mymoneytracker.app.ui.common.SectionCard
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
                    SectionCard(highlighted = true) {
                        Text("이번 달 목적통장 납입액 합계", style = MaterialTheme.typography.labelLarge)
                        Text(
                            MoneyFormat.won(current.summaries.sumOf { it.totalMonthlyPayment }),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        LabeledValue("통장 잔액 합계", MoneyFormat.won(current.accounts.sumOf { it.balance }))
                    }
                }
                items(current.summaries, key = { it.account.id }) { summary ->
                    ListRow(
                        title = summary.account.name,
                        subtitle = "목표 ${summary.goals.size}개 · 잔액 ${MoneyFormat.won(summary.account.balance)}",
                        value = "월 ${MoneyFormat.won(summary.totalMonthlyPayment)}",
                        subValue = "목표 합계 ${MoneyFormat.won(summary.totalGoalAmount)}",
                        onClick = { onOpenAccount(summary.account.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
