package com.mymoneytracker.core

import com.mymoneytracker.core.market.HistoryRanges
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HistoryRangesTest {
    private val today = LocalDate.of(2026, 10, 13)
    private val d = { m: Int, day: Int -> LocalDate.of(2026, m, day) }
    private fun trade(id: String, h: String, type: RecordType, date: LocalDate, qty: Double) =
        Record(id = id, accountId = "a", type = type, date = date, holdingId = h, quantity = qty, price = 1.0)

    @Test
    fun holdingPeriodPerMarketKey() {
        val held = Holding(id = "s1", accountId = "a", name = "삼성", code = "005930", market = Market.KR)
        val sameInOther = Holding(id = "s2", accountId = "b", name = "삼성", code = "005930", market = Market.KR)
        val sold = Holding(id = "v", accountId = "a", name = "VOO", code = "VOO", market = Market.AMEX)
        val fund = Holding(id = "f", accountId = "a", name = "펀드", market = Market.KR, assetType = AssetType.FUND)
        val noTrades = Holding(id = "n", accountId = "a", name = "NVDA", code = "NVDA", market = Market.NASDAQ)
        val records = listOf(
            trade("1", "s1", RecordType.BUY, d(3, 10), 5.0),
            trade("2", "s2", RecordType.BUY, d(1, 20), 1.0),
            trade("3", "s2", RecordType.SELL, d(2, 1), 1.0),
            trade("4", "v", RecordType.BUY, d(4, 1), 2.0),
            trade("5", "v", RecordType.SELL, d(5, 1), 1.0),
            trade("6", "v", RecordType.SELL, d(6, 15), 1.0), // 다 판 날
            trade("7", "f", RecordType.BUY, d(1, 2), 1.0),
        )
        val ranges = HistoryRanges.prices(listOf(held, sameInOther, sold, fund, noTrades), records, today).associateBy { it.key }
        // 같은 종목은 계좌가 달라도 합쳐서: 가장 이른 매수 − 7일 ~ 오늘 (지금 보유 중)
        assertEquals(d(1, 13), ranges.getValue("KR_005930").from)
        assertEquals(today, ranges.getValue("KR_005930").to)
        // 다 판 종목은 다 판 날까지
        assertEquals(d(3, 25), ranges.getValue("US_VOO").from)
        assertEquals(d(6, 15), ranges.getValue("US_VOO").to)
        // 펀드(자동 시세 없음)·매매 기록 없는 종목은 빠진다
        assertEquals(setOf("KR_005930", "US_VOO"), ranges.keys)
    }

    @Test
    fun fxRangeFromFirstDollarRecord() {
        val voo = Holding(id = "v", accountId = "a", name = "VOO", code = "VOO", market = Market.AMEX)
        val kr = Holding(id = "k", accountId = "a", name = "삼성", code = "005930", market = Market.KR)
        val krOnly = listOf(trade("1", "k", RecordType.BUY, d(1, 5), 1.0))
        assertNull(HistoryRanges.fx(listOf(voo, kr), krOnly, today))
        val records = krOnly + trade("2", "v", RecordType.BUY, d(5, 4), 1.0) +
            Record(id = "3", accountId = "a", type = RecordType.DEPOSIT, date = d(4, 20), currency = Currency.USD, amount = 100.0)
        assertEquals(d(4, 13) to today, HistoryRanges.fx(listOf(voo, kr), records, today))
    }
}
