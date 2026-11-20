package com.mymoneytracker.core.chart

import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceEstimator
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import java.time.YearMonth

/** 자산 추이 그래프의 보기 단위와 기본 표시 기간(개월). */
enum class ChartInterval(val label: String, val stepDays: Long) {
    DAY_1("1일", 1),
    DAY_5("5일", 5),
    DAY_10("10일", 10),
    MONTH("1달", 0),
    YEAR("1년", 0),
}

/**
 * 자산 추이 기간 (v2.14, 웹 chartRange 와 같은 규칙): 그 기간의 점만 보여 주고, 기간마다 맞는 보기 단위만 고른다.
 */
enum class ChartRange(val label: String) {
    ALL("전체"),
    YEARS_5("5년"),
    YEAR_1("1년"),
    THIS_YEAR("올해"),
    THIS_MONTH("이번 달"),
    ;

    /** 기간 시작일 (전체는 null = 첫 기록일부터). */
    fun start(today: LocalDate): LocalDate? = when (this) {
        ALL -> null
        YEARS_5 -> today.minusYears(5)
        YEAR_1 -> today.minusYears(1)
        THIS_YEAR -> today.withDayOfYear(1)
        THIS_MONTH -> today.withDayOfMonth(1)
    }

    /** 이 기간에 고를 수 있는 보기 단위. */
    val intervals: List<ChartInterval>
        get() = when (this) {
            ALL -> ChartInterval.entries
            YEARS_5 -> listOf(ChartInterval.DAY_5, ChartInterval.DAY_10, ChartInterval.MONTH, ChartInterval.YEAR)
            YEAR_1, THIS_YEAR -> listOf(ChartInterval.DAY_1, ChartInterval.DAY_5, ChartInterval.DAY_10, ChartInterval.MONTH)
            THIS_MONTH -> listOf(ChartInterval.DAY_1, ChartInterval.DAY_5)
        }

    /** [interval] 이 이 기간에 맞지 않으면 가장 가까운 단위 (같으면 짧은 쪽). */
    fun fit(interval: ChartInterval): ChartInterval =
        if (interval in intervals) interval else intervals.minWith(compareBy({ kotlin.math.abs(it.ordinal - interval.ordinal) }, { it.ordinal }))
}

data class ChartPoint(
    val date: LocalDate,
    val valueKrw: Double,
    val investedKrw: Double,
    val dividendsKrw: Double,
    /** 그날 가격이 없는 보유 종목이 있는지 (과거 종가를 아직 받지 않음 → 평가금이 낮게 나옴). */
    val missingPrice: Boolean = false,
) {
    val profitKrw: Double get() = valueKrw - investedKrw
}

object ChartCalculator {

    /**
     * 그래프의 점 날짜 (오래된 순, 첫 기록일부터 전체 기간). 마지막 점은 항상 [today].
     * @param firstDate 첫 기록 날짜. 이보다 앞선 점은 만들지 않는다.
     */
    fun pointDates(interval: ChartInterval, today: LocalDate, firstDate: LocalDate): List<LocalDate> {
        if (firstDate.isAfter(today)) return listOf(today)
        return when (interval) {
            ChartInterval.MONTH -> {
                val dates = mutableListOf<LocalDate>()
                var month = YearMonth.from(firstDate)
                val current = YearMonth.from(today)
                while (month.isBefore(current)) {
                    dates += month.atEndOfMonth()
                    month = month.plusMonths(1)
                }
                dates + today
            }
            ChartInterval.YEAR -> (firstDate.year until today.year).map { LocalDate.of(it, 12, 31) } + today
            else -> {
                generateSequence(today) { it.minusDays(interval.stepDays) }
                    .takeWhile { !it.isBefore(firstDate) }
                    .toList()
                    .reversed()
            }
        }
    }

    /**
     * 금액 보조선 (위아래 여유를 둔 보기 좋은 눈금 3~5개, 웹과 같은 규칙). 간격 = (최대 − 최소) ÷ 4 이상인 1·2·2.5·5·10 × 10ⁿ 중
     * 가장 작은 값. 첫 눈금은 최소 이하, 마지막 눈금은 최대 이상.
     */
    fun niceTicks(minValue: Double, maxValue: Double): List<Double> {
        var min = minValue
        var max = maxValue
        if (min == max) {
            val pad = maxOf(1.0, abs(max) * 0.1)
            min -= pad
            max += pad
        }
        val rough = (max - min) / 4
        val mag = 10.0.pow(floor(log10(rough)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.firstOrNull { it >= rough } ?: rough
        val ticks = mutableListOf<Double>()
        var v = floor(min / step) * step
        while (v <= max + step * 0.001) {
            ticks += v
            v += step
        }
        if (ticks.last() < max) ticks += ticks.last() + step
        return ticks
    }

    /** 보조선 금액 표시 (웹과 같음): 1억 이상 "1.5억", 1만 이상 "1,000만", 그 밖은 원 단위 숫자. */
    fun axisLabel(value: Double): String {
        val a = abs(value)
        val sign = if (value < 0) "-" else ""
        return when {
            a >= 1e8 -> sign + (if (a >= 1e9) "%.0f" else "%.1f").format(Locale.US, a / 1e8).removeSuffix(".0") + "억"
            a >= 1e4 -> sign + "%,d".format(Locale.US, Math.round(a / 1e4)) + "만"
            else -> sign + "%,d".format(Locale.US, Math.round(a))
        }
    }

    /**
     * 날짜별 평가금·투자금·누적 배당. [accountId] 가 있으면 그 계좌만.
     * 과거 날짜는 그날 가격([PriceEstimator]: 종가, 휴일은 직전 거래일, 시세를 모르는 기간은 앞뒤 가격으로 추정)과 환율,
     * 오늘은 현재가와 최신 환율을 쓴다. [estimator] 는 같은 종목의 다른 계좌 매매 단가도 쓰도록 전체 종목·기록으로 만든 것을 넘길 수 있다.
     */
    fun series(
        dates: List<LocalDate>,
        today: LocalDate,
        accounts: List<InvestmentAccount>,
        holdings: List<Holding>,
        records: List<Record>,
        snapshot: MarketSnapshot,
        manualUsdKrw: Double?,
        accountId: String? = null,
        estimator: PriceEstimator = PriceEstimator(holdings, records, snapshot),
    ): List<ChartPoint> {
        val targetAccounts = if (accountId == null) accounts else accounts.filter { it.id == accountId }
        return dates.map { date ->
            val isToday = !date.isBefore(today)
            val fx = (if (isToday) snapshot.latestUsdKrw()?.second else snapshot.usdKrwAt(date)) ?: manualUsdKrw
            val summary = PortfolioCalculator.summarize(
                accounts = targetAccounts,
                holdings = holdings,
                records = records,
                usdKrw = fx,
                priceOf = { h -> if (isToday) PriceLookup.current(h, snapshot) else estimator.at(h, date) },
                asOf = date,
            )
            ChartPoint(date, summary.valueKrw, summary.investedKrw, summary.dividendsKrw, summary.missingPrice)
        }
    }
}
