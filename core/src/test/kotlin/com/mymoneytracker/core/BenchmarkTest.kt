package com.mymoneytracker.core

import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceHistory
import com.mymoneytracker.core.portfolio.Benchmark
import com.mymoneytracker.core.portfolio.BenchmarkIndex
import com.mymoneytracker.core.portfolio.FlowPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.TreeMap

/** 지수 비교 (웹 benchmark.test.ts 와 같은 케이스). 숫자 계산은 공통 테스트 케이스 testdata/benchmark 에서도 검사한다. */
class BenchmarkTest {
    private fun d(s: String): LocalDate = LocalDate.parse(s)

    @Test
    fun compareTextGroupsLowerHigherSame() {
        assertEquals(
            "S&P500보다 3.90%p, 나스닥보다 6.80%p 낮고, KOSPI보다 2.90%p 높아요",
            Benchmark.compareText(0.082, listOf(BenchmarkIndex.SPX to 0.121, BenchmarkIndex.COMP to 0.15, BenchmarkIndex.KOSPI to 0.053)),
        )
        assertEquals("KOSPI보다 1.00%p 높아요", Benchmark.compareText(0.06, listOf(BenchmarkIndex.SPX to null, BenchmarkIndex.KOSPI to 0.05)))
        assertEquals("S&P500 수익률과 같아요", Benchmark.compareText(0.05, listOf(BenchmarkIndex.SPX to 0.05)))
        assertNull(Benchmark.compareText(null, listOf(BenchmarkIndex.SPX to 0.05)))
        assertNull(Benchmark.compareText(0.05, listOf(BenchmarkIndex.SPX to null)))
    }

    @Test
    fun withdrawalSellsUnitsAndMissingFxGivesNull() {
        val kospi = PriceHistory(TreeMap(mapOf(d("2026-03-02") to 100.0, d("2026-03-03") to 200.0)))
        val snap = MarketSnapshot(prices = mapOf(BenchmarkIndex.KOSPI.key to kospi, BenchmarkIndex.SPX.key to kospi))
        val series = listOf(FlowPoint(d("2026-03-02"), 1000.0, 1000.0), FlowPoint(d("2026-03-03"), 900.0, 500.0))
        // 3/3 에 500원 출금 → 2,000원어치 중 500원을 팔아 1,500원
        assertEquals(listOf(1000.0, 1500.0), Benchmark.simulate(series, BenchmarkIndex.KOSPI, snap, d("2026-03-10"))!!.map { it.valueKrw })
        // 달러 지수인데 환율이 없으면 계산하지 않는다
        assertNull(Benchmark.simulate(series, BenchmarkIndex.SPX, snap, d("2026-03-10")))
        assertNull(Benchmark.simulate(series, BenchmarkIndex.COMP, snap, d("2026-03-10")))
        assertTrue(Benchmark.covers(BenchmarkIndex.KOSPI, snap, d("2026-02-25")))
        assertFalse(Benchmark.covers(BenchmarkIndex.KOSPI, snap, d("2026-02-20")))
    }
}
