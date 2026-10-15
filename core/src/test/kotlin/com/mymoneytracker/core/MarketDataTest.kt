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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun goldIsQuotedByKrxCode() {
        val gold = Holding(id = "g", accountId = "a", name = "금", code = AssetType.GOLD_CODE, market = Market.KR, assetType = AssetType.GOLD)
        assertEquals("KR_M04020000", MarketKeys.of(gold))
        assertNull(MarketKeys.of(gold.copy(code = "")))
        assertEquals("g", AssetType.GOLD.unit)
        assertEquals("주", AssetType.ETF.unit)
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

    @Test
    fun tradeRateNeedsFetchedDays() {
        val today = LocalDate.of(2026, 10, 9)
        // 10/3(토)·10/4(일)은 데이터 없음(-1), 10/2(금) 1,400
        val s = MarketSnapshot(usdKrw = TreeMap(mapOf(d2 to 1400.0, LocalDate.of(2026, 10, 3) to -1.0, LocalDate.of(2026, 10, 4) to -1.0, LocalDate.of(2026, 10, 8) to 1410.0)))
        assertEquals(1400.0, s.tradeUsdKrw(LocalDate.of(2026, 10, 4), today)!!, 1e-9)
        assertEquals(1400.0, s.tradeUsdKrw(d2, today)!!, 1e-9)
        // 10/5 는 아직 받지 않았다 → 옛 환율을 쓰지 않는다
        assertNull(s.tradeUsdKrw(LocalDate.of(2026, 10, 5), today))
        // 오늘은 최신 환율
        assertEquals(1410.0, s.tradeUsdKrw(today, today)!!, 1e-9)
    }

    @Test
    fun dailyChangeUsesPrevCloseOnlyForAutoPrice() {
        val s = snapshot(latest = PricePoint(110.0, d5)).let { it.copy(prices = it.prices.mapValues { (_, h) -> h.copy(prevClose = 100.0) }) }
        assertEquals(0.1, PriceLookup.dailyChangeRate(stock, s)!!, 1e-9)
        // 직접 입력 가격이 더 최근이면 오늘 수익률을 보여주지 않는다
        assertNull(PriceLookup.dailyChangeRate(stock.copy(manualPrice = 130.0, manualPriceDate = d5.plusDays(1)), s))
        // 전일 종가가 없으면 없음
        assertNull(PriceLookup.dailyChangeRate(stock, snapshot(latest = PricePoint(110.0, d5))))
    }

    @Test
    fun fxRulesRecentWeekAndTwoAgreeingFetches() {
        // 웹 market.test.ts 와 같은 케이스
        val today = LocalDate.of(2026, 10, 14)
        assertTrue(com.mymoneytracker.core.market.FxRules.isRecent(today.minusDays(1), today))
        assertTrue(com.mymoneytracker.core.market.FxRules.isRecent(today.minusDays(7), today))
        assertFalse(com.mymoneytracker.core.market.FxRules.isRecent(today.minusDays(8), today))
        assertFalse(com.mymoneytracker.core.market.FxRules.isRecent(today, today))
        val rules = com.mymoneytracker.core.market.FxRules
        assertTrue(rules.needsMore(listOf(1394.5)))
        assertEquals(1394.5, rules.agreed(listOf(1394.5, 1394.5))!!, 1e-9)
        assertTrue(rules.needsMore(listOf(1394.5, 1390.0)))
        assertEquals(1394.5, rules.agreed(listOf(1394.5, 1390.0, 1394.5))!!, 1e-9)
        assertNull(rules.agreed(listOf(1394.5, 1390.0, 1391.0)))
        assertFalse(rules.needsMore(listOf(1394.5, 1390.0, 1391.0)))
        assertEquals(-1.0, rules.agreed(listOf(-1.0, -1.0))!!, 1e-9)
        // 오늘 받은 환율이 과거 목록보다 최근이면 최신 환율
        val s = MarketSnapshot(usdKrw = java.util.TreeMap(mapOf(today.minusDays(1) to 1390.0)), usdKrwLatest = today to 1400.0)
        assertEquals(today to 1400.0, s.latestUsdKrw())
        assertEquals(1390.0, s.usdKrwAt(today.minusDays(1))!!, 1e-9)
    }

    @Test
    fun agreeDailyKisPages() {
        val rules = com.mymoneytracker.core.market.FxRules
        val d = { day: Int -> LocalDate.of(2026, 10, day) }
        val first = mapOf(d(12) to 1335.8, d(13) to 1341.5, d(14) to 1342.3)
        // 두 번 같으면 고정, 둘 다 없는 날(10/10·10/11 주말)은 없음(-1)
        val same = rules.agreeDaily(listOf(first, first), d(10), d(14))
        assertEquals(mapOf(d(10) to -1.0, d(11) to -1.0, d(12) to 1335.8, d(13) to 1341.5, d(14) to 1342.3), same)
        assertFalse(rules.needsMoreDaily(listOf(first, first), d(10), d(14)))
        // 한 번만 받았으면 더 받는다
        assertTrue(rules.needsMoreDaily(listOf(first), d(10), d(14)))
        // 10/14 가 다르면 그날만 빠지고 한 번 더 받아 세 번 중 두 번 같은 값
        val other = first + (d(14) to 1342.4)
        assertEquals(null, rules.agreeDaily(listOf(first, other), d(14), d(14))[d(14)])
        assertTrue(rules.needsMoreDaily(listOf(first, other), d(10), d(14)))
        assertEquals(1342.3, rules.agreeDaily(listOf(first, other, first), d(10), d(14))[d(14)]!!, 1e-9)
        assertFalse(rules.needsMoreDaily(listOf(first, other, first), d(10), d(14)))
        assertEquals("한투", rules.sourceLabel("KIS"))
        assertEquals("수출입은행", rules.sourceLabel("EXIM"))
    }
}
