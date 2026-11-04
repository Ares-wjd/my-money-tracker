package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.market.MarketSnapshot
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/** 비교할 지수 (v2.11). [key] 시세 문서 키 (`price_<key>`), [usd] 달러 지수면 그날 환율로 원화 환산. */
enum class BenchmarkIndex(val key: String, val label: String, val usd: Boolean) {
    SPX("IDX_SPX", "S&P500", true),
    COMP("IDX_COMP", "나스닥", true),
    KOSPI("IDX_KOSPI", "KOSPI", false),
}

/**
 * 지수 비교 (docs/FEATURES.md 1-8, 웹 benchmark.ts 와 같은 규칙): "같은 날 같은 돈을 지수에 넣었다면".
 * 일별 평가금·투자금([FlowPoint])에서 날마다 투자금이 늘거나 준 만큼을 그날 지수(원화)로 사고판 가상 평가금을 만든다.
 */
object Benchmark {

    /**
     * [date] 의 지수 원화 값. 지난 날은 그날(휴일은 직전) 종가, 그보다 앞에 종가가 없으면 처음 종가.
     * 오늘([today] 이후)은 현재가, 없으면 마지막 종가. 달러 지수는 그날 환율(오늘은 최신 환율)을 곱한다. 모르면 null.
     */
    fun levelKrw(index: BenchmarkIndex, snapshot: MarketSnapshot, date: LocalDate, today: LocalDate): Double? {
        val history = snapshot.prices[index.key] ?: return null
        val closes = history.closes
        val past = (closes.floorEntry(date) ?: closes.firstEntry())?.value
        val price = (if (!date.isBefore(today)) history.latest?.price?.takeIf { it > 0 } ?: closes.lastEntry()?.value else past)
            ?.takeIf { it > 0 } ?: return null
        if (!index.usd) return price
        val fx = if (!date.isBefore(today)) snapshot.latestUsdKrw()?.second
        else snapshot.usdKrwAt(date) ?: snapshot.usdKrw.entries.firstOrNull { it.value > 0 }?.value
        return fx?.takeIf { it > 0 }?.let { price * it }
    }

    /** 가상 평가금 일별 값 ([series] 와 같은 날짜, 투자금은 그대로). 지수 값을 모르는 날이 있으면 null. */
    fun simulate(series: List<FlowPoint>, index: BenchmarkIndex, snapshot: MarketSnapshot, today: LocalDate): List<FlowPoint>? {
        if (series.isEmpty()) return null
        var units = 0.0
        var invested = 0.0
        return series.map { p ->
            val level = levelKrw(index, snapshot, p.date, today) ?: return null
            units += (p.investedKrw - invested) / level
            invested = p.investedKrw
            FlowPoint(p.date, units * level, p.investedKrw)
        }
    }

    /** 지수 종가가 [from] 무렵(7일 안)부터 있는지: 아니면 그 기간 수익률은 "-" (아직 받는 중). */
    fun covers(index: BenchmarkIndex, snapshot: MarketSnapshot, from: LocalDate): Boolean {
        val first = snapshot.prices[index.key]?.closes?.firstKey() ?: return false
        return !first.isAfter(from.plusDays(7))
    }

    /** 지수 가상 평가금의 기간 수익 ([PeriodReturns.period] 와 같은 방식). 지수 종가가 그 기간을 다 덮지 못하면 null ("-"). */
    fun period(simulated: List<FlowPoint>?, index: BenchmarkIndex, snapshot: MarketSnapshot, from: LocalDate, to: LocalDate): PeriodResult? {
        val result = PeriodReturns.period(simulated ?: return null, from, to) ?: return null
        return result.takeIf { covers(index, snapshot, it.from) }
    }

    /** 그래프 점 날짜마다 가상 평가금 (그날 또는 직전 값, 앞에 없으면 null). */
    fun valuesAt(simulated: List<FlowPoint>, dates: List<LocalDate>): List<Double?> {
        val map = java.util.TreeMap<LocalDate, Double>().apply { simulated.forEach { put(it.date, it.valueKrw) } }
        return dates.map { map.floorEntry(it)?.value }
    }

    /** 오늘 기준 한 줄: "S&P500에 넣었다면 5,960만원 · 내가 −420만" 의 금액들 (내 평가금 − 가상 평가금). */
    data class Today(val index: BenchmarkIndex, val valueKrw: Double, val diffKrw: Double)

    fun today(series: List<FlowPoint>, simulated: List<FlowPoint>, index: BenchmarkIndex): Today? {
        val mine = series.lastOrNull() ?: return null
        val sim = simulated.lastOrNull() ?: return null
        return Today(index, sim.valueKrw, mine.valueKrw - sim.valueKrw)
    }

    /**
     * 수익률 비교 한 줄: "S&P500보다 3.90%p, 나스닥보다 6.80%p 낮고, KOSPI보다 2.90%p 높아요". 차이 0.005%p 미만은 "… 수익률과 같아요".
     * 비교할 지수가 없으면 null.
     */
    fun compareText(mine: Double?, others: List<Pair<BenchmarkIndex, Double?>>): String? {
        mine ?: return null
        val known = others.mapNotNull { (i, r) -> r?.let { i to (mine - it) * 100 } }
        if (known.isEmpty()) return null
        fun list(items: List<Pair<BenchmarkIndex, Double>>) =
            items.joinToString(", ") { (i, d) -> "${i.label}보다 ${String.format(Locale.US, "%.2f", abs(d))}%p" }
        val lower = known.filter { it.second <= -0.005 }
        val higher = known.filter { it.second >= 0.005 }
        val same = known.filter { abs(it.second) < 0.005 }
        val parts = mutableListOf<String>()
        if (lower.isNotEmpty()) parts += list(lower) + " 낮"
        if (higher.isNotEmpty()) parts += list(higher) + " 높"
        if (same.isNotEmpty()) parts += same.joinToString(", ") { it.first.label } + " 수익률과 같"
        return parts.joinToString("고, ") + "아요"
    }
}
