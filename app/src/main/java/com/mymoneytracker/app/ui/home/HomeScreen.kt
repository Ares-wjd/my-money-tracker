package com.mymoneytracker.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.LabeledValue
import com.mymoneytracker.app.ui.common.ListRow
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.core.MoneyFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: PortfolioViewModel,
    onOpenAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("홈") }) }) { padding ->
        val current = data
        if (current == null) {
            LoadingBox(Modifier.padding(padding))
        } else {
            HomeContent(current, padding, onOpenAccount, onAddAccount, onOpenSettings)
        }
    }
}

@Composable
private fun HomeContent(
    current: PortfolioData,
    padding: PaddingValues,
    onOpenAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val summary = current.summary
    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (current.accounts.isEmpty()) {
            item {
                SectionCard(title = "첫 투자 계좌를 등록해 보세요") {
                    Text(
                        "계좌를 만들고 입금·매수 기록을 남기면 투자금과 수익률이 자동으로 계산됩니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onAddAccount) { Text("계좌 추가") }
                }
            }
            return@LazyColumn
        }

        item {
            SectionCard(highlighted = true) {
                Text("투자 자산 평가금", style = MaterialTheme.typography.labelLarge)
                Text(
                    MoneyFormat.won(summary.valueKrw),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${MoneyFormat.signedWon(summary.profitKrw)} (${MoneyFormat.percent(summary.returnRate)})",
                    color = profitColor(summary.profitKrw),
                    style = MaterialTheme.typography.titleMedium,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LabeledValue("투자금", MoneyFormat.won(summary.investedKrw))
                LabeledValue(
                    "수익률 (배당 포함)",
                    MoneyFormat.percent(summary.returnRate),
                    valueColor = profitColor(summary.returnRate),
                )
                LabeledValue(
                    "수익률 (배당 미포함)",
                    MoneyFormat.percent(summary.returnRateExDividends),
                    valueColor = profitColor(summary.returnRateExDividends),
                )
                LabeledValue("누적 배당", MoneyFormat.won(summary.dividendsKrw))
            }
        }

        if (summary.missingFx || summary.missingPrice) {
            item {
                SectionCard {
                    if (summary.missingFx) {
                        WarningText("달러 자산이 있지만 환율이 없어 원화 합계에서 빠져 있습니다.")
                        TextButton(onClick = onOpenSettings) { Text("설정에서 환율 입력") }
                    }
                    if (summary.missingPrice) {
                        WarningText("현재가가 없는 종목이 있어 평가금에서 빠져 있습니다. 종목 화면에서 가격을 입력하세요.")
                    }
                }
            }
        }

        item { Text("계좌별", style = MaterialTheme.typography.titleMedium) }
        items(summary.accounts, key = { it.account.id }) { account ->
            ListRow(
                title = account.account.name,
                subtitle = "투자금 ${MoneyFormat.won(account.investedKrw)}",
                value = MoneyFormat.won(account.valueKrw),
                subValue = MoneyFormat.percent(account.returnRate),
                subValueColor = profitColor(account.returnRate),
                onClick = { onOpenAccount(account.account.id) },
            )
        }
    }
}
