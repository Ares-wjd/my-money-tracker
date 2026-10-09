package com.mymoneytracker.core

import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.PricePoint
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PortfolioCalculatorTest {
    private val day1 = LocalDate.of(2026, 1, 2)
    private val day2 = LocalDate.of(2026, 2, 2)
    private val day3 = LocalDate.of(2026, 3, 2)

    private val kr = InvestmentAccount(id = "kr", name = "국내")
    private val us = InvestmentAccount(id = "us", name = "해외")
    private val samsung = Holding(id = "s", accountId = "kr", name = "삼성전자", code = "005930", market = Market.KR, manualPrice = 80_000.0)
    private val apple = Holding(id = "a", accountId = "us", name = "Apple", code = "AAPL", market = Market.NASDAQ, manualPrice = 200.0)

    private var seq = 0L
    private fun rec(
        account: String,
        type: RecordType,
        date: LocalDate = day1,
        currency: Currency = Currency.KRW,
        amount: Double = 0.0,
        krw: Double = 0.0,
        to: String? = null,
        holding: String? = null,
        qty: Double = 0.0,
        price: Double = 0.0,
        fee: Double = 0.0,
        tax: Double = 0.0,
    ) = Record(
        id = "r${seq}", accountId = account, type = type, date = date, currency = currency, amount = amount,
        krwAmount = krw, toAccountId = to, holdingId = holding, quantity = qty, price = price, fee = fee, tax = tax,
        createdAt = seq++,
    )

    @Test
    fun movingAverageAndRealizedProfit() {
        val records = listOf(
            rec("kr", RecordType.BUY, day1, holding = "s", qty = 10.0, price = 70_000.0, fee = 100.0),
            rec("kr", RecordType.BUY, day2, holding = "s", qty = 10.0, price = 80_000.0, fee = 100.0),
            rec("kr", RecordType.SELL, day3, holding = "s", qty = 5.0, price = 90_000.0, fee = 100.0, tax = 900.0),
        )
        val p = PortfolioCalculator.position(samsung, records)
        assertEquals(15.0, p.quantity, 1e-9)
        assertEquals(75_000.0, p.averagePrice, 1e-9)
        // (90,000 - 75,000) × 5 - 100 - 900
        assertEquals(74_000.0, p.realizedProfit, 1e-9)
        assertEquals(1_200.0, p.feesAndTaxes, 1e-9)
    }

    @Test
    fun sellingEverythingResetsAverage() {
        val records = listOf(
            rec("kr", RecordType.BUY, day1, holding = "s", qty = 2.0, price = 100.0),
            rec("kr", RecordType.SELL, day2, holding = "s", qty = 2.0, price = 150.0),
            rec("kr", RecordType.BUY, day3, holding = "s", qty = 1.0, price = 120.0),
        )
        val p = PortfolioCalculator.position(samsung, records)
        assertEquals(1.0, p.quantity, 1e-9)
        assertEquals(120.0, p.averagePrice, 1e-9)
        assertEquals(100.0, p.realizedProfit, 1e-9)
    }

    @Test
    fun accountCashInvestedAndReturns() {
        val records = listOf(
            rec("kr", RecordType.DEPOSIT, day1, amount = 1_000_000.0, krw = 1_000_000.0),
            rec("kr", RecordType.BUY, day1, holding = "s", qty = 10.0, price = 70_000.0, fee = 500.0),
            rec("kr", RecordType.DIVIDEND, day2, holding = "s", amount = 3_000.0),
            rec("kr", RecordType.CASH_ADJUST, day2, amount = 200.0), // 예탁금 이용료
        )
        val summary = PortfolioCalculator.summarize(listOf(kr), listOf(samsung), records, usdKrw = null)
        val a = summary.accounts.single()
        assertEquals(1_000_000.0, a.investedKrw, 1e-9)
        // 1,000,000 - 700,500 + 3,000 + 200
        assertEquals(302_700.0, a.cash.krw, 1e-9)
        // 종목 800,000 + 예수금 302,700
        assertEquals(1_102_700.0, a.valueKrw, 1e-9)
        assertEquals(102_700.0, a.profitKrw, 1e-9)
        assertEquals(99_700.0, a.profitExDividendsKrw, 1e-9)
        assertEquals(0.1027, a.returnRate!!, 1e-9)
        assertEquals(0.0997, a.returnRateExDividends!!, 1e-9)

        val h = a.holdings.single()
        assertEquals(100_000.0, h.unrealizedProfit!!, 1e-9)
        assertEquals(100_000.0 / 700_000.0, h.returnRate!!, 1e-9)
        assertEquals(103_000.0 / 700_000.0, h.returnRateWithDividends!!, 1e-9)
        assertFalse(a.missingFx)
    }

    @Test
    fun transferAndExchangeBetweenAccounts() {
        val records = listOf(
            rec("kr", RecordType.DEPOSIT, day1, amount = 2_000_000.0, krw = 2_000_000.0),
            rec("kr", RecordType.TRANSFER, day1, amount = 1_000_000.0, krw = 1_000_000.0, to = "us"),
            rec("us", RecordType.EXCHANGE, day2, currency = Currency.KRW, amount = 700.0, krw = 980_000.0),
            rec("us", RecordType.BUY, day2, holding = "a", qty = 3.0, price = 180.0, fee = 1.0),
            rec("us", RecordType.DIVIDEND, day3, holding = "a", amount = 2.0),
        )
        val summary = PortfolioCalculator.summarize(listOf(kr, us), listOf(samsung, apple), records, usdKrw = 1_400.0)
        val krSummary = summary.accounts.first { it.account.id == "kr" }
        val usSummary = summary.accounts.first { it.account.id == "us" }

        assertEquals(1_000_000.0, krSummary.investedKrw, 1e-9)
        assertEquals(1_000_000.0, usSummary.investedKrw, 1e-9)
        assertEquals(2_000_000.0, summary.investedKrw, 1e-9)

        assertEquals(20_000.0, usSummary.cash.krw, 1e-9)
        // 700 - 541 + 2
        assertEquals(161.0, usSummary.cash.usd, 1e-9)
        // 종목 600$ × 1400 + 20,000 + 161$ × 1400
        assertEquals(840_000.0 + 20_000.0 + 225_400.0, usSummary.valueKrw, 1e-9)
        assertEquals(2.0 * 1_400.0, usSummary.dividendsKrw, 1e-9)
    }

    @Test
    fun missingFxAndPriceAreFlagged() {
        val noPrice = apple.copy(manualPrice = null)
        val records = listOf(rec("us", RecordType.BUY, day1, holding = "a", qty = 1.0, price = 100.0))
        val summary = PortfolioCalculator.summarize(listOf(us), listOf(noPrice), records, usdKrw = null)
        val a = summary.accounts.single()
        assertTrue(a.missingFx)
        assertTrue(a.missingPrice)
        assertNull(a.holdings.single().marketValueKrw)
    }

    @Test
    fun asOfIgnoresLaterRecords() {
        val records = listOf(
            rec("kr", RecordType.DEPOSIT, day1, amount = 100.0, krw = 100.0),
            rec("kr", RecordType.DEPOSIT, day3, amount = 50.0, krw = 50.0),
        )
        val summary = PortfolioCalculator.summarize(listOf(kr), emptyList(), records, null, asOf = day2)
        assertEquals(100.0, summary.investedKrw, 1e-9)
    }

    @Test
    fun quantityAtDateExcludesEditedRecord() {
        val buy = rec("kr", RecordType.BUY, day1, holding = "s", qty = 10.0, price = 1.0)
        val sell = rec("kr", RecordType.SELL, day2, holding = "s", qty = 4.0, price = 1.0)
        assertEquals(6.0, PortfolioCalculator.quantityAt(samsung, listOf(buy, sell), day3), 1e-9)
        assertEquals(10.0, PortfolioCalculator.quantityAt(samsung, listOf(buy, sell), day3, excludeRecordId = sell.id), 1e-9)
        assertEquals(0.0, PortfolioCalculator.quantityAt(samsung, listOf(buy, sell), day1.minusDays(1)), 1e-9)
    }

    @Test
    fun priceOverrideIsUsed() {
        val records = listOf(rec("kr", RecordType.BUY, day1, holding = "s", qty = 1.0, price = 1.0))
        val summary = PortfolioCalculator.summarize(
            listOf(kr), listOf(samsung), records, null,
            priceOf = { PricePoint(5.0, day1) },
        )
        assertEquals(5.0, summary.accounts.single().holdings.single().marketValue!!, 1e-9)
    }
}
