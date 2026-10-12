package com.mymoneytracker.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.AssetType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.sp
import com.mymoneytracker.core.MoneyFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

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

/** 초록 강조 카드 안인지 (라벨 글자색을 카드 글자색 계열로 바꿔 대비를 확보한다). */
val LocalOnHero = androidx.compose.runtime.staticCompositionLocalOf { false }

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
            androidx.compose.runtime.CompositionLocalProvider(LocalOnHero provides highlighted) { content() }
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
        // 다크 모드에서 inverseSurface 는 밝은 색이라 튄다 → 다크 모드는 진녹색 강조 카드와 같게
        colors = if (isSystemInDarkTheme()) {
            CardDefaults.cardColors(containerColor = LocalMoneyColors.current.heroContainer, contentColor = LocalMoneyColors.current.onHero)
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            )
        },
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
    /** 칸이 좁아 다 안 들어갈 때 보여줄 짧은 값 (예: "329.1만원"). */
    shortValue: String? = null,
) {
    val labelColor = if (onHero) LocalMoneyColors.current.onHero.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .then(
                if (onHero) Modifier else Modifier.background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium),
            )
            .padding(horizontal = if (onHero) 0.dp else 12.dp, vertical = if (onHero) 0.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
        AutoShrinkText(value, MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = valueColor, fallback = shortValue)
    }
}

/**
 * 한 줄 글자. 칸보다 길면 글자를 조금씩 줄이고(최소 70%), 그래도 넘치면 [fallback](짧은 표현)으로 바꾼다.
 */
@Composable
fun AutoShrinkText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fallback: String? = null,
    textAlign: TextAlign? = null,
) {
    var scale by remember(text) { mutableFloatStateOf(1f) }
    var useFallback by remember(text) { mutableStateOf(false) }
    val shown = if (useFallback && fallback != null) fallback else text
    Text(
        shown,
        modifier = modifier,
        style = style.copy(fontSize = style.fontSize * scale),
        color = color,
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow) {
                when {
                    scale > 0.72f -> scale -= 0.07f
                    fallback != null && !useFallback -> useFallback = true
                }
            }
        },
    )
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

/**
 * 목록 앞의 둥근 사각 배지 (계좌 종류, 종목 티커). 폭을 고정해 글자 수와 상관없이 크기가 같고,
 * 뒤에 오는 이름의 시작 위치도 같다. 긴 글자(5~6자)는 글자 크기를 줄인다.
 */
@Composable
fun LabelBadge(text: String, container: Color, content: Color, modifier: Modifier = Modifier, width: Int = 44) {
    val base = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
    val style = when {
        text.length >= 6 -> base.copy(fontSize = base.fontSize * 0.8f, letterSpacing = (-0.2).sp)
        text.length == 5 -> base.copy(fontSize = base.fontSize * 0.88f)
        else -> base
    }
    Box(
        modifier = modifier
            .width(width.dp)
            .heightIn(min = 36.dp)
            .background(container, MaterialTheme.shapes.small)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, color = content, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** 티커 배지 자리를 맞추는 회색 배지 (예수금·아직 안 산 종목 등, 이름 시작 위치를 맞추려고). */
@Composable
fun PlaceholderBadge(text: String) {
    LabelBadge(text.take(6), MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.onSurfaceVariant, width = TICKER_BADGE_WIDTH)
}

/** 티커 배지 폭 (dp) — 6자 코드(예: 360750, GOOGL)가 들어가는 고정 폭. */
const val TICKER_BADGE_WIDTH = 56

/** 티커 색 구분: 국내 주식 / 해외 주식 / ETF / 펀드·채권. */
enum class TickerGroup(val label: String) { KR_STOCK("국내"), US_STOCK("해외"), ETF("ETF"), OTHER("펀드·채권") }

fun tickerGroup(market: Market, assetType: AssetType): TickerGroup = when {
    assetType == AssetType.ETF -> TickerGroup.ETF
    assetType == AssetType.STOCK && market == Market.KR -> TickerGroup.KR_STOCK
    assetType == AssetType.STOCK -> TickerGroup.US_STOCK
    else -> TickerGroup.OTHER
}

/** 배지 바탕색 (흰 글자 대비 4.5:1 이상, 색각 이상 구분 검사 통과). 다크 모드는 다크 바탕에 맞춘 값. */
@Composable
fun tickerColor(group: TickerGroup): Color {
    val dark = isSystemInDarkTheme()
    return when (group) {
        TickerGroup.KR_STOCK -> if (dark) Color(0xFF18805F) else Color(0xFF0B7A5E)
        TickerGroup.US_STOCK -> if (dark) Color(0xFF6A5FD9) else Color(0xFF5A4FCF)
        TickerGroup.ETF -> if (dark) Color(0xFFA85D0A) else Color(0xFFA35A08)
        TickerGroup.OTHER -> if (dark) Color(0xFF5E6B65) else Color(0xFF5B6B64)
    }
}

/** 종목 티커 배지: 코드가 있으면 코드, 없으면 이름 앞 글자. 색으로 국내·해외·ETF 를 구분한다. */
@Composable
fun TickerBadge(code: String, name: String, market: Market, assetType: AssetType) {
    val text = code.ifBlank { name.take(2) }.take(6)
    LabelBadge(text, tickerColor(tickerGroup(market, assetType)), Color.White, width = TICKER_BADGE_WIDTH)
}

/** 티커 색 범례. [groups] 에 있는 것만 보여준다. */
@Composable
fun TickerLegend(groups: Set<TickerGroup>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TickerGroup.entries.filter { it in groups }.forEach { group ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).background(tickerColor(group), RoundedCornerShape(3.dp)))
                Text(group.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
        val labelColor = if (LocalOnHero.current) LocalMoneyColors.current.onHero.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurfaceVariant
        Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor)
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
    // 이름은 보통 굵기·한 줄 말줄임, 금액은 굵게 (금액이 먼저 눈에 들어오도록)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                // 줄바꿈으로 나눈 설명(계좌 탭의 "종류·번호 / 투자금·종목")은 줄 수만큼 보여준다
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitle.count { it == '\n' } + 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 금액은 줄이지 않고 끝까지 보여준다 (이름 쪽이 말줄임)
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false)
            if (subValue != null) {
                Text(subValue, style = MaterialTheme.typography.labelLarge, color = subValueColor, maxLines = 1, softWrap = false)
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
