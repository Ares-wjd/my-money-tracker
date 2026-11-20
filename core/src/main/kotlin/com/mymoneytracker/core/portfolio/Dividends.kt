package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** 배당 한 건의 구분: 받음 (기록) · 확정 예정 (한투 일정) · 추정 예정 (내 기록). */
enum class DividendKind { RECEIVED, CONFIRMED, ESTIMATED }

/**
 * 배당 한 건 (세후). [date] 받은 날·지급일 (확정인데 지급일을 모르면 null), [month] 그 달, [amountLocal] 종목 통화 금액,
 * [amountKrw] 원화 (받음은 받은 날 환율, 예정은 지금 환율).
 */
data class DividendItem(
    val kind: DividendKind,
    val date: LocalDate?,
    val month: YearMonth,
    val holding: Holding,
    val accountId: String,
    val amountLocal: Double,
    val amountKrw: Double,
)

/** 한 달 (또는 한 해) 합계. */
data class DividendTotals(val received: Double = 0.0, val confirmed: Double = 0.0, val estimated: Double = 0.0) {
    val planned: Double get() = confirmed + estimated
    val total: Double get() = received + planned
}

/** 종목 하나의 최근 12개월 배당과 배당수익률 (현재가 기준 · 내 평단 기준, 다 판 종목은 null). */
data class DividendYield(
    val key: String,
    val holding: Holding,
    val last12Krw: Double,
    val yieldOnPrice: Double?,
    val yieldOnCost: Double?,
)

/** 홈 카드: 올해 받은 배당과 작년 같은 기간, 다음 달 예정과 작년 같은 달, 1~12월 올해·작년. */
data class DividendHome(
    val thisYear: Double,
    val lastYearSamePeriod: Double,
    val nextMonth: YearMonth,
    val nextMonthPlanned: Double,
    val lastYearNextMonth: Double,
    val months: List<DividendTotals>,
    val lastYearMonths: List<Double>,
)

/**
 * 배당 계산 (docs/FEATURES.md 1-10, 웹 dividends.ts 와 같은 규칙). 금액은 모두 세후·원화.
 * [accountIds] 가 null 이면 모든 계좌.
 */
object Dividends {
    /** 한투 배당 일정(세전)을 세후로 맞추는 원천징수율. */
    const val KR_TAX = 0.154
    const val US_TAX = 0.15

    /** 지급일을 모르는 확정 일정은 기준일이 이만큼 지나면 뺀다. */
    private const val UNKNOWN_PAY_DAYS = 30L

    private fun inAccounts(accountId: String, accountIds: Set<String>?) = accountIds == null || accountId in accountIds

    /** 받은 날 환율: 매매일 환율 → 그날 이전 환율 → 지금 환율. */
    private fun fxOn(snapshot: MarketSnapshot, date: LocalDate, today: LocalDate, fxNow: Double?): Double? =
        snapshot.tradeUsdKrw(date, today) ?: snapshot.usdKrwAt(date) ?: fxNow

    /** 받은 배당 (기록). 해외는 받은 날 환율로 원화 (환율을 모르면 빠진다). */
    fun received(
        holdings: List<Holding>,
        records: List<Record>,
        snapshot: MarketSnapshot,
        today: LocalDate,
        fxNow: Double?,
        accountIds: Set<String>? = null,
    ): List<DividendItem> {
        val byId = holdings.associateBy { it.id }
        return records.filter { it.type == RecordType.DIVIDEND && inAccounts(it.accountId, accountIds) }.mapNotNull { r ->
            val h = r.holdingId?.let(byId::get) ?: return@mapNotNull null
            val fx = if (h.currency == Currency.USD) fxOn(snapshot, r.date, today, fxNow) ?: return@mapNotNull null else 1.0
            DividendItem(DividendKind.RECEIVED, r.date, YearMonth.from(r.date), h, r.accountId, r.amount, r.amount * fx)
        }.sortedWith(compareBy({ it.date }, { it.holding.name }))
    }

    /** [holding] 의 [date] 까지 (그날 포함) 보유 수량. */
    private fun quantityOn(holding: Holding, records: List<Record>, date: LocalDate): Double =
        PortfolioCalculator.position(holding, records.filter { it.holdingId == holding.id && !it.date.isAfter(date) }).quantity

    /**
     * 예정 배당: 확정 (한투 일정 × 지금 보유 수량 × (1 − 원천징수)) + 추정 (반복 주기 → 최근 12개월 기록을 1년 뒤로, 같은 종목·같은 달은 앞의 것만).
     * 오늘 이후 12개월 안, 지금 보유한 종목만. 해외는 지금 환율.
     */
    fun planned(
        summary: PortfolioSummary,
        records: List<Record>,
        snapshot: MarketSnapshot,
        today: LocalDate,
        accountIds: Set<String>? = null,
    ): List<DividendItem> {
        val fxNow = summary.usdKrw
        val horizon = today.plusYears(1)
        val result = mutableListOf<DividendItem>()
        for (account in summary.accounts) {
            if (!inAccounts(account.account.id, accountIds)) continue
            for (v in account.holdings) {
                val h = v.holding
                val qty = v.position.quantity
                if (qty <= 1e-9) continue
                val usd = h.currency == Currency.USD
                val fx = if (usd) fxNow ?: continue else 1.0
                val confirmedMonths = mutableSetOf<YearMonth>()
                MarketKeys.of(h)?.let { key ->
                    for (e in snapshot.dividends[key].orEmpty()) {
                        val pay = e.pay
                        val keep = if (pay != null) !pay.isBefore(today) else !e.record.isBefore(today.minusDays(UNKNOWN_PAY_DAYS))
                        val day = pay ?: e.record
                        if (!keep || day.isAfter(horizon) || e.perShare <= 0) continue
                        val tax = if (e.currency == Currency.USD) US_TAX else KR_TAX
                        val local = e.perShare * qty * (1 - tax)
                        // 지급일을 모르면 기준일이 있는 달 (이미 지났으면 이번 달)
                        val month = YearMonth.from(pay ?: maxOf(e.record, today))
                        confirmedMonths += month
                        result += DividendItem(DividendKind.CONFIRMED, pay, month, h, account.account.id, local, local * fx)
                    }
                }
                val own = records.filter {
                    it.type == RecordType.DIVIDEND && it.holdingId == h.id && it.accountId == account.account.id && !it.date.isAfter(today)
                }.sortedBy { it.date }
                // 반복 주기 (v2.15): 마지막 두 번의 간격으로 월·분기배당을 알아보고 마지막 주당 배당으로 이어 간다
                val cycleMonths = mutableSetOf<YearMonth>()
                val step = cycleStep(own, today)
                val last = own.lastOrNull()
                if (step != null && last != null) {
                    val then = quantityOn(h, records, last.date)
                    if (then > 1e-9) {
                        val local = last.amount / then * qty
                        var k = 1L
                        while (true) {
                            val day = last.date.plusMonths(step * k++)
                            if (day.isAfter(horizon)) break
                            if (!day.isAfter(today)) continue
                            val month = YearMonth.from(day)
                            if (month in confirmedMonths) continue
                            cycleMonths += month
                            result += DividendItem(DividendKind.ESTIMATED, day, month, h, account.account.id, local, local * fx)
                        }
                    }
                }
                // 1년 전 기록: 확정·반복 주기가 없는 달만
                val start = today.minusDays(365)
                for (r in own) {
                    if (!r.date.isAfter(start)) continue
                    val day = r.date.plusYears(1)
                    if (!day.isAfter(today) || day.isAfter(horizon)) continue
                    val month = YearMonth.from(day)
                    if (month in confirmedMonths || month in cycleMonths) continue
                    val then = quantityOn(h, records, r.date)
                    if (then <= 1e-9) continue
                    val local = r.amount / then * qty
                    result += DividendItem(DividendKind.ESTIMATED, day, month, h, account.account.id, local, local * fx)
                }
            }
        }
        return result.sortedWith(compareBy({ it.month }, { it.date ?: it.month.atEndOfMonth() }, { it.holding.name }))
    }

    /**
     * 반복 주기 (개월, v2.15): 날짜순 배당 기록 [own] 의 마지막 두 번 간격이 20~40일이면 1 (월배당), 80~100일이면 3 (분기배당).
     * 마지막 기록이 (주기 + 20일)보다 오래됐으면 null.
     */
    fun cycleStep(own: List<Record>, today: LocalDate): Long? {
        if (own.size < 2) return null
        val prev = own[own.size - 2].date
        val last = own.last().date
        val gap = ChronoUnit.DAYS.between(prev, last)
        val step = when (gap) {
            in 20L..40L -> 1L
            in 80L..100L -> 3L
            else -> return null
        }
        return if (last.plusMonths(step).plusDays(20).isBefore(today)) null else step
    }

    /** [year] 의 1~12월 합계. */
    fun months(items: List<DividendItem>, year: Int): List<DividendTotals> = (1..12).map { m ->
        totals(items.filter { it.month == YearMonth.of(year, m) })
    }

    fun totals(items: List<DividendItem>): DividendTotals = DividendTotals(
        received = items.filter { it.kind == DividendKind.RECEIVED }.sumOf { it.amountKrw },
        confirmed = items.filter { it.kind == DividendKind.CONFIRMED }.sumOf { it.amountKrw },
        estimated = items.filter { it.kind == DividendKind.ESTIMATED }.sumOf { it.amountKrw },
    )

    /** 연도별 (첫 배당 해 ~ [today] 의 해, 예정은 그 해 것만). */
    fun years(items: List<DividendItem>, today: LocalDate): List<Pair<Int, DividendTotals>> {
        val first = items.minOfOrNull { it.month.year } ?: return emptyList()
        return (minOf(first, today.year)..today.year).map { y -> y to totals(items.filter { it.month.year == y }) }
    }

    /** 홈 카드 (전체 계좌). [received] · [planned] 는 위 함수의 결과. */
    fun home(received: List<DividendItem>, planned: List<DividendItem>, today: LocalDate): DividendHome {
        val lastYearToday = today.minusYears(1)
        val next = YearMonth.from(today).plusMonths(1)
        val all = received + planned
        return DividendHome(
            thisYear = received.filter { it.date!!.year == today.year && !it.date.isAfter(today) }.sumOf { it.amountKrw },
            lastYearSamePeriod = received.filter { it.date!!.year == lastYearToday.year && !it.date.isAfter(lastYearToday) }.sumOf { it.amountKrw },
            nextMonth = next,
            nextMonthPlanned = planned.filter { it.month == next }.sumOf { it.amountKrw },
            lastYearNextMonth = received.filter { it.month == next.minusYears(1) }.sumOf { it.amountKrw },
            months = months(all, today.year),
            lastYearMonths = months(received, today.year - 1).map { it.received },
        )
    }

    /** 종목별 최근 12개월 배당과 배당수익률, 그리고 전체 (현재가 기준, 내 평단 기준). 같은 종목(시세 키)은 계좌가 달라도 합친다. */
    data class YieldReport(val items: List<DividendYield>, val totalOnPrice: Double?, val totalOnCost: Double?)

    fun yields(summary: PortfolioSummary, received: List<DividendItem>, today: LocalDate, accountIds: Set<String>? = null): YieldReport {
        val fxNow = summary.usdKrw
        val start = today.minusDays(365)
        fun keyOf(h: Holding) = MarketKeys.of(h) ?: "id:${h.id}"
        val recent = received.filter { it.date!!.isAfter(start) && !it.date.isAfter(today) }
        data class Acc(val holding: Holding, var divLocal: Double = 0.0, var divKrw: Double = 0.0, var value: Double = 0.0, var cost: Double = 0.0, var qty: Double = 0.0)
        val byKey = linkedMapOf<String, Acc>()
        recent.forEach { d -> byKey.getOrPut(keyOf(d.holding)) { Acc(d.holding) }.apply { divLocal += d.amountLocal; divKrw += d.amountKrw } }
        for (account in summary.accounts) {
            if (!inAccounts(account.account.id, accountIds)) continue
            for (v in account.holdings) {
                val acc = byKey[keyOf(v.holding)] ?: continue
                acc.qty += v.position.quantity
                acc.value += v.marketValue ?: 0.0
                acc.cost += v.position.costBasis
            }
        }
        var divAll = 0.0
        var valueAll = 0.0
        var costAll = 0.0
        val items = byKey.map { (key, a) ->
            val held = a.qty > 1e-9
            val fx = if (a.holding.currency == Currency.USD) fxNow else 1.0
            if (held && fx != null && a.value > 0 && a.cost > 0) {
                divAll += a.divLocal * fx
                valueAll += a.value * fx
                costAll += a.cost * fx
            }
            DividendYield(
                key, a.holding, a.divKrw,
                if (held && a.value > 0) a.divLocal / a.value else null,
                if (held && a.cost > 0) a.divLocal / a.cost else null,
            )
        }.sortedByDescending { it.last12Krw }
        return YieldReport(items, if (valueAll > 0) divAll / valueAll else null, if (costAll > 0) divAll / costAll else null)
    }

    /** 비교 비율 (b 가 0 이하면 null). */
    fun change(now: Double, before: Double): Double? = if (before > 0.5) now / before - 1 else null
}
