package com.mymoneytracker.app.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.app.ui.common.AppFilterChip
import com.mymoneytracker.app.ui.common.ListRow
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.formatDate
import com.mymoneytracker.app.ui.records.recordAmountText
import com.mymoneytracker.app.ui.records.recordDetailText
import com.mymoneytracker.app.ui.records.recordTitle
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.RecordFilter

// 계좌 상세의 기록: 유형 칩 · 기록 목록.

/** 유형 칩: 여러 개 선택, [전체]는 모두 켜져 있으면 전체 해제·아니면 전체 선택. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordTypeChips(presentTypes: List<RecordType>, hiddenTypes: Set<RecordType>, onHiddenTypesChange: (Set<RecordType>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppFilterChip(
            selected = RecordFilter.allShown(presentTypes, hiddenTypes),
            label = "전체",
            onClick = { onHiddenTypesChange(RecordFilter.toggleAll(presentTypes, hiddenTypes)) },
        )
        presentTypes.forEach { type ->
            AppFilterChip(
                selected = type !in hiddenTypes,
                label = RecordFilter.label(type),
                onClick = { onHiddenTypesChange(RecordFilter.toggle(type, hiddenTypes)) },
            )
        }
    }
}

/** 기록 목록 ([visible] 건까지, 더 보기): 누적 투자금, 계산되지 않는 매도 표시. */
@Composable
internal fun RecordListCard(
    records: List<Record>,
    visible: Int,
    onShowMore: () -> Unit,
    data: PortfolioData,
    accountId: String,
    cumulativeInvested: Map<String, Double>,
    shortIds: Set<String>,
    onOpenRecord: (String) -> Unit,
) {
    SectionCard {
        records.take(visible).forEachIndexed { index, record ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(
                title = recordTitle(record, data, accountId),
                subtitle = listOfNotNull(formatDate(record.date), recordDetailText(record, data)).joinToString(" · "),
                value = recordAmountText(record, data, accountId),
                subValue = cumulativeInvested[record.id]?.let { "누적 투자금 ${MoneyFormat.won(it)}" },
                subValueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onOpenRecord(record.id) },
            )
            if (record.id in shortIds) {
                Text(
                    "⚠ 보유 수량 부족 · 계산 안 됨",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        if (records.size > visible) {
            TextButton(onClick = onShowMore) { Text("기록 더 보기 (${records.size - visible}건 남음)") }
        }
    }
}
