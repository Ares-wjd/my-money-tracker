package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.market.MarketClock
import com.mymoneytracker.core.market.MarketGroup
import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.MarketState
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Market
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * 시장별 변동의 종목 하나 (같은 시세 키는 계좌가 달라도 합침). [changeKrw]·[rate] 가 null 이면 일봉 없음 (직접 입력 가격·아직 못 받음).
 * [changeKrw] 는 가격 변동만 (해외는 지금 환율로 원화, 환율 효과는 따로). [live] 면 그 시장이 진행 중이고 받은 일봉이 지금 일봉.
 */
data class TodayItem(
    val key: String,
    val holding: Holding,
    val valueKrw: Double,
    val changeKrw: Double?,
    val rate: Double?,
    val group: MarketGroup,
    val live: Boolean,
) {
    /** 목록에 쓰는 이름: 금현물, 종목명, 이름이 없으면 종목코드. */
    val label: String
        get() = when {
            holding.assetType == AssetType.GOLD -> "금현물"
            holding.name.isNotBlank() -> holding.name.trim()
            else -> holding.code.uppercase()
        }
}

/** 필터 칩의 종류. 종목은 티커 색 구분과 같고, 달러 예수금은 환율 효과에만 들어간다. */
enum class TodayKind(val label: String) {
    KR_STOCK("국내 주식"), US_STOCK("해외 주식"), ETF("ETF"), GOLD("금현물"), OTHER("펀드·채권"), USD_CASH("달러 예수금");

    companion object {
        fun of(holding: Holding): TodayKind = when {
            holding.assetType == AssetType.ETF -> ETF
            holding.assetType == AssetType.STOCK && holding.market == Market.KR -> KR_STOCK
            holding.assetType == AssetType.STOCK -> US_STOCK
            holding.assetType == AssetType.GOLD -> GOLD
            else -> OTHER
        }
    }
}

/** 필터: 뺀 계좌·종류를 기억하므로 기본은 전부 (= 전체), 새로 생긴 계좌·종류도 보인다. */
data class TodayFilter(val hiddenAccounts: Set<String> = emptySet(), val hiddenKinds: Set<TodayKind> = emptySet()) {
    val isEmpty: Boolean get() = hiddenAccounts.isEmpty() && hiddenKinds.isEmpty()

    companion object {
        /** 칩에 있는 것이 모두 보이는지 ([전체] 칩이 켜진 상태). */
        fun <T> allShown(present: List<T>, hidden: Set<T>): Boolean = present.none { it in hidden }

        /** [전체] 칩: 모두 보이면 전체 해제, 아니면 전체 선택. */
        fun <T> toggleAll(present: List<T>, hidden: Set<T>): Set<T> =
            if (allShown(present, hidden)) hidden + present else hidden - present.toSet()

        /** 칩 하나 켜기·끄기. */
        fun <T> toggle(item: T, hidden: Set<T>): Set<T> = if (item in hidden) hidden - item else hidden + item
    }
}

/**
 * 한 시장의 일봉 변동. [changeKrw] 가 null 이면 종목은 있지만 받은 일봉이 하나도 없음.
 * [live] 면 시장이 진행 중이고 받은 일봉이 지금 일봉 (아니면 화면에서 회색). [candleDate] 는 받은 일봉 날짜 (종목 중 가장 최근).
 * 국내는 [regularKrw] (정규장) + [extendedKrw] (장외) = [changeKrw]. 환율 줄은 [fxFrom] → [fxTo].
 */
data class MarketMove(
    val state: MarketState,
    val changeKrw: Double?,
    val rate: Double?,
    val live: Boolean,
    val candleDate: LocalDate?,
    val items: List<TodayItem> = emptyList(),
    val regularKrw: Double? = null,
    val extendedKrw: Double? = null,
    val fxFrom: Double? = null,
    val fxTo: Double? = null,
) {
    val group: MarketGroup get() = state.group

    /** 시장은 진행 중인데 받은 일봉이 아직 예전 것 (폰이 아직 못 받음). */
    val stale: Boolean get() = state.phase.active && !live
}

/** 시장별 변동 (웹 todayChange.ts 와 같은 규칙, v2.10): 합계 = 각 시장 최근 일봉 변동의 합. */
data class TodayChange(
    val totalKrw: Double,
    /** 합계 ÷ 기준 평가금 (대상 종목 기준가 + 달러 예수금). 기준이 없으면 null. */
    val rate: Double?,
    /** 국내 · 미국 · 환율 (있는 것만, 이 순서). */
    val markets: List<MarketMove>,
    /** 종목별 (변동 금액 순은 화면에서, 여기는 평가금 큰 순). */
    val items: List<TodayItem>,
) {
    /** 지금 움직이는 시장이 하나라도 있는지 (없으면 합계도 회색). */
    val live: Boolean get() = markets.any { it.live }

    fun market(group: MarketGroup): MarketMove? = markets.firstOrNull { it.group == group }
}

object TodayChangeCalculator {

    /** 칩으로 보여줄 종류: 보유 종목에 있는 종류 + 달러 예수금이 있으면 달러 예수금, 정해진 순서로. */
    fun presentKinds(summary: PortfolioSummary): List<TodayKind> {
        val kinds = summary.accounts.flatMap { a -> a.holdings.filter { it.position.quantity > 1e-9 }.map { TodayKind.of(it.holding) } }.toMutableSet()
        if (summary.accounts.any { abs(it.cash.usd) > 1e-9 }) kinds += TodayKind.USD_CASH
        return TodayKind.entries.filter { it in kinds }
    }

    private class Acc {
        var change = 0.0
        var regular = 0.0
        /** 시장 등락률의 분모 (해외는 지금 환율로 원화). */
        var base = 0.0
        /** 합계 비율의 분모 (해외는 직전 환율로 원화 = 기준 평가금). */
        var prev = 0.0
        var any = false
        var candleDate: LocalDate? = null
        val items = linkedMapOf<String, TodayItem>()
    }

    /**
     * 시장별 변동. [now] 로 시장 상태를 정하고, [filter] 로 뺀 계좌·종류는 계산하지 않는다.
     * 고른 범위에 종목도 달러 예수금도 없으면 null.
     */
    fun calculate(summary: PortfolioSummary, snapshot: MarketSnapshot, now: ZonedDateTime, filter: TodayFilter = TodayFilter()): TodayChange? {
        val states = MarketClock.states(now, snapshot.calendar)
        val fxState = states.getValue(MarketGroup.FX)
        val fxNow = summary.usdKrw

        // 환율 일봉: 그날 환율 (오늘 받은 값이 그날이면 그것) ← 직전 개장일 환율
        val latestFx = snapshot.latestUsdKrw()
        val fxTo = if (latestFx != null && latestFx.first == fxState.candleDate) latestFx.second else snapshot.usdKrw[fxState.candleDate]?.takeIf { it > 0 }
        val fxFrom = if (fxTo != null) snapshot.usdKrwAt(MarketClock.prevKrOpen(fxState.candleDate, snapshot.calendar)) else null
        val fxMove = if (fxFrom != null && fxTo != null) fxTo - fxFrom else 0.0
        val fxBase = fxFrom ?: fxNow ?: 0.0

        val kr = Acc()
        val us = Acc()
        var usdCash = 0.0
        var usdHoldings = 0.0
        var anyUsdCash = false
        for (account in summary.accounts) {
            if (account.account.id in filter.hiddenAccounts) continue
            if (TodayKind.USD_CASH !in filter.hiddenKinds && abs(account.cash.usd) > 1e-9) {
                usdCash += account.cash.usd
                anyUsdCash = true
            }
            for (v in account.holdings) {
                val quantity = v.position.quantity
                if (quantity <= 1e-9) continue
                val h = v.holding
                if (TodayKind.of(h) in filter.hiddenKinds) continue
                val group = MarketGroup.of(h)
                val acc = if (group == MarketGroup.KR) kr else us
                acc.any = true
                val key = MarketKeys.of(h) ?: "id:${h.id}"
                val usd = h.currency == Currency.USD
                // 직접 입력 가격을 쓰는 종목(펀드 등)은 빼고, 일봉이 있는 자동 시세 종목만
                val history = snapshot.history(h)
                val candle = history?.candle?.takeIf { it.base > 0 && history.latest != null && PriceLookup.current(h, snapshot) == history.latest }
                var change: Double? = null
                if (candle != null) {
                    val fx = if (usd) fxNow ?: 0.0 else 1.0
                    change = quantity * (candle.last - candle.base) * fx
                    acc.change += change
                    acc.regular += quantity * ((candle.regular ?: candle.last) - candle.base) * fx
                    acc.base += quantity * candle.base * fx
                    acc.prev += quantity * candle.base * (if (usd) fxBase else 1.0)
                    if (usd) usdHoldings += quantity * candle.base
                    acc.candleDate = maxOf(acc.candleDate ?: candle.date, candle.date)
                }
                val live = candle != null && states.getValue(group).let { it.phase.active && candle.date == it.candleDate }
                val old = acc.items[key]
                acc.items[key] = if (old == null) {
                    TodayItem(key, h, v.marketValueKrw ?: 0.0, change, candle?.rate, group, live)
                } else {
                    old.copy(valueKrw = old.valueKrw + (v.marketValueKrw ?: 0.0), changeKrw = sumOrNull(old.changeKrw, change))
                }
            }
        }
        if (!kr.any && !us.any && !anyUsdCash) return null

        val markets = mutableListOf<MarketMove>()
        fun move(group: MarketGroup, acc: Acc, split: Boolean): MarketMove {
            val state = states.getValue(group)
            val hasCandle = acc.candleDate != null
            return MarketMove(
                state = state,
                changeKrw = if (hasCandle) acc.change else null,
                rate = if (hasCandle && acc.base > 0) acc.change / acc.base else null,
                live = hasCandle && state.phase.active && acc.candleDate == state.candleDate,
                candleDate = acc.candleDate,
                items = acc.items.values.sortedByDescending { it.valueKrw },
                regularKrw = if (split && hasCandle) acc.regular else null,
                extendedKrw = if (split && hasCandle) acc.change - acc.regular else null,
            )
        }
        if (kr.any) markets += move(MarketGroup.KR, kr, split = true)
        if (us.any) markets += move(MarketGroup.US, us, split = false)
        // 환율 효과 = (해외 종목 수량 × 기준가 + 달러 예수금) × 환율 변동
        val fxChange = (usdHoldings + usdCash) * fxMove
        if (us.any || anyUsdCash) {
            markets += MarketMove(
                state = fxState,
                changeKrw = if (fxTo != null) fxChange else null,
                rate = if (fxFrom != null && fxTo != null && fxFrom > 0) fxTo / fxFrom - 1 else null,
                live = fxTo != null && fxState.phase.active,
                candleDate = if (fxTo != null) fxState.candleDate else null,
                fxFrom = fxFrom,
                fxTo = fxTo,
            )
        }

        val total = markets.sumOf { it.changeKrw ?: 0.0 }
        val baseValue = kr.prev + us.prev + usdCash * fxBase
        return TodayChange(
            totalKrw = total,
            rate = if (baseValue > 0) total / baseValue else null,
            markets = markets,
            items = (kr.items.values + us.items.values).sortedByDescending { it.valueKrw },
        )
    }

    private fun sumOrNull(a: Double?, b: Double?): Double? = if (a == null || b == null) a ?: b else a + b
}
