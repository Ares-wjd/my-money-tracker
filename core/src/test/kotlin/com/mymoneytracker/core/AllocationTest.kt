package com.mymoneytracker.core

import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.AllocationCalculator
import com.mymoneytracker.core.portfolio.AllocationTarget
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AllocationTest {
    private val day = LocalDate.of(2026, 10, 1)
    private val isa = InvestmentAccount(id = "a", name = "ISA")
    private val other = InvestmentAccount(id = "b", name = "일반")
    // 같은 종목(360750)이 두 계좌에 있으면 합친다
    private val sp1 = Holding(id = "h1", accountId = "a", name = "TIGER 미국S&P500", code = "360750", market = Market.KR, manualPrice = 100.0)
    private val bond = Holding(id = "h2", accountId = "a", name = "채권 ETF", code = "aaa", market = Market.KR, manualPrice = 100.0)
    private val sp2 = Holding(id = "h3", accountId = "b", name = "TIGER 미국S&P500", code = "360750", market = Market.KR, manualPrice = 100.0)
    private val gold = Holding(id = "h4", accountId = "a", name = "금 ETF", code = "GLD1", market = Market.KR, manualPrice = 50.0)
    private val records = listOf(
        Record(id = "1", accountId = "a", type = RecordType.BUY, date = day, holdingId = "h1", quantity = 10.0, price = 90.0),
        Record(id = "2", accountId = "a", type = RecordType.BUY, date = day, holdingId = "h2", quantity = 5.0, price = 100.0),
        Record(id = "3", accountId = "b", type = RecordType.BUY, date = day, holdingId = "h3", quantity = 5.0, price = 100.0),
        Record(id = "4", accountId = "a", type = RecordType.CASH_BALANCE, date = day, amount = 500.0),
    )
    private val summary = PortfolioCalculator.summarize(listOf(isa, other), listOf(sp1, bond, sp2, gold), records, null)
    private val target = AllocationTarget(
        name = "자산배분",
        accountIds = listOf("a", "b"),
        targets = mapOf("KR:360750" to 50.0, "KR:AAA" to 30.0, AllocationCalculator.CASH to 20.0),
        includeCash = true,
        threshold = 3.0,
    )

    @Test
    fun mergesSameHoldingAcrossAccounts() {
        val view = AllocationCalculator.view(target, summary.accounts, null)
        assertEquals(2500.0, view.totalKrw, 1e-9)
        val byKey = view.rows.associateBy { it.key }
        assertEquals(setOf("KR:360750", "KR:AAA", "CASH"), byKey.keys) // 0주·목표 없는 금 ETF 는 빠진다
        val sp = byKey.getValue("KR:360750")
        assertEquals(15.0, sp.quantity, 1e-9)
        assertEquals(0.6, sp.currentWeight, 1e-9)
        assertEquals(10.0, sp.diffPoints, 1e-9)
        assertEquals(-10.0, byKey.getValue("KR:AAA").diffPoints, 1e-9)
        assertEquals(0.0, byKey.getValue("CASH").diffPoints, 1e-9)
        assertTrue(view.highlighted(sp))
        assertFalse(view.highlighted(byKey.getValue("CASH")))
        assertEquals(listOf("KR:360750", "KR:AAA", "CASH"), view.rows.map { it.key }) // 목표 큰 순
    }

    @Test
    fun excludesCashWhenNotIncluded() {
        val view = AllocationCalculator.view(target.copy(includeCash = false, targets = mapOf("KR:360750" to 60.0, "KR:AAA" to 40.0)), summary.accounts, null)
        assertEquals(2000.0, view.totalKrw, 1e-9)
        assertEquals(0.75, view.rows.first { it.key == "KR:360750" }.currentWeight, 1e-9)
    }

    @Test
    fun includesUnheldTargetHolding() {
        val view = AllocationCalculator.view(target.copy(targets = target.targets + ("KR:GLD1" to 10.0)), summary.accounts, null)
        val g = view.rows.first { it.key == "KR:GLD1" }
        assertEquals(0.0, g.valueKrw, 1e-9)
        assertEquals(50.0, g.priceKrw!!, 1e-9)
        assertEquals("금 ETF", g.name)
    }

    @Test
    fun buyOnlyFillsDeficits() {
        val view = AllocationCalculator.view(target, summary.accounts, null)
        // 넣은 뒤 합계 3000 → 목표 1500/900/600, 부족분 0/400/100
        val buys = AllocationCalculator.buyOnly(view, 500.0).associateBy { it.row.key }
        assertEquals(setOf("KR:AAA", "CASH"), buys.keys)
        assertEquals(400.0, buys.getValue("KR:AAA").amountKrw, 1e-9)
        assertEquals(4.0, buys.getValue("KR:AAA").shares!!, 1e-9)
        assertEquals(100.0, buys.getValue("CASH").amountKrw, 1e-9)
        assertEquals(null, buys.getValue("CASH").shares)
        // 목표 없는 종목이 있으면 부족분 합이 넣을 돈보다 커서 부족분 비율로 나눈다
        // (금 ETF 1주 50원 추가 → 넣은 뒤 합계 3050, 목표 1525/915/610, 부족분 25/415/110, 합 550 > 500)
        val withGold = records + Record(id = "5", accountId = "a", type = RecordType.BUY, date = day, holdingId = "h4", quantity = 1.0, price = 50.0)
        val accounts2 = PortfolioCalculator.summarize(listOf(isa, other), listOf(sp1, bond, sp2, gold), withGold, null).accounts
        val small = AllocationCalculator.buyOnly(AllocationCalculator.view(target, accounts2, null), 500.0).associateBy { it.row.key }
        assertEquals(500.0 * 25 / 550, small.getValue("KR:360750").amountKrw, 1e-9)
        assertEquals(500.0 * 415 / 550, small.getValue("KR:AAA").amountKrw, 1e-9)
        assertEquals(500.0 * 110 / 550, small.getValue("CASH").amountKrw, 1e-9)
        assertFalse("KR:GLD1" in small)
    }

    @Test
    fun rebalanceToExactTargets() {
        val view = AllocationCalculator.view(target, summary.accounts, null)
        val trades = AllocationCalculator.rebalance(view).associateBy { it.row.key }
        assertEquals(-250.0, trades.getValue("KR:360750").amountKrw, 1e-9)
        assertEquals(250.0, trades.getValue("KR:AAA").amountKrw, 1e-9)
        assertFalse("CASH" in trades)
    }

    @Test
    fun excludedHoldingIsLeftOut() {
        // 채권 ETF 를 빼면 S&P 1500 + 예수금 500 = 2000 으로 계산 (목표에 남아 있어도 무시)
        val view = AllocationCalculator.view(
            target.copy(targets = mapOf("KR:360750" to 80.0, AllocationCalculator.CASH to 20.0, "KR:AAA" to 30.0), excluded = listOf("KR:AAA")),
            summary.accounts,
            null,
        )
        assertEquals(2000.0, view.totalKrw, 1e-9)
        assertEquals(listOf("KR:360750", "CASH"), view.rows.map { it.key })
        assertEquals(0.75, view.rows.first().currentWeight, 1e-9)
        assertFalse(AllocationCalculator.buyOnly(view, 1000.0).any { it.row.key == "KR:AAA" })
    }
}
