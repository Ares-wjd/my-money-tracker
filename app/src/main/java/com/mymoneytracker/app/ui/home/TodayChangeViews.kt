package com.mymoneytracker.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.SegmentedPills
import com.mymoneytracker.app.ui.common.TickerBadge
import com.mymoneytracker.app.ui.common.TickerGroup
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.app.ui.common.tickerColor
import com.mymoneytracker.app.ui.common.tickerGroup
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.portfolio.TodayChange
import com.mymoneytracker.core.portfolio.TodayChangeCalculator
import com.mymoneytracker.core.portfolio.TodayItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 기준 시각 글: "10.14 (화) 14:32 기준". */
private fun baseTime(refreshedAt: Long?): String =
    refreshedAt?.let { SimpleDateFormat("M.d (E) HH:mm", Locale.KOREA).format(Date(it)) + " 기준" }.orEmpty()

/** 홈의 "오늘 변동" 카드 (누르면 상세 화면). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayChangeCard(change: TodayChange, refreshedAt: Long?, onOpen: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("오늘 변동", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(baseTime(refreshedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "자세히", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TodayTotal(change)
        if (change.included.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            ContributionBar(change)
            ContributionRows(change)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Movers("많이 오른 종목", change.topGainers(), Modifier.weight(1f))
                Movers("많이 내린 종목", change.topLosers(), Modifier.weight(1f))
            }
        }
        Text(
            "현재가 ÷ 전일 종가 기준 · 직접 입력 가격(펀드 등)은 제외 · 누르면 종목별로 보기",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TodayTotal(change: TodayChange) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            MoneyFormat.signedWon(change.totalKrw),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = profitColor(change.totalKrw),
        )
        Text(
            MoneyFormat.rate(change.rate),
            style = MaterialTheme.typography.titleSmall,
            color = profitColor(change.rate),
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}

private data class Part(val label: String, val detail: String?, val amount: Double, val color: Color)

@Composable
private fun parts(change: TodayChange): List<Part> = listOf(
    Part("국내 주식·ETF", null, change.domesticKrw, tickerColor(TickerGroup.KR_STOCK)),
    Part("해외 주식", "미국 시장 기준", change.foreignKrw, tickerColor(TickerGroup.US_STOCK)),
    Part(
        "환율",
        if (change.fxFrom != null && change.fxTo != null) {
            "${MoneyFormat.decimal(change.fxFrom!!, 2)} → ${MoneyFormat.decimal(change.fxTo!!, 2)}"
        } else {
            "오늘 환율 발표 전"
        },
        change.fxKrw,
        tickerColor(TickerGroup.GOLD),
    ),
)

/** 국내·해외·환율이 기여한 몫 (크기 비율 막대). */
@Composable
private fun ContributionBar(change: TodayChange) {
    val items = parts(change).filter { kotlin.math.abs(it.amount) >= 0.5 }
    val total = items.sumOf { kotlin.math.abs(it.amount) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        if (total > 0) {
            items.forEach { p -> Box(Modifier.weight((kotlin.math.abs(p.amount) / total).toFloat().coerceAtLeast(0.01f)).height(10.dp).background(p.color)) }
        }
    }
}

@Composable
private fun ContributionRows(change: TodayChange) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parts(change).forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(p.color, RoundedCornerShape(3.dp)))
                Text(p.label, style = MaterialTheme.typography.bodyMedium)
                if (p.detail != null) {
                    Text(p.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(MoneyFormat.signedWon(p.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = profitColor(p.amount))
            }
        }
    }
}

@Composable
private fun Movers(title: String, items: List<TodayItem>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (items.isEmpty()) Text("없음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { item ->
            // 종목명 (앞의 작은 점 = 티커 색), 긴 이름은 줄이고 등락률은 항상 보이게
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(tickerColor(tickerGroup(item.holding.market, item.holding.assetType)), RoundedCornerShape(4.dp)))
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    MoneyFormat.rate(item.rate),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = profitColor(item.rate),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 타일 색 (바탕, 글자): 등락 단계별, 시세 없음은 연회색. */
@Composable
private fun tileColors(level: Int?): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    return when (level) {
        null -> if (dark) Color(0xFF242E2A) to Color(0xFFB4C0BA) else Color(0xFFF0F2EF) to Color(0xFF55655E)
        0 -> if (dark) Color(0xFF2F3834) to Color(0xFFE3E8E5) else Color(0xFFE4E8E5) to Color(0xFF16201C)
        1 -> if (dark) Color(0xFF4A1D24) to Color(0xFFFFC9D0) else Color(0xFFF8DDE1) to Color(0xFF5C0716)
        2 -> if (dark) Color(0xFF8A2836) to Color.White else Color(0xFFE7909C) to Color(0xFF4A0512)
        3 -> Color(0xFFC4122F) to Color.White
        -1 -> if (dark) Color(0xFF1C2B4A) to Color(0xFFC9DAFF) else Color(0xFFDFE8FA) to Color(0xFF0B2A66)
        -2 -> if (dark) Color(0xFF2D4E95) to Color.White else Color(0xFF9FBBF0) to Color(0xFF071D4A)
        else -> Color(0xFF1F5FD1) to Color.White
    }
}

/** 종목별 타일: 칸 크기 = 평가금 비중, 색 = 오늘 등락. */
@Composable
private fun TodayTiles(change: TodayChange, onOpenHolding: (String) -> Unit) {
    val tiles = TodayChangeCalculator.tiles(change.items)
    val height = 260f
    BoxWithConstraints(Modifier.fillMaxWidth().height(height.dp)) {
        val width = maxWidth.value
        val rects = TodayChangeCalculator.treemap(tiles.map { it.second }, width.toDouble(), height.toDouble())
        tiles.forEachIndexed { i, (item, _) ->
            val r = rects[i]
            val (bg, fg) = tileColors(if (item == null) null else TodayChangeCalculator.level(item.rate))
            Box(
                Modifier
                    .offset(r.x.dp, r.y.dp)
                    .size(r.w.dp, r.h.dp)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .then(if (item != null) Modifier.clickable { onOpenHolding(item.holding.id) } else Modifier)
                    .padding(6.dp),
            ) {
                if (r.w >= 40 && r.h >= 30) {
                    // 좁은 칸은 글자를 줄여 등락률이 잘리지 않게, 큰 칸은 이름을 2줄까지
                    val compact = r.w < 72
                    Column {
                        Text(
                            item?.label ?: "기타",
                            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = fg,
                            maxLines = if (r.h >= 80 && !compact) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (r.h >= 50) {
                            Text(
                                when {
                                    item == null -> ""
                                    item.rate == null -> "시세 없음"
                                    else -> MoneyFormat.rate(item.rate)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = if (compact) 9.sp else TextUnit.Unspecified,
                                color = fg,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class TodaySort(val label: String) { AMOUNT("금액"), RATE("등락률") }

/** 오늘 변동 상세 화면: 요약 · 종목별 타일 · 종목 목록. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodayChangeScreen(viewModel: PortfolioViewModel, onBack: () -> Unit, onOpenHolding: (String) -> Unit) {
    val change by viewModel.todayChange.collectAsStateWithLifecycle()
    val refresh by viewModel.refresh.collectAsStateWithLifecycle()
    var sortName by rememberSaveable { mutableStateOf(TodaySort.AMOUNT.name) }
    val sort = TodaySort.valueOf(sortName)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle("오늘 변동") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        val c = change
        if (c == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    Text(
                        listOf(baseTime(refresh.lastRefreshedAt), "해외는 미국 시장 기준").filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TodayTotal(c)
                    ContributionRows(c)
                }
            }
            item {
                SectionCard(title = "종목별") {
                    Text(
                        "칸 크기 = 평가금 비중 · 색 = 오늘 등락 (빨강 상승, 파랑 하락, 진할수록 큼) · 누르면 종목 화면",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    TodayTiles(c, onOpenHolding)
                }
            }
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (sort == TodaySort.AMOUNT) "변동 금액 순" else "등락률 순", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    SegmentedPills(TodaySort.entries, sort, { it.label }, { sortName = it.name })
                    val sorted = c.included.sortedByDescending { if (sort == TodaySort.AMOUNT) it.changeKrw else it.rate } + c.excluded
                    sorted.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenHolding(item.holding.id) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TickerBadge(item.holding.code, item.holding.name, item.holding.market, item.holding.assetType)
                            Text(item.holding.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Column(horizontalAlignment = Alignment.End) {
                                if (item.changeKrw == null) {
                                    Text("시세 없음", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text(MoneyFormat.signedWon(item.changeKrw!!), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = profitColor(item.changeKrw))
                                    Text(MoneyFormat.rate(item.rate), style = MaterialTheme.typography.labelMedium, color = profitColor(item.rate))
                                }
                            }
                        }
                    }
                    Text(
                        "해외 종목 금액은 오늘 환율 기준 (환율 효과는 위에 따로) · 직접 입력 가격(펀드 등)은 시세 없음",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
