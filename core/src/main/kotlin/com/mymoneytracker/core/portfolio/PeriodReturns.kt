package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.chart.ChartStore
import com.mymoneytracker.core.chart.StoredChart
import com.mymoneytracker.core.model.Record
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.pow

/** 하루의 평가금·투자금 합 (기간 수익률 계산용). 투자금이 늘거나 준 만큼이 그날 넣고 뺀 돈. */
data class FlowPoint(val date: LocalDate, val valueKrw: Double, val investedKrw: Double)

/** 한 기간의 수익: 수익금 = 끝 평가금 − 시작 평가금 − 넣은 돈, 수익률은 수정 디츠 방식 (분모가 0 이하면 null). */
data class PeriodResult(
    val from: LocalDate,
    val to: LocalDate,
    val profitKrw: Double,
    val rate: Double?,
    /** 기간 동안 넣은 돈 − 뺀 돈 */
    val netFlowKrw: Double,
)

/** 장기 수익률: 1년 이상이면 연평균(XIRR), 1년 미만이면 기간 수익률 ([days] 일). */
data class LongTermReturn(val annual: Double?, val period: Double?, val days: Long)

/**
 * 올해 수익률·연평균 수익률·월별·연도별 수익 (웹 periodReturns.ts 와 같은 규칙, v2.8). 배당은 평가금(예수금)에 들어 있어 수익에 포함된다.
 * 계좌 여러 개를 합치면 계좌 사이 이체는 투자금 증감이 서로 상쇄된다.
 */
object PeriodReturns {

    /**
     * 고른 계좌들의 일별 평가금·투자금 합: 첫 기록일부터 어제까지는 저장된 일별 값, 오늘은 [todayValues] (계좌 id → 평가금, 투자금).
     * 저장된 값이 없는 계좌가 있으면(아직 계산 중) null. 저장한 마지막 날 뒤 며칠은 마지막 값을 이어 쓴다.
     */
    fun series(
        firstDates: Map<String, LocalDate>,
        stored: Map<String, StoredChart>,
        today: LocalDate,
        todayValues: Map<String, Pair<Double, Double>>,
    ): List<FlowPoint>? {
        val first = firstDates.values.minOrNull() ?: return emptyList()
        val days = mutableMapOf<String, TreeMap<LocalDate, Pair<Double, Double>>>()
        for ((id, accountFirst) in firstDates) {
            val chart = stored[id]?.takeIf { it.from == accountFirst } ?: if (accountFirst.isBefore(today)) return null else continue
            days[id] = TreeMap(chart.days.mapValues { it.value.valueKrw to it.value.investedKrw })
        }
        val out = mutableListOf<FlowPoint>()
        var date = first
        while (date.isBefore(today)) {
            var value = 0.0
            var invested = 0.0
            for ((id, accountFirst) in firstDates) {
                if (date.isBefore(accountFirst)) continue
                val day = days[id]?.floorEntry(date)?.value ?: continue
                value += day.first
                invested += day.second
            }
            out += FlowPoint(date, value, invested)
            date = date.plusDays(1)
        }
        val now = firstDates.keys.mapNotNull { todayValues[it] }
        out += FlowPoint(today, now.sumOf { it.first }, now.sumOf { it.second })
        return out
    }

    /** [ids] 계좌(null 이면 전체)의 일별 평가금·투자금 합. 오늘은 [summary] 의 지금 값. 계산 중이면 null. */
    fun forAccounts(
        summary: PortfolioSummary,
        records: List<Record>,
        stored: Map<String, StoredChart>,
        today: LocalDate,
        ids: Set<String>? = null,
    ): List<FlowPoint>? {
        val accounts = summary.accounts.filter { ids == null || it.account.id in ids }
        val firsts = ChartStore.firstDates(accounts.map { it.account }, records)
        return series(firsts, stored, today, accounts.associate { it.account.id to (it.valueKrw to it.investedKrw) })
    }

    /**
     * [from] ~ [to] (둘 다 포함) 의 수익. 시작 평가금은 [from] 전날 값 (없으면 0). 기간에 점이 없으면 null.
     * 기록이 [from] 보다 늦게 시작하면 첫 기록일부터 (넣은 돈이 기간 전체를 채우지 못한 것처럼 보이지 않게).
     */
    fun period(series: List<FlowPoint>, fromDate: LocalDate, to: LocalDate): PeriodResult? {
        val from = series.firstOrNull()?.date?.let { maxOf(it, fromDate) } ?: return null
        val points = series.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        if (points.isEmpty()) return null
        val before = series.lastOrNull { it.date.isBefore(from) }
        val v0 = before?.valueKrw ?: 0.0
        var invested = before?.investedKrw ?: 0.0
        val start = from.minusDays(1)
        val end = points.last().date
        val total = ChronoUnit.DAYS.between(start, end).toDouble()
        var flows = 0.0
        var weighted = 0.0
        for (p in points) {
            val flow = p.investedKrw - invested
            invested = p.investedKrw
            if (abs(flow) < 1e-9) continue
            flows += flow
            // 넣은 돈은 그날 아침에 들어온 것으로 본다 (첫날 넣은 돈은 기간 전체만큼)
            weighted += flow * (ChronoUnit.DAYS.between(p.date, end) + 1) / total
        }
        val v1 = points.last().valueKrw
        val profit = v1 - v0 - flows
        val base = v0 + weighted
        return PeriodResult(from, end, profit, if (base > 1e-9) profit / base else null, flows)
    }

    /** 올해 (1월 1일 ~ 오늘). */
    fun yearToDate(series: List<FlowPoint>, today: LocalDate): PeriodResult? = period(series, LocalDate.of(today.year, 1, 1), today)

    /** 처음부터 오늘까지: 1년(365일) 이상이면 연평균 수익률(XIRR), 미만이면 기간 수익률. */
    fun longTerm(series: List<FlowPoint>, today: LocalDate): LongTermReturn? {
        val first = series.firstOrNull()?.date ?: return null
        val days = ChronoUnit.DAYS.between(first, today)
        if (days < 365) return LongTermReturn(null, period(series, first, today)?.rate, days)
        return LongTermReturn(xirr(series, today), null, days)
    }

    /** 넣고 뺀 돈(투자금 증감)과 오늘 평가금으로 계산한 연환산 수익률. 풀 수 없으면 null. */
    fun xirr(series: List<FlowPoint>, today: LocalDate): Double? {
        val first = series.firstOrNull()?.date ?: return null
        val flows = mutableListOf<Pair<Double, Double>>() // (연 단위 시점, 금액: 넣은 돈 −, 받은 돈 +)
        var invested = 0.0
        for (p in series) {
            val flow = p.investedKrw - invested
            invested = p.investedKrw
            if (abs(flow) > 1e-9) flows += ChronoUnit.DAYS.between(first, p.date) / 365.0 to -flow
        }
        val last = series.last()
        flows += ChronoUnit.DAYS.between(first, today) / 365.0 to last.valueKrw
        if (flows.none { it.second < 0 } || flows.none { it.second > 0 }) return null
        val npv = { r: Double -> flows.sumOf { (t, cf) -> cf / (1 + r).pow(t) } }
        var lo = -0.9999
        var hi = 10.0
        var fLo = npv(lo)
        if (fLo * npv(hi) > 0) return null
        repeat(200) {
            val mid = (lo + hi) / 2
            val fMid = npv(mid)
            if (fLo * fMid <= 0) hi = mid else {
                lo = mid
                fLo = fMid
            }
        }
        return (lo + hi) / 2
    }

    /** 최근 [count] 달의 월별 수익 (오래된 달부터, 기록이 시작되기 전 달은 뺀다). 이번 달은 오늘까지. */
    fun months(series: List<FlowPoint>, today: LocalDate, count: Int = 12): List<PeriodResult> {
        val first = series.firstOrNull()?.date ?: return emptyList()
        return (count - 1 downTo 0).mapNotNull { back ->
            val start = today.withDayOfMonth(1).minusMonths(back.toLong())
            val end = minOf(start.plusMonths(1).minusDays(1), today)
            if (end.isBefore(first)) null else period(series, maxOf(start, first), end)
        }
    }

    /** 연도별 수익 (첫해부터 올해까지, 올해는 오늘까지). */
    fun years(series: List<FlowPoint>, today: LocalDate): List<PeriodResult> {
        val first = series.firstOrNull()?.date ?: return emptyList()
        return (first.year..today.year).mapNotNull { y ->
            period(series, maxOf(LocalDate.of(y, 1, 1), first), minOf(LocalDate.of(y, 12, 31), today))
        }
    }

    /** 기간 길이 글: 31일 미만 "n일", 그 이상 "n개월". */
    fun lengthText(days: Long): String = if (days < 31) "${days}일" else "${days / 30}개월"

    /** 홈·계좌 상세 한 줄: "올해 ▲8.20% (+234.0만원) · 연평균 ▲11.40%" 또는 "… · 기간 ▲5.00% (2개월)". */
    fun summaryText(ytd: PeriodResult?, long: LongTermReturn?): String = listOfNotNull(
        ytd?.let { "올해 ${MoneyFormat.rate(it.rate)} (${signedShort(it.profitKrw)})" },
        long?.let {
            if (it.annual != null) "연평균 ${MoneyFormat.rate(it.annual)}"
            else it.period?.let { p -> "기간 ${MoneyFormat.rate(p)} (${lengthText(it.days)})" }
        },
    ).joinToString(" · ")

    private fun signedShort(value: Double): String = (if (value > 0.5) "+" else "") + MoneyFormat.shortWon(value)
}
