package com.mymoneytracker.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import com.mymoneytracker.app.ui.theme.Brand
import com.mymoneytracker.app.ui.theme.LocalMoneyColors
import com.mymoneytracker.core.model.AccountKind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mymoneytracker.core.MoneyFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy.M.d (E)", Locale.KOREAN)

fun formatDate(date: LocalDate): String = dateFormatter.format(date)

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

/** 흰 카드 (다크 모드에서는 한 단계 밝은 바탕). highlighted 면 브랜드 색 강조 카드. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val money = LocalMoneyColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = if (highlighted) {
            CardDefaults.cardColors(containerColor = money.heroContainer, contentColor = money.onHero)
        } else {
            appCardColors()
        },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
fun appCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    contentColor = MaterialTheme.colorScheme.onSurface,
)

/** 진한 강조 카드 (목표 탭의 "이번 달 넣을 금액" 등). */
@Composable
fun InkCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/** 강조 카드 안의 수익 알약: "▲ 4,120,000원 · +8.54%" */
@Composable
fun ProfitPill(text: String, value: Double?) {
    Text(
        text,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = profitColor(value),
        style = MaterialTheme.typography.labelLarge,
    )
}

/** 작은 정보 칸 (라벨 위, 값 아래). */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    onHero: Boolean = false,
) {
    val labelColor = if (onHero) LocalMoneyColors.current.onHero.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .then(
                if (onHero) Modifier else Modifier.background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium),
            )
            .padding(horizontal = if (onHero) 0.dp else 12.dp, vertical = if (onHero) 0.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
        Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor)
    }
}

/** 한 줄 알약 선택 (그래프 보기 단위 등). 선택된 항목은 진한 바탕. */
@Composable
fun <T> SegmentedPills(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceContainer,
                        MaterialTheme.shapes.small,
                    )
                    .clickable(role = Role.RadioButton) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 목록 앞의 둥근 사각 배지 (계좌 종류, 종목 티커). */
@Composable
fun LabelBadge(text: String, container: Color, content: Color, modifier: Modifier = Modifier, minWidth: Int = 40) {
    Box(
        modifier = modifier
            .widthIn(min = minWidth.dp)
            .heightIn(min = 36.dp)
            .background(container, MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = content, maxLines = 1)
    }
}

/** 종목 티커 배지: 코드가 있으면 코드, 없으면 이름 앞 글자. */
@Composable
fun TickerBadge(code: String, name: String) {
    val text = code.ifBlank { name.take(2) }.take(6)
    LabelBadge(text, MaterialTheme.colorScheme.inverseSurface, MaterialTheme.colorScheme.inverseOnSurface, minWidth = 52)
}

/** 계좌 종류 배지. */
@Composable
fun AccountKindBadge(kind: AccountKind) {
    val dark = isSystemInDarkTheme()
    val (text, container, content) = when (kind) {
        AccountKind.GENERAL -> Triple("위탁", if (dark) Color(0xFF173B33) else Color(0xFFE6F2EE), if (dark) Color(0xFF7FD9BC) else Color(0xFF0F6B5C))
        AccountKind.ISA -> Triple("ISA", if (dark) Color(0xFF1C2B4A) else Color(0xFFE8EEFB), if (dark) Color(0xFF9CBBFF) else Color(0xFF1F5FD1))
        AccountKind.PENSION -> Triple("연금", if (dark) Color(0xFF3A2C10) else Color(0xFFFDF3E1), if (dark) Color(0xFFF2C46B) else Color(0xFF8A5A08))
        AccountKind.IRP -> Triple("IRP", if (dark) Color(0xFF3A2C10) else Color(0xFFFDF3E1), if (dark) Color(0xFFF2C46B) else Color(0xFF8A5A08))
        AccountKind.DC -> Triple("DC", if (dark) Color(0xFF3A2C10) else Color(0xFFFDF3E1), if (dark) Color(0xFFF2C46B) else Color(0xFF8A5A08))
        AccountKind.OTHER -> Triple("기타", MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LabelBadge(text, container, content)
}

/** 목표 진행 막대. */
@Composable
fun GoalProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val money = LocalMoneyColors.current
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(5.dp)),
    ) {
        if (clamped > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(clamped.coerceAtLeast(0.02f))
                    .height(10.dp)
                    .background(if (clamped >= 1f) money.goalDone else money.goal, RoundedCornerShape(5.dp)),
            )
        }
    }
}

/** 앱 로고 (B · 계단 막대). */
@Composable
fun AppLogo(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 108f
        drawRoundRect(Brand.Ink, cornerRadius = CornerRadius(28f * k))
        fun bar(x: Float, y: Float, h: Float, color: Color) = drawRoundRect(
            color,
            topLeft = Offset(x * k, y * k),
            size = Size(14f * k, h * k),
            cornerRadius = CornerRadius(5f * k),
        )
        bar(27f, 58f, 24f, Color.White)
        bar(47f, 44f, 38f, Color.White)
        bar(67f, 28f, 54f, Brand.Mint)
        drawCircle(Brand.Gold, radius = 5f * k, center = Offset(74f * k, 18f * k))
    }
}

/** "라벨 ............ 값" 한 줄. */
@Composable
fun LabeledValue(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    bold: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun WarningText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** 칩 목록 중 하나를 고르는 선택기. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipSelector(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option)) },
                )
            }
        }
    }
}

/**
 * 숫자 입력창. 쉼표 없이 입력받고, 아래에 읽기 쉬운 형식으로 보여준다.
 * @param allowDecimal 소수점 허용 (달러 금액, 수량 등)
 * @param allowNegative 음수 허용 (예수금 조정)
 */
@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowDecimal: Boolean = true,
    allowNegative: Boolean = false,
    preview: (Double) -> String = { MoneyFormat.decimal(it) },
    isError: Boolean = false,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            var filtered = input.filter { it.isDigit() || (allowDecimal && it == '.') || (allowNegative && it == '-') }
            if (filtered.count { it == '.' } > 1) return@OutlinedTextField
            if (filtered.lastIndexOf('-') > 0) return@OutlinedTextField
            if (filtered.length > 18) filtered = filtered.take(18)
            onValueChange(filtered)
        },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number),
        supportingText = {
            val parsed = MoneyFormat.parseDecimal(value)
            when {
                supporting != null -> Text(supporting)
                parsed != null -> Text(preview(parsed))
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun TextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLength: Int = 100,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        label = { Text(label) },
        singleLine = singleLine,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 날짜 선택 입력창. 누르면 달력이 열린다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = formatDate(date),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("취소") } },
        ) {
            DatePicker(state = state)
        }
    }
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

/** 목록의 한 줄 (앞 배지, 왼쪽 제목·설명, 오른쪽 값·부가값). */
@Composable
fun ListRow(
    title: String,
    subtitle: String?,
    value: String,
    subValue: String? = null,
    subValueColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.titleSmall)
            if (subValue != null) {
                Text(subValue, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = subValueColor)
            }
        }
    }
}

/** 계좌번호 입력창 (숫자와 - 만). */
@Composable
fun AccountNumberField(value: String, modifier: Modifier = Modifier, label: String = "계좌번호 (선택)", onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() || it == '-' }.take(30)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = modifier.fillMaxWidth(),
    )
}

/** 계좌 종류·번호를 한 줄로: "ISA · 123-45-6789" */
fun accountDescription(kindLabel: String, number: String): String =
    listOf(kindLabel, number).filter { it.isNotBlank() }.joinToString(" · ")
