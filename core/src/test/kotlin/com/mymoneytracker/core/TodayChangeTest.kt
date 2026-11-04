package com.mymoneytracker.core

import com.mymoneytracker.core.market.Candle
import com.mymoneytracker.core.market.MarketGroup
import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketPhase
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
import com.mymoneytracker.core.portfolio.TodayFilter
import com.mymoneytracker.core.portfolio.TodayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.TreeMap

// 웹 todayChange.test.ts 와 같은 케이스. 시장 상태·합계 숫자는 공통 테스트 케이스(testdata/clock, testdata/portfolio)에서도 검사한다.
class TodayChangeTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val day = LocalDate.of(2026, 10, 1)
    /** 수요일 밤: 국내 장 마감, 미국 정규장 (서머타임), 외환시장 열림. */
    private val night = ZonedDateTime.parse("2026-10-14T23:50:00+09:00")
    private val a = InvestmentAccount(id = "a", name = "국내")
    private val b = InvestmentAccount(id = "b", name = "해외")
    private val c = InvestmentAccount(id = "c", name = "연금")
    private val sam = Holding(id = "s", accountId = "a", name = "삼성전자", code = "005930", market = Market.KR)
    private val sam2 = Holding(id = "s2", accountId = "c", name = "삼성전자", code = "005930", market = Market.KR)
    private val fund = Holding(id = "f", accountId = "a", name = "TDF", market = Market.KR, assetType = AssetType.FUND, manualPrice = 1_000.0, manualPriceDate = day)
    private val voo = Holding(id = "v", accountId = "b", name = "VOO", code = "VOO", market = Market.NYSE, assetType = AssetType.ETF)
    private val holdings = listOf(sam, sam2, fund, voo)
    private val records = listOf(
        Record(id = "1", accountId = "a", type = RecordType.BUY, date = day, holdingId = "s", quantity = 10.0, price = 60_000.0, createdAt = 1),
        Record(id = "2", accountId = "a", type = RecordType.BUY, date = day, holdingId = "f", quantity = 5.0, price = 1_000.0, createdAt = 2),
        Record(id = "3", accountId = "b", type = RecordType.BUY, date = day, holdingId = "v", quantity = 2.0, price = 450.0, currency = Currency.USD, createdAt = 3),
        Record(id = "4", accountId = "b", type = RecordType.CASH_BALANCE, date = day.plusDays(1), currency = Currency.USD, amount = 100.0, createdAt = 4),
        Record(id = "5", accountId = "c", type = RecordType.BUY, date = day, holdingId = "s2", quantity = 2.0, price = 60_000.0, createdAt = 5),
    )

    private fun snapshot(usCandleDate: LocalDate = today) = MarketSnapshot(
        prices = mapOf(
            MarketKeys.of(sam)!! to PriceHistory(
                latest = PricePoint(75_000.0, today), prevClose = 70_000.0,
                candle = Candle(today, base = 70_000.0, last = 76_000.0, regular = 75_000.0),
            ),
            MarketKeys.of(voo)!! to PriceHistory(
                latest = PricePoint(520.0, today), prevClose = 500.0,
                candle = Candle(usCandleDate, base = 500.0, last = 520.0),
            ),
        ),
        usdKrw = TreeMap(mapOf(today.minusDays(1) to 1_390.0)),
        usdKrwLatest = today to 1_400.0,
    )

    private fun summary(snap: MarketSnapshot) = PortfolioCalculator.summarize(
        listOf(a, b, c), holdings, records, snap.latestUsdKrw()?.second, priceOf = { PriceLookup.current(it, snap) },
    )

    @Test
    fun marketsWithRegularAndExtendedSplit() {
        val snap = snapshot()
        val t = TodayChangeCalculator.calculate(summary(snap), snap, night)!!
        assertEquals(listOf(MarketGroup.KR, MarketGroup.US, MarketGroup.FX), t.markets.map { it.group })
        val kr = t.market(MarketGroup.KR)!!
        // 국내: 12주(두 계좌 합) × (76,000 − 70,000), 정규 12 × 5,000 · 장외 12 × 1,000
        assertEquals(72_000.0, kr.changeKrw!!, 1e-6)
        assertEquals(60_000.0, kr.regularKrw!!, 1e-6)
        assertEquals(12_000.0, kr.extendedKrw!!, 1e-6)
        assertEquals(MarketPhase.CLOSED, kr.state.phase)
        assertFalse(kr.live)
        // 같은 종목은 계좌가 달라도 한 줄, 직접 입력 가격(펀드)은 일봉 없음
        assertEquals(listOf("삼성전자", "TDF"), kr.items.map { it.label })
        assertNull(kr.items.first { it.holding.id == "f" }.changeKrw)
        val us = t.market(MarketGroup.US)!!
        assertEquals(2 * 20 * 1_400.0, us.changeKrw!!, 1e-6)
        assertTrue(us.live)
        // 환율 효과: (2주 × $500 + $100) × 10원
        assertEquals(11_000.0, t.market(MarketGroup.FX)!!.changeKrw!!, 1e-6)
        assertEquals(72_000.0 + 56_000.0 + 11_000.0, t.totalKrw, 1e-6)
        assertTrue(t.live)
    }

    @Test
    fun filtersAccountsAndKinds() {
        val snap = snapshot()
        val noPension = TodayChangeCalculator.calculate(summary(snap), snap, night, TodayFilter(hiddenAccounts = setOf("c")))!!
        assertEquals(60_000.0, noPension.market(MarketGroup.KR)!!.changeKrw!!, 1e-6)
        // 달러 예수금을 빼면 환율 효과는 해외 종목 몫만
        val noCash = TodayChangeCalculator.calculate(summary(snap), snap, night, TodayFilter(hiddenKinds = setOf(TodayKind.USD_CASH)))!!
        assertEquals(10_000.0, noCash.market(MarketGroup.FX)!!.changeKrw!!, 1e-6)
        // 해외 계좌를 빼면 미국·환율 줄이 없다
        val noB = TodayChangeCalculator.calculate(summary(snap), snap, night, TodayFilter(hiddenAccounts = setOf("b")))!!
        assertEquals(listOf(MarketGroup.KR), noB.markets.map { it.group })
        assertEquals(listOf(TodayKind.KR_STOCK, TodayKind.ETF, TodayKind.OTHER, TodayKind.USD_CASH), TodayChangeCalculator.presentKinds(summary(snap)))
    }

    @Test
    fun staleCandleIsNotLive() {
        // 미국 정규장인데 받은 일봉이 하루 전 것 → 진행 중이지만 "시세 갱신 전" (회색)
        val snap = snapshot(usCandleDate = today.minusDays(1))
        val us = TodayChangeCalculator.calculate(summary(snap), snap, night)!!.market(MarketGroup.US)!!
        assertEquals(MarketPhase.REGULAR, us.state.phase)
        assertFalse(us.live)
        assertTrue(us.stale)
        assertEquals(today.minusDays(1), us.candleDate)
    }

    @Test
    fun dailyRateUsesCandle() {
        val snap = snapshot()
        assertEquals(76_000.0 / 70_000.0 - 1, PriceLookup.dailyChangeRate(sam, snap)!!, 1e-12)
        assertNull(PriceLookup.dailyChangeRate(fund, snap))
        val states = com.mymoneytracker.core.market.MarketClock.states(night, snap.calendar)
        assertFalse(PriceLookup.isLive(sam, snap, states))
        assertTrue(PriceLookup.isLive(voo, snap, states))
    }
}
