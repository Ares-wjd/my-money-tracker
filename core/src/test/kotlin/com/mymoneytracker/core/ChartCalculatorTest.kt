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
import org.junit.Assert.assertFalse
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
        val limited = ChartCalculator.pointDates(ChartInterval.DAY_1, today, LocalDate.of(2020, 1, 1))
        assertEquals(today.minusMonths(3), limited.first())
        assertTrue(ChartCalculator.hasEarlier(ChartInterval.DAY_1, today, LocalDate.of(2020, 1, 1), 1))
        assertFalse(ChartCalculator.hasEarlier(ChartInterval.MONTH, today, LocalDate.of(2020, 1, 1), 1))
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
