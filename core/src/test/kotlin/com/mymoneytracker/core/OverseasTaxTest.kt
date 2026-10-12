package com.mymoneytracker.core

import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.AllocationCalculator
import com.mymoneytracker.core.portfolio.AllocationTarget
import com.mymoneytracker.core.portfolio.OverseasTax
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.portfolio.TaxLimit
import com.mymoneytracker.core.portfolio.ToleranceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OverseasTaxTest {
    private val d = { y: Int, m: Int, day: Int -> LocalDate.of(y, m, day) }

    @Test
    fun realizedInYearUsesMovingAverageAndSellDateFx() {
        val voo = Holding(id = "v", accountId = "a", name = "VOO", code = "VOO", market = Market.AMEX)
        val kr = Holding(id = "k", accountId = "a", name = "삼성", code = "005930", market = Market.KR)
        val records = listOf(
            Record(id = "1", accountId = "a", type = RecordType.BUY, date = d(2025, 6, 1), holdingId = "v", quantity = 10.0, price = 100.0),
            Record(id = "2", accountId = "a", type = RecordType.SELL, date = d(2025, 12, 1), holdingId = "v", quantity = 1.0, price = 120.0),
            Record(id = "3", accountId = "a", type = RecordType.SELL, date = d(2026, 3, 2), holdingId = "v", quantity = 4.0, price = 150.0, cost = 1.0),
            // 국내 종목은 대상 아님
            Record(id = "4", accountId = "a", type = RecordType.BUY, date = d(2026, 1, 2), holdingId = "k", quantity = 1.0, price = 1000.0),
            Record(id = "5", accountId = "a", type = RecordType.SELL, date = d(2026, 2, 2), holdingId = "k", quantity = 1.0, price = 9000.0),
        )
        val fx = mapOf(d(2025, 6, 1) to 1000.0, d(2025, 12, 1) to 1100.0, d(2026, 3, 2) to 1200.0)
        val y2026 = OverseasTax.realizedInYear(listOf(voo, kr), records, 2026, { fx[it] }, 1300.0)
        // 4 × (150 × 1,200 − 100,000) − 1 × 1,200
        assertEquals(318_800.0, y2026.realizedKrw, 1e-6)
        assertFalse(y2026.estimated)
        assertEquals(32_000.0, OverseasTax.realizedInYear(listOf(voo), records, 2025, { fx[it] }, 1300.0).realizedKrw, 1e-6)
        // 환율이 없는 날은 최신 환율로 추정
        val est = OverseasTax.realizedInYear(listOf(voo), records, 2026, { if (it == d(2026, 3, 2)) null else fx[it] }, 1300.0)
        assertEquals(4 * (150 * 1300.0 - 100_000) - 1300.0, est.realizedKrw, 1e-6)
        assertTrue(est.estimated)
        assertEquals(mapOf("US:VOO" to 100_000.0), OverseasTax.averageCostKrwByKey(listOf(voo, kr), records, { fx[it] }, 1300.0))
    }

    // 해외 A (원화 평균 10만원, 지금 20만원 → 이익률 50%), 해외 B (30만원 → 20만원, 손실), 국내 C
    private val acc = InvestmentAccount(id = "a", name = "해외")
    private val a = Holding(id = "a1", accountId = "a", name = "A", code = "AAA", market = Market.NASDAQ, manualPrice = 200.0)
    private val b = Holding(id = "b1", accountId = "a", name = "B", code = "BBB", market = Market.NASDAQ, manualPrice = 200.0)
    private val c = Holding(id = "c1", accountId = "a", name = "C", code = "CCC", market = Market.KR, manualPrice = 100_000.0)
    private val records = listOf(
        Record(id = "1", accountId = "a", type = RecordType.BUY, date = d(2025, 1, 2), holdingId = "a1", quantity = 10.0, price = 100.0),
        Record(id = "2", accountId = "a", type = RecordType.BUY, date = d(2025, 1, 2), holdingId = "b1", quantity = 10.0, price = 300.0),
        Record(id = "3", accountId = "a", type = RecordType.BUY, date = d(2025, 1, 2), holdingId = "c1", quantity = 10.0, price = 90_000.0),
    )
    private val summary = PortfolioCalculator.summarize(listOf(acc), listOf(a, b, c), records, 1000.0)
    private val target = AllocationTarget(
        name = "t", accountIds = listOf("a"), targets = mapOf("US:AAA" to 20.0, "US:BBB" to 20.0, "KR:CCC" to 60.0),
    )
    private val view = AllocationCalculator.view(target, summary.accounts, 1000.0)
    private val costs = mapOf("US:AAA" to 100_000.0, "US:BBB" to 300_000.0)

    private fun plan(realized: Double) = AllocationCalculator.rebalance(view, TaxLimit(realized, costs))

    @Test
    fun keepsSellsWithinRemainingLimit() {
        // A 100만원 매도 → 이익 50만, B 100만원 매도 → 손실 50만. 남은 한도 = 240 − 230 + 50 = 60만 ≥ 50만
        val p = plan(2_300_000.0)
        val t = p.trades.associate { it.row.key to it.amountKrw }
        assertEquals(-1_000_000.0, t.getValue("US:AAA"), 1e-6)
        assertEquals(-1_000_000.0, t.getValue("US:BBB"), 1e-6)
        assertEquals(2_000_000.0, t.getValue("KR:CCC"), 1e-6)
        assertTrue(p.tax!!.reduced.isEmpty())
        assertEquals(0.0, p.tax!!.plannedGainKrw, 1e-6)
    }

    @Test
    fun scalesProfitSellsAndBuysToLimit() {
        // 남은 한도 = 240 − 280 + 50(손실) = 10만 → 이익 매도 50만의 20% → A 20만원(1주)만 매도
        val p = plan(2_800_000.0)
        val t = p.trades.associate { it.row.key to it.amountKrw }
        assertEquals(-200_000.0, t.getValue("US:AAA"), 1e-6)
        assertEquals(-1_000_000.0, t.getValue("US:BBB"), 1e-6) // 손실 매도는 그대로
        // 덜 판 80만원만큼 매수도 줄임: 200만 × (120/200) = 120만
        assertEquals(1_200_000.0, t.getValue("KR:CCC"), 1e-6)
        val tax = p.tax!!
        assertEquals(800_000.0, tax.reduced.single().second, 1e-6)
        assertEquals(-400_000.0, tax.plannedGainKrw, 1e-6)
        assertEquals(2_400_000.0, tax.totalKrw, 1e-6)
    }

    @Test
    fun noProfitSellsWhenAlreadyOverLimit() {
        val p = plan(3_500_000.0)
        val t = p.trades.associate { it.row.key to it.amountKrw }
        assertNull(t["US:AAA"])
        assertEquals(-1_000_000.0, t.getValue("US:BBB"), 1e-6)
        assertEquals(1_000_000.0, t.getValue("KR:CCC"), 1e-6)
        // 한도 없이 계산하면 그대로
        assertNull(AllocationCalculator.rebalance(view).tax)
        assertEquals(3, AllocationCalculator.rebalance(view).trades.size)
    }

    @Test
    fun separateUpperAndLowerBands() {
        val rule = target.copy(threshold = 5.0, relative = 25.0, upper = ToleranceRule(10.0, 50.0))
        assertEquals(10.0, rule.upperRule.band(40.0)!!, 1e-9)
        assertEquals(5.0, rule.lower.band(40.0)!!, 1e-9)
        assertEquals(5.0, rule.upperRule.band(10.0)!!, 1e-9)
        assertEquals(2.5, rule.lower.band(10.0)!!, 1e-9)
        assertEquals(1.0, rule.upperRule.band(2.0)!!, 1e-9)
        assertEquals(0.5, rule.lower.band(2.0)!!, 1e-9)
        // 실제 A 40% (목표 20 → 상단 +10, 하단 −5), C 20% (목표 60 → 상단 +10, 하단 −5)
        val v = AllocationCalculator.view(rule, summary.accounts, 1000.0)
        val rows = v.rows.associateBy { it.key }
        val ra = rows.getValue("US:AAA")
        assertEquals(20.0, ra.diffPoints, 1e-9)
        assertEquals(15.0 to 30.0, v.range(ra))
        assertTrue(v.highlighted(ra))
        // 차이 +8 은 상단 10 안이라 강조 안 함 (같은 기준이었으면 ±5 라 강조)
        val near = AllocationCalculator.view(rule.copy(targets = mapOf("US:AAA" to 32.0, "US:BBB" to 8.0, "KR:CCC" to 60.0)), summary.accounts, 1000.0)
        val nearA = near.rows.first { it.key == "US:AAA" }
        assertEquals(8.0, nearA.diffPoints, 1e-9)
        assertFalse(near.highlighted(nearA))
        assertTrue(AllocationCalculator.view(rule.copy(upper = null, targets = near.target.targets), summary.accounts, 1000.0).highlighted(nearA))
    }
}
