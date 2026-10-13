package com.mymoneytracker.core

import com.mymoneytracker.core.chart.ChartCalculator
import com.mymoneytracker.core.chart.ChartInterval
import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceHistory
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.TreeMap

class ChartCalculatorTest {
    private val today = LocalDate.of(2026, 10, 9)

    @Test
    fun monthAndYearPointsEndToday() {
        val months = ChartCalculator.pointDates(ChartInterval.MONTH, today, LocalDate.of(2026, 7, 15))
        assertEquals(
            listOf(LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30), today),
            months,
        )
        val years = ChartCalculator.pointDates(ChartInterval.YEAR, today, LocalDate.of(2024, 3, 1))
        assertEquals(listOf(LocalDate.of(2024, 12, 31), LocalDate.of(2025, 12, 31), today), years)
    }

    @Test
    fun dayPointsStepBackFromToday() {
        val dates = ChartCalculator.pointDates(ChartInterval.DAY_5, today, today.minusDays(12))
        assertEquals(listOf(today.minusDays(10), today.minusDays(5), today), dates)
        // 전체 기간: 첫 기록일부터
        val all = ChartCalculator.pointDates(ChartInterval.DAY_1, today, LocalDate.of(2020, 1, 1))
        assertEquals(LocalDate.of(2020, 1, 1), all.first())
        assertEquals(today, all.last())
    }

    @Test
    fun niceTicksAndAxisLabels() {
        // 1,230만 ~ 2,870만 → 간격 (1640만 ÷ 4 = 410만) 이상 중 가장 작은 500만: 1,000만 ~ 3,000만
        assertEquals(listOf(10e6, 15e6, 20e6, 25e6, 30e6), ChartCalculator.niceTicks(12.3e6, 28.7e6))
        // 1.9억 ~ 2.3억 → 간격 1,000만
        assertEquals(listOf(1.9e8, 2.0e8, 2.1e8, 2.2e8, 2.3e8), ChartCalculator.niceTicks(1.9e8, 2.3e8).map { Math.round(it / 1e6) * 1e6 })
        // 값이 하나뿐이면 위아래로 10% 여유
        assertTrue(ChartCalculator.niceTicks(5e6, 5e6).let { it.first() <= 4.5e6 && it.last() >= 5.5e6 })
        assertEquals("1,000만", ChartCalculator.axisLabel(10e6))
        assertEquals("1.5억", ChartCalculator.axisLabel(1.5e8))
        assertEquals("2억", ChartCalculator.axisLabel(2e8))
        assertEquals("12억", ChartCalculator.axisLabel(1.2e9))
        assertEquals("5,000", ChartCalculator.axisLabel(5000.0))
        assertEquals("0", ChartCalculator.axisLabel(0.0))
    }

    @Test
    fun seriesUsesClosesOfEachDate() {
        val account = InvestmentAccount(id = "a", name = "국내")
        val holding = Holding(id = "h", accountId = "a", name = "삼성전자", code = "005930", market = Market.KR)
        val d1 = LocalDate.of(2026, 9, 30)
        val records = listOf(
            Record(id = "1", accountId = "a", type = RecordType.DEPOSIT, date = LocalDate.of(2026, 9, 1), amount = 1000.0, krwAmount = 1000.0),
            Record(id = "2", accountId = "a", type = RecordType.BUY, date = LocalDate.of(2026, 9, 2), holdingId = "h", quantity = 10.0, price = 100.0, createdAt = 1),
        )
        val snapshot = MarketSnapshot(
            prices = mapOf(MarketKeys.of(holding)!! to PriceHistory(TreeMap(mapOf(LocalDate.of(2026, 9, 29) to 120.0, LocalDate.of(2026, 10, 8) to 150.0)))),
        )
        val points = ChartCalculator.series(listOf(d1, today), today, listOf(account), listOf(holding), records, snapshot, null)
        assertEquals(1200.0, points[0].valueKrw, 1e-9)
        assertEquals(1500.0, points[1].valueKrw, 1e-9)
        assertEquals(1000.0, points[1].investedKrw, 1e-9)
    }
}
