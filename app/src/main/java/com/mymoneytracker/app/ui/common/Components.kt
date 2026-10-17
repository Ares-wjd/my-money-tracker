package com.mymoneytracker.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymoneytracker.app.ui.theme.LocalMoneyColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// 여러 화면이 같이 쓰는 작은 도구: 날짜 형식, 수익 색, 로딩, 빈 화면 안내, 경고 글, 추가 버튼 색, 위 막대 제목, 삭제 버튼, 확인창.
// 카드는 Cards.kt, 배지·색은 Badges.kt, 입력창·선택기는 Inputs.kt.

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy.M.d (E)", Locale.KOREAN)

private val shortDateFormatter = DateTimeFormatter.ofPattern("M.d (E)", Locale.KOREAN)

/** 올해 날짜는 짧게 "10.9 (금)", 다른 해는 "2025.10.9 (목)". */
fun formatDate(date: LocalDate): String =
    (if (date.year == LocalDate.now().year) shortDateFormatter else dateFormatter).format(date)

/** 수익은 빨강, 손실은 파랑 (국내 증권 앱 관례). 다크 모드에서는 밝은 톤. */
@Composable
fun profitColor(value: Double?): Color = when {
    value == null || value == 0.0 -> MaterialTheme.colorScheme.onSurface
    value > 0 -> LocalMoneyColors.current.profit
    else -> LocalMoneyColors.current.loss
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 기능 구현 전까지 각 화면 자리를 채우는 임시 화면. */
@Composable
fun PlaceholderContent(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        extra()
    }
}

@Composable
fun WarningText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** 추가 버튼(FAB) 색: 밝은 모드는 진한 잉크색, 다크 모드는 진녹색 (다크 모드에서 흰 버튼이 튀지 않도록). */
@Composable
fun fabContainerColor(): Color =
    if (isSystemInDarkTheme()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.inverseSurface

@Composable
fun fabContentColor(): Color =
    if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.inverseOnSurface

/** 위 막대 제목: 한 줄, 넘치면 말줄임. */
@Composable
fun TopBarTitle(text: String) {
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** 삭제 버튼 (빨간 글씨·테두리). */
@Composable
fun DeleteButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
    ) { Text(text) }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 계좌 종류·번호를 한 줄로: "ISA · 123-45-6789" */
fun accountDescription(kindLabel: String, number: String): String =
    listOf(kindLabel, number).filter { it.isNotBlank() }.joinToString(" · ")
