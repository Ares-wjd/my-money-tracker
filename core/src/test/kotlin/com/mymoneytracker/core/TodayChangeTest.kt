package com.mymoneytracker.core

import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceHistory
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.PricePoint
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.portfolio.TodayChangeCalculator
import com.mymoneytracker.core.portfolio.TodayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.TreeMap

// 웹 todayChange.test.ts 와 같은 케이스.
class TodayChangeTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val day = LocalDate.of(2026, 10, 1)
    private val a = InvestmentAccount(id = "a", name = "국내")
    private val b = InvestmentAccount(id = "b", name = "해외")
    private val sam = Holding(id = "s", accountId = "a", name = "삼성전자", code = "005930", market = Market.KR)
    private val fund = Holding(id = "f", accountId = "a", name = "TDF", market = Market.KR, assetType = AssetType.FUND, manualPrice = 1_000.0, manualPriceDate = day)
    private val voo = Holding(id = "v", accountId = "b", name = "VOO", code = "VOO", market = Market.NYSE, assetType = AssetType.ETF)
    private val holdings = listOf(sam, fund, voo)
    private val records = listOf(
        Record(id = "1", accountId = "a", type = RecordType.BUY, date = day, holdingId = "s", quantity = 10.0, price = 60_000.0, createdAt = 1),
        Record(id = "2", accountId = "a", type = RecordType.BUY, date = day, holdingId = "f", quantity = 5.0, price = 1_000.0, createdAt = 2),
        Record(id = "3", accountId = "b", type = RecordType.BUY, date = day, holdingId = "v", quantity = 2.0, price = 450.0, currency = Currency.USD, createdAt = 3),
        Record(id = "4", accountId = "b", type = RecordType.CASH_BALANCE, date = day.plusDays(1), currency = Currency.USD, amount = 100.0, createdAt = 4),
    )
    private val snapshot = MarketSnapshot(
        prices = mapOf(
            MarketKeys.of(sam)!! to PriceHistory(latest = PricePoint(75_000.0, today), prevClose = 70_000.0),
            MarketKeys.of(voo)!! to PriceHistory(latest = PricePoint(520.0, today), prevClose = 500.0),
        ),
        usdKrw = TreeMap(mapOf(today.minusDays(1) to 1_390.0)),
        usdKrwLatest = today to 1_400.0,
    )

    private fun summary(snap: MarketSnapshot = snapshot) = PortfolioCalculator.summarize(
        listOf(a, b), holdings, records, snap.latestUsdKrw()?.second, priceOf = { PriceLookup.current(it, snap) },
    )

    @Test
    fun domesticForeignAndFxEffect() {
        val t = TodayChangeCalculator.calculate(summary(), snapshot, today)!!
        // 국내: 10주 × (75,000 − 70,000) = 50,000
        assertEquals(50_000.0, t.domesticKrw, 1e-6)
        // 해외: 2주 × $20 × 1,400 = 56,000
        assertEquals(56_000.0, t.foreignKrw, 1e-6)
        // 환율: (2주 × $500 + 달러 예수금 $100) × (1,400 − 1,390) = 11,000
        assertEquals(11_000.0, t.fxKrw, 1e-6)
        assertEquals(117_000.0, t.totalKrw, 1e-6)
        // 전일 기준: 700,000 + 2 × 500 × 1,390 + 100 × 1,390 = 2,229,000
        assertEquals(117_000.0 / 2_229_000.0, t.rate!!, 1e-12)
        assertEquals(1_390.0, t.fxFrom!!, 1e-9)
        // 펀드(직접 입력 가격)는 시세 없음
        assertEquals(listOf("f"), t.excluded.map { it.holding.id })
        assertEquals(listOf("s", "v"), t.topGainers().map { it.holding.id })
        assertTrue(t.topLosers().isEmpty())
        assertEquals("삼성전자", t.items.first { it.holding.id == "s" }.label)
    }

    @Test
    fun noFxEffectBeforeTodaysRate() {
        // 오늘 환율 발표 전 (최신 환율이 어제 것) → 환율 효과 0
        val early = snapshot.copy(usdKrwLatest = null)
        val t = TodayChangeCalculator.calculate(summary(early), early, today)!!
        assertEquals(0.0, t.fxKrw, 1e-9)
        assertNull(t.fxFrom)
        assertEquals(2 * 20 * 1_390.0, t.foreignKrw, 1e-6)
    }

    @Test
    fun levelsTilesAndTreemap() {
        assertEquals(0, TodayChangeCalculator.level(0.0005))
        assertEquals(1, TodayChangeCalculator.level(0.005))
        assertEquals(-2, TodayChangeCalculator.level(-0.015))
        assertEquals(3, TodayChangeCalculator.level(0.031))
        assertNull(TodayChangeCalculator.level(null))
        // 16개 넘으면 작은 것부터 기타
        val many = (1..20).map { TodayItem("k$it", sam.copy(id = "h$it"), it * 100.0, 0.0, 0.0) }
        val tiles = TodayChangeCalculator.tiles(many)
        assertEquals(16, tiles.size)
        assertNull(tiles.last().first)
        assertEquals((1..5).sumOf { it * 100.0 }, tiles.last().second, 1e-9)
        // 넓이는 비중대로, 모두 틀 안에
        val weights = listOf(6.0, 6.0, 4.0, 3.0, 2.0, 2.0, 1.0)
        val rects = TodayChangeCalculator.treemap(weights, 6.0, 4.0)
        rects.zip(weights).forEach { (r, w) ->
            assertEquals(w, r.w * r.h, 1e-9)
            assertTrue(r.x >= -1e-9 && r.y >= -1e-9 && r.x + r.w <= 6.0 + 1e-9 && r.y + r.h <= 4.0 + 1e-9)
        }
    }
}
