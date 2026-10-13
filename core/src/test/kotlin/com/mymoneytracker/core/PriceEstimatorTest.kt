package com.mymoneytracker.core

import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceEstimator
import com.mymoneytracker.core.market.PriceHistory
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.TreeMap

// 웹 market.test.ts 의 가격 추정 케이스와 같다.
class PriceEstimatorTest {
    private val gold = Holding(id = "g", accountId = "a", name = "금현물", code = "M04020000", market = Market.KR, assetType = AssetType.GOLD)
    private val goldB = gold.copy(id = "g2", accountId = "b")
    private fun d(text: String) = LocalDate.parse(text)
    private fun buy(id: String, h: Holding, date: String, price: Double, qty: Double = 1.0) =
        Record(id = id, accountId = h.accountId, type = RecordType.BUY, date = d(date), holdingId = h.id, quantity = qty, price = price)
    private fun snap(vararg closes: Pair<String, Double>) = MarketSnapshot(
        prices = mapOf(MarketKeys.of(gold)!! to PriceHistory(TreeMap(closes.associate { d(it.first) to it.second }))),
    )

    @Test
    fun interpolatesUnknownPeriodFromTradeAndClose() {
        // 1/1 매수 20만원, 1/3 종가 18만원 → 1/2 는 19만원
        val e = PriceEstimator(listOf(gold), listOf(buy("1", gold, "2026-01-01", 200_000.0)), snap("2026-01-03" to 180_000.0))
        assertEquals(200_000.0, e.at(gold, d("2026-01-01"))!!.price, 1e-6)
        assertEquals(190_000.0, e.at(gold, d("2026-01-02"))!!.price, 1e-6)
        assertEquals(180_000.0, e.at(gold, d("2026-01-03"))!!.price, 1e-6)
        // 첫 가격 앞은 첫 가격, 마지막 가격 뒤는 마지막 가격
        assertEquals(200_000.0, e.at(gold, d("2025-12-20"))!!.price, 1e-6)
        assertEquals(180_000.0, e.at(gold, d("2026-02-01"))!!.price, 1e-6)
    }

    @Test
    fun holidayUsesPreviousCloseButLongGapIsInterpolated() {
        val e = PriceEstimator(listOf(gold), emptyList(), snap("2026-01-09" to 100.0, "2026-01-12" to 110.0, "2026-02-11" to 140.0))
        // 금 1/9 ~ 월 1/12: 휴일 → 전 거래일 종가
        assertEquals(100.0, e.at(gold, d("2026-01-10"))!!.price, 1e-9)
        assertEquals(100.0, e.at(gold, d("2026-01-11"))!!.price, 1e-9)
        // 1/12 ~ 2/11 (30일, 시세를 모르는 기간) → 날짜 비율로: 1/27 은 15/30
        assertEquals(125.0, e.at(gold, d("2026-01-27"))!!.price, 1e-9)
    }

    @Test
    fun closeWinsAndTradesAreWeightedAcrossAccounts() {
        val e = PriceEstimator(
            listOf(gold, goldB),
            listOf(
                buy("1", gold, "2026-01-05", 100.0, 1.0),
                buy("2", goldB, "2026-01-05", 130.0, 2.0), // 다른 계좌의 같은 종목도 쓴다
                buy("3", gold, "2026-01-07", 999.0), // 같은 날 종가가 있으면 종가
            ),
            snap("2026-01-07" to 150.0),
        )
        assertEquals(120.0, e.at(gold, d("2026-01-05"))!!.price, 1e-9)
        assertEquals(150.0, e.at(goldB, d("2026-01-07"))!!.price, 1e-9)
        assertEquals(135.0, e.at(gold, d("2026-01-06"))!!.price, 1e-9)
    }

    @Test
    fun manualPriceAndNoKnownPrice() {
        val fund = Holding(id = "f", accountId = "a", name = "펀드", market = Market.KR, assetType = AssetType.FUND, manualPrice = 1_200.0, manualPriceDate = d("2026-03-01"))
        val e = PriceEstimator(listOf(fund), listOf(buy("1", fund, "2026-01-01", 1_000.0)), MarketSnapshot())
        // 매수 1,000 (1/1) ~ 직접 입력 1,200 (3/1): 59일 중 31일 지난 2/1
        assertEquals(1_000.0 + 200.0 * 31 / 59, e.at(fund, d("2026-02-01"))!!.price, 1e-9)
        assertEquals(1_200.0, e.at(fund, d("2026-04-01"))!!.price, 1e-9)
        // 알고 있는 가격이 없으면 null
        assertNull(PriceEstimator(listOf(gold), emptyList(), MarketSnapshot()).at(gold, d("2026-01-01")))
    }
}
