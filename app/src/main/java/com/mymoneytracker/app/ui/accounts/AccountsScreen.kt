package com.mymoneytracker.app.ui.accounts

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.ListRow
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.PlaceholderContent
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.core.MoneyFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    viewModel: PortfolioViewModel,
    onOpenAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("계좌") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddAccount,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("계좌 추가") },
            )
        },
    ) { padding ->
        val current = summary
        when {
            current == null -> LoadingBox(Modifier.padding(padding))
            current.accounts.isEmpty() -> PlaceholderContent(
                "투자 계좌가 없습니다",
                "계좌 추가를 눌러 증권 계좌를 등록하세요.",
                Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            ) {
                items(current.accounts, key = { it.account.id }) { account ->
                    ListRow(
                        title = account.account.name,
                        subtitle = "투자금 ${MoneyFormat.won(account.investedKrw)} · 종목 ${account.holdings.count { it.position.quantity > 0 }}개",
                        value = MoneyFormat.won(account.valueKrw),
                        subValue = "${MoneyFormat.signedWon(account.profitKrw)} (${MoneyFormat.percent(account.returnRate)})",
                        subValueColor = profitColor(account.profitKrw),
                        onClick = { onOpenAccount(account.account.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
