package com.mymoneytracker.core

import com.mymoneytracker.core.chart.DayValue
import com.mymoneytracker.core.chart.StoredChart
import com.mymoneytracker.core.portfolio.FlowPoint
import com.mymoneytracker.core.portfolio.PeriodReturns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

// 웹 periodReturns.test.ts 와 같은 케이스.
class PeriodReturnsTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun p(date: LocalDate, value: Double, invested: Double) = FlowPoint(date, value, invested)

    @Test
    fun yearToDateWeightsMidYearDeposit() {
        // 작년 말 1,000 → 7/2 에 1,000 입금 → 연말 2,200
        val s = listOf(p(d(2025, 12, 31), 1000.0, 1000.0), p(d(2026, 7, 2), 2000.0, 2000.0), p(d(2026, 12, 31), 2200.0, 2000.0))
        val ytd = PeriodReturns.yearToDate(s, d(2026, 12, 31))!!
        assertEquals(200.0, ytd.profitKrw, 1e-9)
        assertEquals(1000.0, ytd.netFlowKrw, 1e-9)
        // 수정 디츠: 200 ÷ (1,000 + 1,000 × 183/365) — 7/2 아침에 넣은 돈은 7/2 ~ 12/31 (183일) 동안 투자
        assertEquals(200.0 / (1000.0 + 1000.0 * 183 / 365), ytd.rate!!, 1e-12)
    }

    @Test
    fun annualAfterOneYearElsePeriod() {
        val year = listOf(p(d(2025, 1, 1), 1000.0, 1000.0), p(d(2026, 1, 1), 1100.0, 1000.0))
        val long = PeriodReturns.longTerm(year, d(2026, 1, 1))!!
        assertEquals(0.1, long.annual!!, 1e-6)
        assertNull(long.period)

        val short = listOf(p(d(2026, 8, 1), 1000.0, 1000.0), p(d(2026, 10, 1), 1050.0, 1000.0))
        val s = PeriodReturns.longTerm(short, d(2026, 10, 1))!!
        assertNull(s.annual)
        assertEquals(0.05, s.period!!, 1e-12)
        assertEquals(61L, s.days)
        assertEquals("기간 ▲5.00% (2개월)", PeriodReturns.summaryText(null, s))
        assertEquals("올해 ▲5.00% (+50원) · 기간 ▲5.00% (2개월)", PeriodReturns.summaryText(PeriodReturns.yearToDate(short, d(2026, 10, 1)), s))
    }

    @Test
    fun monthsAndYears() {
        val s = listOf(p(d(2026, 7, 31), 1000.0, 1000.0), p(d(2026, 8, 31), 1100.0, 1000.0), p(d(2026, 9, 30), 1210.0, 1000.0))
        val m = PeriodReturns.months(s, d(2026, 9, 30), count = 3)
        assertEquals(listOf(d(2026, 7, 31), d(2026, 8, 1), d(2026, 9, 1)), m.map { it.from })
        assertEquals(0.0, m[0].rate!!, 1e-12) // 첫날 넣은 돈뿐 (수익 0)
        assertEquals(100.0, m[1].profitKrw, 1e-9)
        assertEquals(0.1, m[1].rate!!, 1e-12)
        assertEquals(0.1, m[2].rate!!, 1e-12)
        val y = PeriodReturns.years(s, d(2026, 9, 30))
        assertEquals(1, y.size)
        assertEquals(210.0, y[0].profitKrw, 1e-9)
    }

    @Test
    fun seriesSumsAccountsAndWaitsForStored() {
        fun chart(from: LocalDate, days: Map<LocalDate, Pair<Double, Double>>) = StoredChart(
            2, from, days.keys.max(), days.mapValues { DayValue(it.value.first, it.value.second, 0.0) }, emptyMap(), "", emptyMap(), null,
        )
        val a = chart(d(2026, 10, 1), mapOf(d(2026, 10, 1) to (100.0 to 100.0), d(2026, 10, 2) to (50.0 to 50.0)))
        val b = chart(d(2026, 10, 2), mapOf(d(2026, 10, 2) to (50.0 to 50.0)))
        val firsts = mapOf("a" to d(2026, 10, 1), "b" to d(2026, 10, 2))
        val today = d(2026, 10, 3)
        val s = PeriodReturns.series(firsts, mapOf("a" to a, "b" to b), today, mapOf("a" to (50.0 to 50.0), "b" to (55.0 to 50.0)))!!
        // 10/2 에 a → b 로 50 이체: 합치면 투자금 변화 없음
        assertEquals(listOf(100.0, 100.0, 105.0), s.map { it.valueKrw })
        assertEquals(listOf(100.0, 100.0, 100.0), s.map { it.investedKrw })
        // 저장된 값이 없는 계좌가 있으면 계산 중 (null)
        assertNull(PeriodReturns.series(firsts, mapOf("a" to a), today, emptyMap()))
    }
}
