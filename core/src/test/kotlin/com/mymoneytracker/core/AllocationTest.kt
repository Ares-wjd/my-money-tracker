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
        val buys = AllocationCalculator.buyOnly(view, 500.0).trades.associateBy { it.row.key }
        assertEquals(setOf("KR:AAA", "CASH"), buys.keys)
        assertEquals(400.0, buys.getValue("KR:AAA").amountKrw, 1e-9)
        assertEquals(4.0, buys.getValue("KR:AAA").shares!!, 1e-9)
        assertEquals(100.0, buys.getValue("CASH").amountKrw, 1e-9)
        assertEquals(null, buys.getValue("CASH").shares)
        // 목표 없는 종목이 있으면 부족분 합이 넣을 돈보다 커서 부족분 비율로 나눈다
        // (금 ETF 1주 50원 추가 → 넣은 뒤 합계 3050, 목표 1525/915/610, 부족분 25/415/110, 합 550 > 500)
        val withGold = records + Record(id = "5", accountId = "a", type = RecordType.BUY, date = day, holdingId = "h4", quantity = 1.0, price = 50.0)
        val accounts2 = PortfolioCalculator.summarize(listOf(isa, other), listOf(sp1, bond, sp2, gold), withGold, null).accounts
        // 금액 단위를 0.01원으로 두면 내림 영향 없이 비율 그대로
        val fine = target.copy(units = mapOf("KR:360750" to 0.01, "KR:AAA" to 0.01))
        val small = AllocationCalculator.buyOnly(AllocationCalculator.view(fine, accounts2, null), 500.0).trades.associateBy { it.row.key }
        assertEquals(500.0 * 25 / 550, small.getValue("KR:360750").amountKrw, 0.02)
        assertEquals(500.0 * 415 / 550, small.getValue("KR:AAA").amountKrw, 0.02)
        assertEquals(500.0 * 110 / 550, small.getValue("CASH").amountKrw, 0.02)
        assertFalse("KR:GLD1" in small)
    }

    @Test
    fun rebalanceToExactTargets() {
        val view = AllocationCalculator.view(target, summary.accounts, null)
        val trades = AllocationCalculator.rebalance(view).associateBy { it.row.key }
        // 1주 100원 단위로 0 쪽 내림: −250 → −200, +250 → +200
        assertEquals(-200.0, trades.getValue("KR:360750").amountKrw, 1e-9)
        assertEquals(200.0, trades.getValue("KR:AAA").amountKrw, 1e-9)
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
        assertFalse(AllocationCalculator.buyOnly(view, 1000.0).trades.any { it.row.key == "KR:AAA" })
    }

    @Test
    fun manualHoldingNotInAccount() {
        // 계좌에 없는 종목을 직접 추가: 실제 0%, 계산기 금액은 있지만 주 수는 없음
        val key = AllocationCalculator.manualKey(foreign = true, code = "qqqm")
        assertEquals("US:QQQM", key)
        val view = AllocationCalculator.view(
            target.copy(targets = mapOf("KR:360750" to 50.0, "KR:AAA" to 30.0, key to 20.0), includeCash = false, names = mapOf(key to "Invesco QQQM")),
            summary.accounts,
            null,
        )
        val q = view.rows.first { it.key == key }
        assertEquals("Invesco QQQM", q.name)
        assertEquals(0.0, q.currentWeight, 1e-9)
        assertTrue(q.isForeign)
        val buy = AllocationCalculator.buyOnly(view, 1000.0).trades.first { it.row.key == key }
        assertTrue(buy.amountKrw > 0)
        assertEquals(null, buy.shares)
    }

    @Test
    fun foreignKeyIgnoresExchange() {
        val nyse = Holding(id = "x", accountId = "a", name = "VOO", code = "voo", market = Market.NYSE)
        val other = Holding(id = "y", accountId = "b", name = "VOO", code = "VOO", market = Market.US_OTHER)
        assertEquals("US:VOO", AllocationCalculator.keyOf(nyse))
        assertEquals(AllocationCalculator.keyOf(nyse), AllocationCalculator.keyOf(other))
        assertEquals("KR:360750", AllocationCalculator.keyOf(sp1))
        assertEquals("id:f", AllocationCalculator.keyOf(Holding(id = "f", accountId = "a", name = "펀드", market = Market.KR)))
    }

    @Test
    fun wholeShareUnitsAndLeftover() {
        // 1주 100원 종목: 400원 → 4주, 남는 돈은 가장 부족한 종목부터 한 주씩
        val view = AllocationCalculator.view(target, summary.accounts, null)
        val plan = AllocationCalculator.buyOnly(view, 450.0)
        val byKey = plan.trades.associateBy { it.row.key }
        // 이상적: 넣은 뒤 2950 → 목표 1475/885/590, 부족분 0/385/90 → 내림 AAA 300(3주)
        // 남은 150 원: AAA 부족 85 > 0 → 1주 더 (400), 남은 50 원은 예수금으로 (남는 돈 0)
        assertEquals(400.0, byKey.getValue("KR:AAA").amountKrw, 1e-9)
        assertEquals(4.0, byKey.getValue("KR:AAA").shares!!, 1e-9)
        assertFalse(byKey.getValue("KR:AAA").fractional)
        assertEquals(50.0, byKey.getValue("CASH").amountKrw, 1e-9)
        assertEquals(0.0, plan.leftoverKrw, 1e-9)
        // 예수금을 빼면 남는 돈으로 표시
        val noCash = AllocationCalculator.view(target.copy(includeCash = false, targets = mapOf("KR:360750" to 60.0, "KR:AAA" to 40.0)), summary.accounts, null)
        val p2 = AllocationCalculator.buyOnly(noCash, 450.0)
        assertEquals(450.0 - p2.trades.sumOf { it.amountKrw }, p2.leftoverKrw, 1e-9)
        assertTrue(p2.leftoverKrw < 100.0)
    }

    @Test
    fun moneyUnitAndManualPrice() {
        // 금액 단위 30원: 385 → 360 (12단위), 소수 주 수 3.6주
        val view = AllocationCalculator.view(target.copy(units = mapOf("KR:AAA" to 30.0)), summary.accounts, null)
        val aaa = AllocationCalculator.buyOnly(view, 450.0).trades.first { it.row.key == "KR:AAA" }
        assertTrue(aaa.fractional)
        assertEquals(0.0, aaa.amountKrw % 30.0, 1e-9)
        assertEquals(aaa.amountKrw / 100.0, aaa.shares!!, 1e-9)

        // 종목코드 없이 직접 추가 + 현재가 직접 입력 (해외 $10, 환율 1000 → 1주 10,000원)
        val ipo = AllocationCalculator.codelessKey(foreign = true, id = "abc")
        assertTrue(AllocationCalculator.isCodeless(ipo))
        val t2 = target.copy(
            targets = mapOf("KR:360750" to 50.0, "KR:AAA" to 30.0, ipo to 20.0),
            includeCash = false,
            names = mapOf(ipo to "상장 예정 공모주"),
            prices = mapOf(ipo to 10.0),
        )
        val v2 = AllocationCalculator.view(t2, summary.accounts, 1000.0)
        val row = v2.rows.first { it.key == ipo }
        assertEquals("상장 예정 공모주", row.name)
        assertEquals(10_000.0, row.priceKrw!!, 1e-9)
        assertTrue(row.isForeign)
        val plan = AllocationCalculator.buyOnly(v2, 50_000.0)
        val buy = plan.trades.first { it.row.key == ipo }
        assertEquals(0.0, buy.amountKrw % 10_000.0, 1e-9)
        assertEquals(buy.amountKrw / 10_000.0, buy.shares!!, 1e-9)
    }

    @Test
    fun rebalanceRoundsTowardZeroByUnit() {
        val view = AllocationCalculator.view(target.copy(units = mapOf("KR:360750" to 1000.0)), summary.accounts, null)
        val trades = AllocationCalculator.rebalance(view).associateBy { it.row.key }
        // 360750: −250 → 단위 1000원이면 0 → 목록에서 빠짐. AAA: +250, 1주 100원 → 200
        assertFalse("KR:360750" in trades)
        assertEquals(200.0, trades.getValue("KR:AAA").amountKrw, 1e-9)
    }

    @Test
    fun noBuyHoldingIsSkippedByCalculatorOnly() {
        // 채권 ETF(AAA)를 "지금 매수 안 함": 표에는 남고, 계산기는 원래 목표 기준으로 나머지만
        val t = target.copy(noBuy = listOf("KR:AAA"), units = mapOf("KR:360750" to 0.01))
        val view = AllocationCalculator.view(t, summary.accounts, null)
        assertTrue(view.rows.any { it.key == "KR:AAA" })
        val plan = AllocationCalculator.buyOnly(view, 500.0)
        val byKey = plan.trades.associateBy { it.row.key }
        assertFalse("KR:AAA" in byKey)
        // 넣은 뒤 3000 → S&P 목표 1500(부족 0), 예수금 600(부족 100). 남는 400 은 S&P 50 : 예수금 20 비율
        assertEquals(400.0 * 50 / 70, byKey.getValue("KR:360750").amountKrw, 0.02)
        assertEquals(500.0 - byKey.getValue("KR:360750").amountKrw, byKey.getValue("CASH").amountKrw, 1e-6)
        assertFalse(AllocationCalculator.rebalance(view).any { it.row.key == "KR:AAA" })
    }

    @Test
    fun prefersMoreShortStockOverCashOnTie() {
        // 1주 300원짜리 종목이 목표보다 크게 부족하고 예수금은 조금 부족할 때: 1주를 산다 (예수금으로 남기지 않음)
        val pricey = Holding(id = "p", accountId = "a", name = "비싼 종목", code = "PRC", market = Market.KR, manualPrice = 300.0)
        val accounts = PortfolioCalculator.summarize(listOf(isa, other), listOf(sp1, bond, sp2, gold, pricey), records, null).accounts
        val t = target.copy(targets = mapOf("KR:360750" to 40.0, "KR:AAA" to 20.0, "KR:PRC" to 20.0, AllocationCalculator.CASH to 20.0))
        val plan = AllocationCalculator.buyOnly(AllocationCalculator.view(t, accounts, null), 400.0)
        val prc = plan.trades.firstOrNull { it.row.key == "KR:PRC" }
        assertEquals(300.0, prc?.amountKrw ?: 0.0, 1e-9)
    }
}
