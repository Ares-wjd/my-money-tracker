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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.ui.PortfolioViewModel
import com.mymoneytracker.app.ui.common.AppFilterChip
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.TickerBadge
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.app.ui.common.profitColor
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.market.MarketCalendar
import com.mymoneytracker.core.market.MarketClock
import com.mymoneytracker.core.market.MarketGroup
import com.mymoneytracker.core.market.MarketPhase
import com.mymoneytracker.core.portfolio.MarketMove
import com.mymoneytracker.core.portfolio.TodayChange
import com.mymoneytracker.core.portfolio.TodayChangeCalculator
import com.mymoneytracker.core.portfolio.TodayFilter
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import kotlin.math.abs

// 시장별 변동 (v2.10): 홈 카드와 상세 화면. 시장마다 지금(또는 마지막) 일봉 하나, 상태 표시, 쉬는 시장은 회색.

/** 지금 시각 (30초마다 바뀜): 시장 상태가 시간에 따라 바뀌므로. */
@Composable
fun rememberNow(): ZonedDateTime {
    val now by produceState(ZonedDateTime.now(MarketClock.KST)) {
        while (true) {
            delay(30_000)
            value = ZonedDateTime.now(MarketClock.KST)
        }
    }
    return now
}

/** 상태 표시 색 (바탕, 글자, 점 — 쉬는 중이면 점 없음). 웹 styles.css 의 .phase-* 와 같은 값. */
@Composable
private fun phaseColors(phase: MarketPhase): Triple<Color, Color, Color?> {
    val dark = isSystemInDarkTheme()
    return when (phase) {
        MarketPhase.PRE -> if (dark) Triple(Color(0xFF1E2E47), Color(0xFF9CC1F5), Color(0xFF6FA3EC)) else Triple(Color(0xFFE8F0FB), Color(0xFF1F4F9E), Color(0xFF3B78D8))
        MarketPhase.REGULAR -> if (dark) Triple(Color(0xFF5CC9A7), Color(0xFF00382B), Color(0xFF00382B)) else Triple(Color(0xFF0F6B5C), Color.White, Color.White)
        MarketPhase.AFTER -> if (dark) Triple(Color(0xFF2E2547), Color(0xFFC9B5F2), Color(0xFFA98BE0)) else Triple(Color(0xFFF1EBFB), Color(0xFF5B3A99), Color(0xFF8A63D2))
        MarketPhase.DAY -> if (dark) Triple(Color(0xFF3D2B14), Color(0xFFF2C38A), Color(0xFFE08A1E)) else Triple(Color(0xFFFDF0E1), Color(0xFF8A4B00), Color(0xFFE08A1E))
        MarketPhase.FX_OPEN -> if (dark) Triple(Color(0xFF16332A), Color(0xFF8FD6B9), Color(0xFF1BAF7A)) else Triple(Color(0xFFE6F2EE), Color(0xFF0B5548), Color(0xFF1BAF7A))
        else -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, null)
    }
}

/** 시간표 구간 색. */
@Composable
private fun segmentColor(phase: MarketPhase): Color = when (phase) {
    MarketPhase.PRE -> Color(0xFF7AA7E6)
    MarketPhase.REGULAR -> if (isSystemInDarkTheme()) Color(0xFF5CC9A7) else Color(0xFF0F6B5C)
    MarketPhase.AFTER -> Color(0xFFA98BE0)
    MarketPhase.DAY -> Color(0xFFF0A855)
    else -> Color(0xFF8FCBB4)
}

/** 상태 표시: "● 프리마켓", "휴장 · 주말". */
@Composable
fun MarketChip(move: MarketMove) {
    val (bg, fg, dot) = phaseColors(move.state.phase)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot != null) Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(move.state.chip, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** 시장 줄 아래 설명: "11/3(월) 일봉 확정 · 내일 08:00 프리마켓", 환율은 "1,402.5원 (+2.0원) · …". */
fun marketSubText(move: MarketMove, now: ZonedDateTime): String {
    val date = move.candleDate ?: move.state.candleDate
    val head = when {
        move.group == MarketGroup.FX -> move.fxTo?.let { to ->
            "${MoneyFormat.decimal(to, 1)}원" + (move.fxFrom?.let { " (${signedDecimal(to - it)}원)" } ?: "")
        } ?: "환율 없음"
        move.changeKrw == null -> "시세 없음"
        move.stale -> "시세 갱신 전 · ${MarketClock.dayText(date)} 일봉"
        move.state.phase == MarketPhase.CLOSED -> "${MarketClock.dayText(date)} 일봉 확정"
        else -> "${MarketClock.dayText(date)} 일봉"
    }
    return listOfNotNull(head, MarketClock.nextText(move.state, now)).joinToString(" · ")
}

private fun signedDecimal(v: Double): String = (if (v > 0) "+" else "") + MoneyFormat.decimal(v, 1)

/** 시장 글자 배지 (KR · US · FX). */
@Composable
private fun MarketBadge(move: MarketMove) {
    val (bg, fg, _) = phaseColors(if (move.live) move.state.phase else MarketPhase.CLOSED)
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(move.group.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** 시장 한 줄 (홈 카드): 배지 · 이름 · 상태 · 설명 · 변동 금액·비율 (쉬는 시장은 회색). */
@Composable
private fun MarketRow(move: MarketMove, now: ZonedDateTime) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val color = if (move.live) profitColor(move.changeKrw) else grey
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MarketBadge(move)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(move.group.label, style = MaterialTheme.typography.titleSmall, color = if (move.live) MaterialTheme.colorScheme.onSurface else grey)
                MarketChip(move)
            }
            Text(marketSubText(move, now), style = MaterialTheme.typography.bodySmall, color = grey)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(move.changeKrw?.let { MoneyFormat.signedWon(it) } ?: "-", style = MaterialTheme.typography.titleSmall, color = color)
            move.rate?.let { Text(MoneyFormat.rate(it), style = MaterialTheme.typography.labelMedium, color = color) }
        }
    }
}

/** 합계와 비율 (모든 시장이 쉬면 회색). */
@Composable
private fun TodayTotal(change: TodayChange) {
    val color = if (change.live) profitColor(change.totalKrw) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(MoneyFormat.signedWon(change.totalKrw), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
        change.rate?.let { Text(MoneyFormat.rate(it), style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.padding(bottom = 3.dp)) }
    }
    Text(
        if (change.live) "각 시장의 최근 일봉 합계" else "모든 시장이 쉬는 중 · 최근 일봉 합계",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 홈의 시장별 변동 카드 (항상 전체 기준). */
@Composable
fun TodayChangeCard(change: TodayChange, now: ZonedDateTime, onOpen: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("시장별 변동", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "${MarketClock.dayText(now.toLocalDate())} %02d:%02d ›".format(now.hour, now.minute),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TodayTotal(change)
        change.markets.forEach { move ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MarketRow(move, now)
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

/** 시장별 비중 막대 (변동 금액 크기 비율, 쉬는 시장은 회색). */
@Composable
private fun ShareBar(change: TodayChange) {
    val parts = change.markets.filter { abs(it.changeKrw ?: 0.0) > 0.5 }
    val total = parts.sumOf { abs(it.changeKrw!!) }
    if (total <= 0) return
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).height(10.dp).clip(RoundedCornerShape(50))) {
        parts.forEach { m ->
            val color = if (m.live) segmentColor(m.state.phase) else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.weight((abs(m.changeKrw!!) / total).toFloat()).height(10.dp).background(color))
        }
    }
}

/** "지금 열린 장" 시간표: 오늘 0~24시에 시장별 구간, 지금 시각 선. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionTimeline(change: TodayChange, now: ZonedDateTime, calendar: MarketCalendar) {
    val today = now.toLocalDate()
    val segments = remember(today, calendar) { MarketClock.timeline(today, calendar) }
    val nowMinutes = now.hour * 60 + now.minute
    SectionCard(title = "지금 열린 장") {
        Text("한국 시간 · 미국은 서머타임 자동 반영", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        change.markets.map { it.group }.forEach { group ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(group.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp))
                BoxWithConstraints(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val w = maxWidth
                    segments.filter { it.group == group }.forEach { s ->
                        Box(Modifier.offset(x = w * (s.start / 1440f)).width(w * ((s.end - s.start) / 1440f)).height(18.dp).background(segmentColor(s.phase)))
                    }
                    Box(Modifier.offset(x = w * (nowMinutes / 1440f)).width(2.dp).height(18.dp).background(MaterialTheme.colorScheme.error))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0시", "6시", "12시", "18시", "24시").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(MarketPhase.PRE, MarketPhase.REGULAR, MarketPhase.AFTER, MarketPhase.DAY, MarketPhase.FX_OPEN).forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(segmentColor(p)))
                    Text(if (p == MarketPhase.FX_OPEN) "외환시장" else p.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 시장 카드 (상세): 머리줄 · 설명 · 종목 목록 (변동 금액 순, 쉬는 시장은 회색). */
@Composable
private fun MarketSection(move: MarketMove, now: ZonedDateTime, onOpenHolding: (String) -> Unit) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val color = if (move.live) profitColor(move.changeKrw) else grey
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarketBadge(move)
            Text(move.group.label, style = MaterialTheme.typography.titleMedium, color = if (move.live) MaterialTheme.colorScheme.onSurface else grey)
            MarketChip(move)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(move.changeKrw?.let { MoneyFormat.signedWon(it) } ?: "-", style = MaterialTheme.typography.titleMedium, color = color)
                move.rate?.let { Text(MoneyFormat.rate(it), style = MaterialTheme.typography.labelMedium, color = color) }
            }
        }
        val lines = buildList {
            add(marketSubText(move, now))
            if (move.regularKrw != null && abs(move.extendedKrw ?: 0.0) > 0.5) {
                add("정규 ${MoneyFormat.signedWon(move.regularKrw!!)} · 장외 ${MoneyFormat.signedWon(move.extendedKrw!!)}")
            }
            if (move.group == MarketGroup.US) add("오늘 환율로 원화 환산 (환율 효과는 아래에 따로)")
            if (move.group == MarketGroup.FX) add("해외 종목 기준가 평가금 + 달러 예수금에 대한 환율 효과 · 서울 외환시장 09:00 ~ 다음 날 02:00")
        }
        Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = grey, modifier = Modifier.padding(top = 8.dp))
        val items = move.items.sortedByDescending { abs(it.changeKrw ?: -1.0) }
        items.forEachIndexed { index, item ->
            if (index == 0) HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) else HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val itemColor = if (item.live) profitColor(item.changeKrw) else grey
            Row(
                Modifier.fillMaxWidth().clickable { onOpenHolding(item.holding.id) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TickerBadge(item.holding.code, item.holding.name, item.holding.market, item.holding.assetType)
                Text(item.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (item.changeKrw == null) {
                    Text("시세 없음", style = MaterialTheme.typography.bodyMedium, color = grey)
                } else {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(MoneyFormat.signedWon(item.changeKrw!!), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = itemColor)
                        Text(MoneyFormat.rate(item.rate), style = MaterialTheme.typography.labelMedium, color = itemColor)
                    }
                }
            }
        }
    }
}

/** 시장별 변동 상세: 필터 · 요약 · 지금 열린 장 · 시장별 카드. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayChangeScreen(viewModel: PortfolioViewModel, onBack: () -> Unit, onOpenHolding: (String) -> Unit) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val filter by viewModel.todayFilter.collectAsStateWithLifecycle()
    val now = rememberNow()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle("시장별 변동") },
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
        // 필터를 적용한 시장별 변동 (계산이 가벼워 화면에서 바로 계산)
        val c = remember(current, filter, now) { TodayChangeCalculator.calculate(current.summary, current.market, now, filter) }
        val accounts = current.summary.accounts.map { it.account }
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
                    ChipRow("계좌", accounts.map { it.id }, filter.hiddenAccounts, { id -> accounts.first { it.id == id }.name }) {
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
                        "${MarketClock.dayText(now.toLocalDate())} %02d:%02d".format(now.hour, now.minute),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TodayTotal(c)
                    ShareBar(c)
                }
            }
            item { SessionTimeline(c, now, current.market.calendar) }
            c.markets.forEach { move -> item(key = move.group.name) { MarketSection(move, now, onOpenHolding) } }
            item {
                Text(
                    "직접 입력 가격(펀드 등)은 시세 없음 · 한투가 주지 않는 장외 시세는 마지막으로 받은 가격 기준",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
