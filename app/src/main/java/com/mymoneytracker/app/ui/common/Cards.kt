package com.mymoneytracker.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymoneytracker.app.ui.theme.LocalMoneyColors

// 여러 화면이 같이 쓰는 카드·정보 표시: 흰 카드·강조 카드, 수익 알약, 정보 칸, "라벨 … 값" 줄, 목록 한 줄, 진행 막대.

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
