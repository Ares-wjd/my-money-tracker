package com.mymoneytracker.core

import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceHistory
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.TreeMap

class MarketDataTest {
    private val d1 = LocalDate.of(2026, 10, 1)
    private val d2 = LocalDate.of(2026, 10, 2)
    private val d5 = LocalDate.of(2026, 10, 5)
    private val stock = Holding(id = "s", accountId = "a", name = "삼성전자", code = "005930", market = Market.KR)

    private fun snapshot(latest: PricePoint? = null) = MarketSnapshot(
        prices = mapOf(MarketKeys.key(Market.KR, "005930") to PriceHistory(TreeMap(mapOf(d1 to 100.0, d2 to 110.0)), latest)),
        usdKrw = TreeMap(mapOf(d1 to 1400.0, d2 to -1.0)),
    )

    @Test
    fun fundsAreNotQuotable() {
        assertNull(MarketKeys.of(stock.copy(assetType = AssetType.FUND)))
        assertEquals("KR_005930", MarketKeys.of(stock))
        assertEquals("US_AAPL", MarketKeys.key(Market.NASDAQ, "aapl"))
    }

    @Test
    fun closeOnOrBeforeSkipsHolidays() {
        val s = snapshot()
        assertEquals(110.0, PriceLookup.at(stock, s, d5)!!.price, 1e-9)
        assertEquals(100.0, PriceLookup.at(stock, s, d1)!!.price, 1e-9)
        assertNull(PriceLookup.at(stock, s, d1.minusDays(1)))
    }

    @Test
    fun fxIgnoresNoDataMarkers() {
        val s = snapshot()
        assertEquals(1400.0, s.usdKrwAt(d5)!!, 1e-9)
        assertEquals(d1 to 1400.0, s.latestUsdKrw())
    }

    @Test
    fun currentPricePrefersNewest() {
        val s = snapshot(latest = PricePoint(120.0, d5))
        assertEquals(120.0, PriceLookup.current(stock, s)!!.price, 1e-9)
        val manualNewer = stock.copy(manualPrice = 130.0, manualPriceDate = d5.plusDays(1))
        assertEquals(130.0, PriceLookup.current(manualNewer, s)!!.price, 1e-9)
        val manualOlder = stock.copy(manualPrice = 90.0, manualPriceDate = d1)
        assertEquals(120.0, PriceLookup.current(manualOlder, s)!!.price, 1e-9)
    }

    @Test
    fun pastPriceFallsBackToManual() {
        val fund = stock.copy(code = "", assetType = AssetType.FUND, manualPrice = 1000.0, manualPriceDate = d5)
        assertEquals(1000.0, PriceLookup.at(fund, MarketSnapshot(), d1)!!.price, 1e-9)
    }
}
