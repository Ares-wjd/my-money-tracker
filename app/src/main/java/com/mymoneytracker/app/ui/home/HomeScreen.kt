package com.mymoneytracker.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.ApiStatus
import com.mymoneytracker.app.ui.ChartState
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.RefreshState
import com.mymoneytracker.app.ui.common.AccountKindBadge
import com.mymoneytracker.app.ui.common.AppLogo
import com.mymoneytracker.app.ui.common.ProfitPill
import com.mymoneytracker.app.ui.common.StatTile
import com.mymoneytracker.app.ui.common.accountDescription
import com.mymoneytracker.app.ui.common.ListRow
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.WarningText
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.core.MoneyFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: PortfolioViewModel,
    onOpenAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val refresh by viewModel.refresh.collectAsStateWithLifecycle()
    val chart by viewModel.chart.collectAsStateWithLifecycle()
    val apiStatus by viewModel.apiStatus.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppLogo(30.dp)
                        Text("Money Tracker", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    if (refresh.running) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { viewModel.refreshQuotes() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "시세 새로고침")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = data
        if (current == null) {
            LoadingBox(Modifier.padding(padding))
        } else {
            HomeContent(
                current = current,
                refresh = refresh,
                chart = chart,
                apiStatus = apiStatus,
                padding = padding,
                viewModel = viewModel,
                onOpenAccount = onOpenAccount,
                onAddAccount = onAddAccount,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun HomeContent(
    current: PortfolioData,
    refresh: RefreshState,
    chart: ChartState,
    apiStatus: ApiStatus,
    padding: PaddingValues,
    viewModel: PortfolioViewModel,
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
        apiStatus.daysUntilKisExpiry()?.let { days ->
            if (days <= 30) {
                item {
                    SectionCard {
                        WarningText(
                            if (days < 0) {
                                "한국투자증권 Open API 서비스가 만료되었습니다. KIS Developers 에서 갱신하세요."
                            } else {
                                "한국투자증권 Open API 서비스가 ${days}일 뒤 만료됩니다. KIS Developers 에서 갱신하세요 (만료 30일 전부터 가능)."
                            },
                        )
                    }
                }
            }
        }

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
                Text("투자 자산 평가금", style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current.copy(alpha = 0.85f))
                Text(
                    MoneyFormat.won(summary.valueKrw),
                    style = MaterialTheme.typography.headlineMedium,
                )
                ProfitPill(
                    (if (summary.profitKrw >= 0) "▲ " else "▼ ") +
                        MoneyFormat.won(kotlin.math.abs(summary.profitKrw)) + " · " + MoneyFormat.percent(summary.returnRate),
                    summary.profitKrw,
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("투자금", MoneyFormat.won(summary.investedKrw), Modifier.weight(1f), onHero = true)
                    StatTile("배당 미포함", MoneyFormat.percent(summary.returnRateExDividends), Modifier.weight(1f), onHero = true)
                    StatTile("누적 배당", MoneyFormat.won(summary.dividendsKrw), Modifier.weight(1f), onHero = true)
                }
                val footnote = listOfNotNull(
                    current.usdKrw?.let { rate ->
                        "환율 ${MoneyFormat.decimal(rate, 2)}원" + (current.usdKrwDate?.let { " (${formatDate(it)})" } ?: " (직접 입력)")
                    },
                    refresh.lastRefreshedAt?.let { "시세 " + SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(it)) },
                ).joinToString(" · ")
                if (footnote.isNotEmpty()) {
                    Text(
                        footnote,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (summary.missingFx || summary.missingPrice || refresh.errors.isNotEmpty()) {
            item {
                SectionCard {
                    if (summary.missingFx) {
                        WarningText("달러 자산이 있지만 환율이 없어 원화 합계에서 빠져 있습니다.")
                    }
                    if (summary.missingPrice) {
                        WarningText("현재가가 없는 종목이 있어 평가금에서 빠져 있습니다. 종목 화면에서 가격을 입력하세요.")
                    }
                    refresh.errors.take(4).forEach { WarningText(it) }
                    if (summary.missingFx || refresh.errors.isNotEmpty()) {
                        TextButton(onClick = onOpenSettings) { Text("설정 열기") }
                    }
                }
            }
        }

        item {
            AssetChartCard(
                state = chart,
                accounts = current.accounts,
                onSelectInterval = viewModel::selectChartInterval,
                onSelectAccount = viewModel::selectChartAccount,
                onShowEarlier = viewModel::showEarlierChart,
            )
        }

        item {
            SectionCard(title = "계좌") {
                summary.accounts.forEachIndexed { index, account ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(
                        title = account.account.name,
                        subtitle = accountDescription(account.account.kind.label, account.account.number).ifBlank { null },
                        value = MoneyFormat.won(account.valueKrw),
                        subValue = MoneyFormat.percent(account.returnRate),
                        subValueColor = profitColor(account.returnRate),
                        onClick = { onOpenAccount(account.account.id) },
                        leading = { AccountKindBadge(account.account.kind) },
                    )
                }
            }
        }
    }
}
