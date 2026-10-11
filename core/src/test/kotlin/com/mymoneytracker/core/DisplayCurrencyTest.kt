package com.mymoneytracker.core

import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.portfolio.DisplayConversion
import com.mymoneytracker.core.portfolio.DisplayCurrency
import com.mymoneytracker.core.portfolio.KrwHoldingCalculator
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayCurrencyTest {
    @Test
    fun krwModeConvertsOnlyForeignHoldings() {
        val usInKrw = DisplayConversion.of(Currency.USD, DisplayCurrency.KRW, 1_400.0)
        assertEquals(Currency.KRW, usInKrw.currency)
        assertEquals(140_000.0, usInKrw.convert(100.0)!!, 1e-9)

        val krInKrw = DisplayConversion.of(Currency.KRW, DisplayCurrency.KRW, 1_400.0)
        assertEquals(Currency.KRW, krInKrw.currency)
        assertEquals(100.0, krInKrw.convert(100.0)!!, 1e-9)
    }

    @Test
    fun foreignModeKeepsHoldingCurrency() {
        val us = DisplayConversion.of(Currency.USD, DisplayCurrency.FOREIGN, 1_400.0)
        assertEquals(Currency.USD, us.currency)
        assertEquals(100.0, us.convert(100.0)!!, 1e-9)
    }

    @Test
    fun missingFxCannotConvert() {
        val us = DisplayConversion.of(Currency.USD, DisplayCurrency.KRW, null)
        assertNull(us.convert(100.0))
        // 외화 보기는 환율이 없어도 표시할 수 있다.
        assertEquals(100.0, DisplayConversion.of(Currency.USD, DisplayCurrency.FOREIGN, null).convert(100.0)!!, 1e-9)
    }

    private val d1 = LocalDate.of(2026, 1, 5)
    private val d2 = LocalDate.of(2026, 3, 5)
    private val d3 = LocalDate.of(2026, 6, 5)
    private val fx = mapOf(d1 to 1_300.0, d2 to 1_400.0, d3 to 1_350.0)
    private val us = InvestmentAccount(id = "us", name = "해외")
    private val apple = Holding(id = "a", accountId = "us", name = "Apple", code = "AAPL", market = Market.NASDAQ, manualPrice = 200.0)

    private fun valuationOf(records: List<Record>, usdKrw: Double?) =
        PortfolioCalculator.summarize(listOf(us), listOf(apple), records, usdKrw).accounts.single().holdings.single()

    @Test
    fun krwViewUsesPurchaseDayRates() {
        val records = listOf(
            Record(id = "1", accountId = "us", type = RecordType.BUY, date = d1, holdingId = "a", quantity = 10.0, price = 100.0, createdAt = 1),
            Record(id = "2", accountId = "us", type = RecordType.BUY, date = d2, holdingId = "a", quantity = 10.0, price = 150.0, createdAt = 2),
        )
        val view = KrwHoldingCalculator.view(valuationOf(records, 1_500.0), records, 1_500.0) { fx[it] }!!
        // 원화 매입금액: 10×100×1300 + 10×150×1400 = 1,300,000 + 2,100,000
        assertEquals(3_400_000.0, view.costBasisKrw, 1e-6)
        assertEquals(170_000.0, view.averagePriceKrw, 1e-6)
        // 평가금: 20×200×1500
        assertEquals(6_000_000.0, view.marketValueKrw!!, 1e-6)
        assertEquals(2_600_000.0, view.unrealizedProfitKrw!!, 1e-6)
        // 주가 손익: (4000 − 2500)$ × 1500, 환차익: 2500$ × 1500 − 3,400,000
        assertEquals(2_250_000.0, view.stockProfitKrw!!, 1e-6)
        assertEquals(350_000.0, view.fxProfitKrw, 1e-6)
        assertEquals(2_600_000.0 / 3_400_000.0, view.returnRate!!, 1e-9)
    }

    @Test
    fun krwViewRealizesAtSellDayRateAndConvertsDividends() {
        val records = listOf(
            Record(id = "1", accountId = "us", type = RecordType.BUY, date = d1, holdingId = "a", quantity = 10.0, price = 100.0, createdAt = 1),
            Record(id = "2", accountId = "us", type = RecordType.SELL, date = d2, holdingId = "a", quantity = 4.0, price = 120.0, fee = 1.0, createdAt = 2),
            Record(id = "3", accountId = "us", type = RecordType.DIVIDEND, date = d3, holdingId = "a", amount = 2.0, createdAt = 3),
        )
        val view = KrwHoldingCalculator.view(valuationOf(records, 1_500.0), records, 1_500.0) { fx[it] }!!
        // 실현: 4×(120×1400 − 130,000) − 1×1400 = 4×38,000 − 1,400
        assertEquals(150_600.0, view.realizedProfitKrw, 1e-6)
        assertEquals(6 * 130_000.0, view.costBasisKrw, 1e-6)
        assertEquals(2 * 1_350.0, view.dividendsKrw, 1e-6)
    }

    @Test
    fun krwViewNeedsEveryRate() {
        val records = listOf(Record(id = "1", accountId = "us", type = RecordType.BUY, date = d1, holdingId = "a", quantity = 1.0, price = 100.0))
        assertNull(KrwHoldingCalculator.view(valuationOf(records, 1_500.0), records, 1_500.0) { null })
        assertNull(KrwHoldingCalculator.view(valuationOf(records, null), records, null) { fx[it] })
    }
}
