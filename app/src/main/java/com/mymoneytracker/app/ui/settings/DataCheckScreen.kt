package com.mymoneytracker.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.ConfirmDialog
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.core.portfolio.DataCheck
import com.mymoneytracker.core.portfolio.PortfolioCalculator

private fun levelIcon(level: DataCheck.Level): String = when (level) {
    DataCheck.Level.ERROR -> "🔴"
    DataCheck.Level.MISSING -> "🟠"
    DataCheck.Level.REVIEW -> "⚪"
}

/**
 * 데이터 점검 (v2.9): 계산이 틀어지거나 숫자가 비었을 수 있는 곳. 줄을 누르면 [onOpen] 으로 고칠 곳에 가고 (기록이 여러 개면 [recordId]),
 * 무시한 항목은 흐리게 남아 [다시 보기] 로 되돌린다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataCheckScreen(
    viewModel: PortfolioViewModel,
    onBack: () -> Unit,
    onOpen: (issue: DataCheck.Issue, recordId: String?) -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<DataCheck.Issue?>(null) }
    // [매수 먼저 계산] 확인 중인 매도와 새 순서 (v2.12)
    var reordering by remember { mutableStateOf<Pair<DataCheck.Issue, PortfolioCalculator.BuyFirst>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle("데이터 점검") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        val current = data
        if (current == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "계산이 틀어지거나 숫자가 비었을 수 있는 곳입니다. 줄을 누르면 고칠 곳으로 갑니다. " +
                        "문제가 없는 경우라면 [무시]하세요. 무시한 항목은 흐리게 남고 [다시 보기]로 되돌릴 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current.checks.isEmpty()) {
                item { SectionCard { Text("점검할 것이 없습니다.", style = MaterialTheme.typography.bodyLarge) } }
            }
            for (level in DataCheck.Level.entries) {
                val items = current.checks.filter { it.kind.level == level }
                if (items.isEmpty()) continue
                val ignoredCount = items.count { it.key in current.ignoredChecks }
                item(key = level.name) {
                    SectionCard(
                        title = "${levelIcon(level)} ${level.label} ${items.size - ignoredCount}건" +
                            if (ignoredCount > 0) " · 무시 ${ignoredCount}건" else "",
                    ) {
                        items.forEachIndexed { index, issue ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            val buyFirst = if (issue.kind == DataCheck.Kind.SHORT_SELL) {
                                current.records.firstOrNull { it.id == issue.recordIds.firstOrNull() }
                                    ?.let { PortfolioCalculator.buyFirstOrder(current.records, it) }
                            } else {
                                null
                            }
                            IssueRow(
                                issue = issue,
                                ignored = issue.key in current.ignoredChecks,
                                onOpen = { recordId -> onOpen(issue, recordId) },
                                onIgnore = { confirming = issue },
                                onRestore = { viewModel.setCheckIgnored(issue.key, false) },
                                onBuyFirst = buyFirst?.let { b -> { reordering = issue to b } },
                            )
                        }
                    }
                }
            }
        }
    }

    reordering?.let { (issue, buyFirst) ->
        ConfirmDialog(
            title = "매수를 먼저 계산할까요?",
            text = "${issue.detail}\n\n같은 날 매수 ${buyFirst.buys}건을 이 매도보다 먼저 계산합니다 (이 매도의 계산 순서만 바꿉니다). " +
                "옮겨도 수량이 모자라면 경고는 남습니다.",
            confirmLabel = "매수 먼저 계산",
            onConfirm = { issue.recordIds.firstOrNull()?.let { viewModel.setRecordCreatedAt(it, buyFirst.createdAt) } },
            onDismiss = { reordering = null },
        )
    }

    confirming?.let { issue ->
        ConfirmDialog(
            title = "이 항목을 무시할까요?",
            text = "${issue.kind.title} · ${issue.detail}\n\n${issue.kind.ignoreEffect}\n\n" +
                "무시한 항목은 이 화면에 흐리게 남고 [다시 보기]로 되돌릴 수 있습니다. 홈 알림 개수에서는 빠집니다.",
            confirmLabel = "무시",
            onConfirm = { viewModel.setCheckIgnored(issue.key, true) },
            onDismiss = { confirming = null },
        )
    }
}

/** 점검 한 줄: 항목 이름·설명 (누르면 고칠 곳), 오른쪽 [무시] / [다시 보기]. 기록이 여러 개(중복 의심)면 기록마다 열기. */
@Composable
private fun IssueRow(
    issue: DataCheck.Issue,
    ignored: Boolean,
    onOpen: (recordId: String?) -> Unit,
    onIgnore: () -> Unit,
    onRestore: () -> Unit,
    onBuyFirst: (() -> Unit)? = null,
) {
    val faded = Modifier.alpha(if (ignored) 0.45f else 1f)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(faded.weight(1f).clickable { onOpen(null) }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    issue.kind.title + if (ignored) "  · 무시함" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (ignored) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(issue.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = if (ignored) onRestore else onIgnore) { Text(if (ignored) "다시 보기" else "무시") }
        }
        if (onBuyFirst != null && !ignored) {
            // 같은 날 뒤에 매수가 있는 매도: 매수를 먼저 계산하도록 순서를 바꾼다 (v2.12)
            OutlinedButton(onClick = onBuyFirst, modifier = Modifier.padding(bottom = 6.dp)) { Text("매수 먼저 계산") }
        }
        if (issue.recordIds.size > 1) {
            Row(faded.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                issue.recordIds.forEachIndexed { n, id ->
                    TextButton(onClick = { onOpen(id) }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("기록 ${n + 1} 열기") }
                }
            }
        }
    }
}
