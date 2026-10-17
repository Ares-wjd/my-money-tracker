package com.mymoneytracker.core

import com.mymoneytracker.core.chart.DayValue
import com.mymoneytracker.core.chart.StoredChart
import com.mymoneytracker.core.portfolio.FlowPoint
import com.mymoneytracker.core.portfolio.PeriodReturns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

// 웹 periodReturns.test.ts 와 같은 케이스. 올해·연평균·월별·연도별 숫자는 공통 케이스 파일(testdata/period)에서 검사한다.
class PeriodReturnsTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun p(date: LocalDate, value: Double, invested: Double) = FlowPoint(date, value, invested)

    @Test
    fun summaryTextWithoutYearToDate() {
        val s = PeriodReturns.longTerm(listOf(p(d(2026, 8, 1), 1000.0, 1000.0), p(d(2026, 10, 1), 1050.0, 1000.0)), d(2026, 10, 1))
        assertEquals("기간 ▲5.00% (2개월)", PeriodReturns.summaryText(null, s))
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
