package com.mymoneytracker.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.remember
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
import com.mymoneytracker.app.ui.common.AppFilterChip
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.SegmentedPills
import com.mymoneytracker.app.ui.common.TickerBadge
import com.mymoneytracker.app.ui.common.TickerGroup
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.app.ui.common.paletteColor
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.app.ui.common.tickerColor
import com.mymoneytracker.app.ui.common.tickerGroup
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.portfolio.TodayChange
import com.mymoneytracker.core.portfolio.TodayChangeCalculator
import com.mymoneytracker.core.portfolio.TodayFilter
import com.mymoneytracker.core.portfolio.TodayItem
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** 기준 시각 글: "10.14 (화) 14:32 기준". */
private fun baseTime(refreshedAt: Long?): String =
    refreshedAt?.let { SimpleDateFormat("M.d (E) HH:mm", Locale.KOREA).format(Date(it)) + " 기준" }.orEmpty()

/** 홈의 "오늘 변동" 카드 (누르면 상세 화면). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayChangeCard(change: TodayChange, refreshedAt: Long?, accountOrder: List<String>, onOpen: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("오늘 변동", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(baseTime(refreshedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "자세히", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TodayTotal(change)
        if (change.included.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            ContributionBar(change, accountOrder)
            ContributionRows(change, accountOrder)
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

/** 한 줄: [amount] 가 null 이면 시세 없음. */
private data class Part(val key: String, val label: String, val detail: String?, val amount: Double?, val color: Color)

/** 계좌별 + 환율 몫. [accountOrder] 는 전체 계좌 순서 (필터와 상관없이 같은 계좌는 같은 색, 웹과 같은 팔레트). */
@Composable
private fun parts(change: TodayChange, accountOrder: List<String>): List<Part> =
    change.accounts.map { a ->
        Part(a.account.id, a.account.name, null, a.changeKrw, paletteColor(accountOrder.indexOf(a.account.id)))
    } + Part(
        "__fx",
        "환율",
        if (change.fxFrom != null && change.fxTo != null) {
            "${MoneyFormat.decimal(change.fxFrom!!, 2)} → ${MoneyFormat.decimal(change.fxTo!!, 2)}"
        } else {
            "오늘 환율 없음"
        },
        change.fxKrw,
        tickerColor(TickerGroup.GOLD),
    )

/** 계좌별 + 환율이 기여한 몫 (크기 비율 막대). */
@Composable
private fun ContributionBar(change: TodayChange, accountOrder: List<String>) {
    val items = parts(change, accountOrder).mapNotNull { p -> p.amount?.takeIf { kotlin.math.abs(it) >= 0.5 }?.let { p to kotlin.math.abs(it) } }
    val total = items.sumOf { it.second }
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        if (total > 0) {
            items.forEach { (p, amount) -> Box(Modifier.weight((amount / total).toFloat().coerceAtLeast(0.01f)).height(10.dp).background(p.color)) }
        }
    }
}

/** 계좌마다 한 줄 + 환율 한 줄 (모두 더하면 합계). */
@Composable
private fun ContributionRows(change: TodayChange, accountOrder: List<String>) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parts(change, accountOrder).forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(p.color, RoundedCornerShape(3.dp)))
                if (p.detail != null) {
                    Text(p.label, style = MaterialTheme.typography.bodyMedium)
                    Text(p.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                } else {
                    Text(p.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                val amount = p.amount
                if (amount == null) {
                    Text("시세 없음", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(MoneyFormat.signedWon(amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = profitColor(amount))
                }
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

/** 필터 칩 한 줄: [전체] + 항목들 (기록 필터와 같은 동작, 뺀 것을 기억). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(label: String, items: List<T>, hidden: Set<T>, name: (T) -> String, onChange: (Set<T>) -> Unit) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppFilterChip(
                selected = TodayFilter.allShown(items, hidden),
                label = "전체",
                onClick = { onChange(TodayFilter.toggleAll(items, hidden)) },
            )
            items.forEach { item ->
                AppFilterChip(selected = item !in hidden, label = name(item), onClick = { onChange(TodayFilter.toggle(item, hidden)) })
            }
        }
    }
}

private enum class TodaySort(val label: String) { AMOUNT("금액"), RATE("등락률") }

/** 오늘 변동 상세 화면: 요약 · 종목별 타일 · 종목 목록. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodayChangeScreen(viewModel: PortfolioViewModel, onBack: () -> Unit, onOpenHolding: (String) -> Unit) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val filter by viewModel.todayFilter.collectAsStateWithLifecycle()
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
        val current = data
        if (current == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        // 필터를 적용한 오늘 변동 (계산이 가벼워 화면에서 바로 계산)
        val c = remember(current, filter) { TodayChangeCalculator.calculate(current.summary, current.market, LocalDate.now(), filter) }
        val accounts = current.summary.accounts.map { it.account }
        val accountOrder = accounts.map { it.id }
        val kinds = remember(current) { TodayChangeCalculator.presentKinds(current.summary) }
        // 지금 있는 계좌·종류 중에 뺀 것 ("연금저축 · ETF 제외")
        val hiddenNames = accounts.filter { it.id in filter.hiddenAccounts }.map { it.name } +
            kinds.filter { it in filter.hiddenKinds }.map { it.label }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    ChipRow("계좌", accountOrder, filter.hiddenAccounts, { id -> accounts.first { it.id == id }.name }) {
                        viewModel.setTodayFilter(filter.copy(hiddenAccounts = it))
                    }
                    ChipRow("종류", kinds, filter.hiddenKinds, { it.label }) {
                        viewModel.setTodayFilter(filter.copy(hiddenKinds = it))
                    }
                }
            }
            if (c == null) {
                item {
                    SectionCard {
                        Text(
                            if (filter.isEmpty) "보유 종목이 없습니다." else "고른 범위에 보유 종목이 없습니다. 위에서 계좌나 종류를 고르세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@LazyColumn
            }
            item {
                SectionCard {
                    if (hiddenNames.isNotEmpty()) {
                        Text(
                            "필터 적용 중 · ${hiddenNames.joinToString(" · ")} 제외",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        listOf(baseTime(refresh.lastRefreshedAt), "해외는 미국 시장 기준").filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TodayTotal(c)
                    ContributionRows(c, accountOrder)
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
                    if (c.items.isEmpty()) {
                        Text("고른 범위에 종목이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        TodayTiles(c, onOpenHolding)
                    }
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
