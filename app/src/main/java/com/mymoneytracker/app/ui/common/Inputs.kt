package com.mymoneytracker.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mymoneytracker.core.MoneyFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// 입력창과 선택기: 숫자·금액(빠른 금액 버튼, 쉼표 표시)·글자·날짜·계좌번호 입력, 선택 칩, 칩 선택기, 알약 선택.

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

/** 선택 칩: 선택되면 진녹색 바탕 + ✓ (선택 여부가 바로 보이도록). */
@Composable
fun AppFilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
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
                AppFilterChip(selected = option == selected, label = optionLabel(option), onClick = { onSelect(option) })
            }
        }
    }
}

/**
 * 숫자 입력창. 쉼표 없이 입력받고, 아래에 읽기 쉬운 형식으로 보여준다.
 * @param allowDecimal 소수점 허용 (달러 금액, 수량 등)
 * @param allowNegative 음수 허용 (예수금)
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
    /** false 면 회색으로 잠근다 (값은 표시만) */
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        enabled = enabled,
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

private val QUICK_AMOUNTS = listOf(10_000L, 50_000L, 100_000L, 500_000L, 1_000_000L, 5_000_000L)

/** 숫자만 든 입력값에 쉼표를 붙여 보여준다 (저장되는 값은 숫자만). */
private object ThousandsTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val negative = raw.startsWith("-")
        val digits = if (negative) raw.drop(1) else raw
        val out = StringBuilder()
        if (negative) out.append('-')
        // 원래 위치 → 바뀐 위치
        val map = IntArray(raw.length + 1)
        var o = if (negative) 1 else 0
        map[0] = 0
        if (negative) map[1] = 1
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) { out.append(','); o++ }
            out.append(c); o++
            map[i + 1 + (if (negative) 1 else 0)] = o
        }
        val formatted = out.toString()
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = map[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int): Int {
                val t = offset.coerceIn(0, formatted.length)
                var i = 0
                while (i < raw.length && map[i + 1] <= t) i++
                return i
            }
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}

/**
 * 원화 금액 입력 (정수, 쓰는 동안 쉼표). 아래에 "131만 5,979원" 미리보기.
 * [quick] 이면 [+1만]~[+500만] 버튼과 ✕(비우기) — 딱 떨어지는 금액을 적는 곳(입금·출금·이체, 목표 금액)에만 쓴다.
 */
@Composable
fun MoneyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
    quick: Boolean = false,
    isError: Boolean = false,
    supporting: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                val filtered = input.filter { it.isDigit() || (allowNegative && it == '-') }
                if (filtered.lastIndexOf('-') > 0) return@OutlinedTextField
                onValueChange(filtered.trimStart('0').ifEmpty { if (filtered.isNotEmpty()) "0" else "" }.take(15))
            },
            label = { Text(label) },
            singleLine = true,
            isError = isError,
            visualTransformation = ThousandsTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = if (quick && value.isNotEmpty()) {
                { IconButton(onClick = { onValueChange("") }) { Icon(Icons.Filled.Close, contentDescription = "금액 지우기") } }
            } else {
                null
            },
            supportingText = {
                val parsed = MoneyFormat.parseDecimal(value)
                when {
                    supporting != null -> Text(supporting)
                    parsed != null -> Text(MoneyFormat.manwon(parsed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (quick) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QUICK_AMOUNTS.forEach { n ->
                    AssistChip(
                        onClick = {
                            val current = MoneyFormat.parse(value) ?: 0L
                            onValueChange((current + n).toString())
                        },
                        label = { Text("+${n / 10_000}만", fontWeight = FontWeight.SemiBold) },
                    )
                }
            }
        }
    }
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
