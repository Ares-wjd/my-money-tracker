package com.mymoneytracker.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymoneytracker.app.ui.theme.Brand
import com.mymoneytracker.core.model.AccountKind
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.app.ui.theme.LocalDarkTheme
import com.mymoneytracker.app.ui.theme.LocalMoneyColors

// 티커 배지·색 구분·범례, 계좌 종류 배지, 범주 팔레트, 앱 로고.

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

/** 티커 색 구분: 국내 주식 / 해외 주식 / ETF / 금현물 / 펀드·채권. */
enum class TickerGroup(val label: String) { KR_STOCK("국내"), US_STOCK("해외"), ETF("ETF"), GOLD("금현물"), OTHER("펀드·채권") }

fun tickerGroup(market: Market, assetType: AssetType): TickerGroup = when {
    assetType == AssetType.ETF -> TickerGroup.ETF
    assetType == AssetType.STOCK && market == Market.KR -> TickerGroup.KR_STOCK
    assetType == AssetType.STOCK -> TickerGroup.US_STOCK
    assetType == AssetType.GOLD -> TickerGroup.GOLD
    else -> TickerGroup.OTHER
}

/** 범주 팔레트 8색 (비중 탭 종목·오늘 변동 계좌 색, 웹 --series-1~8 과 같은 값). 8개를 넘으면 처음부터 다시. */
@Composable
fun paletteColor(index: Int): Color {
    val light = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF6250D6, 0xFFE34948)
    val dark = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767)
    val i = index.coerceAtLeast(0) % light.size
    return Color(if (LocalDarkTheme.current) dark[i] else light[i])
}

/** 배지 바탕색 (흰 글자 대비 4.5:1 이상, 색각 이상 구분 검사 통과). 다크 모드는 다크 바탕에 맞춘 값. */
@Composable
fun tickerColor(group: TickerGroup): Color {
    val dark = LocalDarkTheme.current
    return when (group) {
        TickerGroup.KR_STOCK -> if (dark) Color(0xFF18805F) else Color(0xFF0B7A5E)
        TickerGroup.US_STOCK -> if (dark) Color(0xFF6A5FD9) else Color(0xFF5A4FCF)
        TickerGroup.ETF -> if (dark) Color(0xFFA85D0A) else Color(0xFFA35A08)
        TickerGroup.GOLD -> if (dark) Color(0xFF8C7000) else Color(0xFF856A00)
        TickerGroup.OTHER -> if (dark) Color(0xFF5E6B65) else Color(0xFF5B6B64)
    }
}

/** 종목 티커 배지: 코드가 있으면 코드, 없으면 이름 앞 글자 (금현물은 "금현물"). 색으로 국내·해외·ETF 를 구분한다. */
@Composable
fun TickerBadge(code: String, name: String, market: Market, assetType: AssetType) {
    val text = if (assetType == AssetType.GOLD) "금현물" else code.ifBlank { name.take(2) }.take(6)
    // 스톤 테마 (v2.18): 종류 색을 옅게 깐 바탕 + 종류 색 글자 (국내 주식은 포인트색과 같은 계열)
    val color = tickerColor(tickerGroup(market, assetType))
    val dark = LocalDarkTheme.current
    LabelBadge(
        text,
        color.copy(alpha = if (dark) 0.28f else 0.12f).compositeOver(MaterialTheme.colorScheme.surfaceContainerLowest),
        if (dark) lerp(color, Color.White, 0.45f) else color,
        width = TICKER_BADGE_WIDTH,
    )
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

/** 계좌 종류 배지 (v2.18): 연한 포인트 바탕 + 진녹 글자, 기타는 회색. */
@Composable
fun AccountKindBadge(kind: AccountKind) {
    val text = when (kind) {
        AccountKind.GENERAL -> "위탁"
        AccountKind.ISA -> "ISA"
        AccountKind.PENSION -> "연금"
        AccountKind.IRP -> "IRP"
        AccountKind.DC -> "DC"
        AccountKind.GOLD -> "금"
        AccountKind.OTHER -> "기타"
    }
    if (kind == AccountKind.OTHER) {
        LabelBadge(text, MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        LabelBadge(text, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** 앱 로고 (B · 계단 막대). */
@Composable
fun AppLogo(size: Dp, modifier: Modifier = Modifier) {
    // 스톤 테마 (v2.18): 포인트색 바탕에 막대, 금색 점
    val bg = MaterialTheme.colorScheme.primary
    val barColor = MaterialTheme.colorScheme.onPrimary
    val dot = LocalMoneyColors.current.gold
    Canvas(modifier.size(size)) {
        val k = this.size.width / 108f
        drawRoundRect(bg, cornerRadius = CornerRadius(28f * k))
        fun bar(x: Float, y: Float, h: Float, color: Color) = drawRoundRect(
            color,
            topLeft = Offset(x * k, y * k),
            size = Size(14f * k, h * k),
            cornerRadius = CornerRadius(5f * k),
        )
        bar(27f, 58f, 24f, barColor)
        bar(47f, 44f, 38f, barColor)
        bar(67f, 28f, 54f, barColor)
        drawCircle(if (dot == Color.Unspecified) Brand.Gold else dot, radius = 5f * k, center = Offset(74f * k, 18f * k))
    }
}
