package com.mymoneytracker.core

import com.mymoneytracker.core.chart.ChartInterval
import com.mymoneytracker.core.chart.ChartRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** 자산 추이 기간 (웹 chart.test.ts 와 같은 케이스). */
class ChartRangeTest {
    private val today = LocalDate.of(2026, 11, 20)

    @Test
    fun startsAndIntervals() {
        assertNull(ChartRange.ALL.start(today))
        assertEquals(LocalDate.of(2021, 11, 20), ChartRange.YEARS_5.start(today))
        assertEquals(LocalDate.of(2025, 11, 20), ChartRange.YEAR_1.start(today))
        assertEquals(LocalDate.of(2026, 1, 1), ChartRange.THIS_YEAR.start(today))
        assertEquals(LocalDate.of(2026, 11, 1), ChartRange.THIS_MONTH.start(today))
        // 맞지 않는 단위는 가장 가까운 단위 (같으면 짧은 쪽)
        assertEquals(ChartInterval.DAY_5, ChartRange.THIS_MONTH.fit(ChartInterval.MONTH))
        assertEquals(ChartInterval.DAY_1, ChartRange.THIS_MONTH.fit(ChartInterval.DAY_1))
        assertEquals(ChartInterval.MONTH, ChartRange.THIS_YEAR.fit(ChartInterval.YEAR))
        assertEquals(ChartInterval.DAY_5, ChartRange.YEARS_5.fit(ChartInterval.DAY_1))
        assertEquals(ChartInterval.YEAR, ChartRange.ALL.fit(ChartInterval.YEAR))
    }

    @Test
    fun shortRate() {
        assertEquals("▲322%", MoneyFormat.rateShort(3.22))
        assertEquals("▲21.4%", MoneyFormat.rateShort(0.2144))
        assertEquals("▼100%", MoneyFormat.rateShort(-1.0))
        assertEquals("0%", MoneyFormat.rateShort(0.0001))
        assertEquals("-", MoneyFormat.rateShort(null))
    }
}
