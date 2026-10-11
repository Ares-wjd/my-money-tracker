package com.mymoneytracker.core

import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.HoldingSort
import com.mymoneytracker.core.portfolio.HoldingSorter
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class HoldingSortTest {
    private val day = LocalDate.of(2026, 10, 1)
    private val account = InvestmentAccount(id = "a", name = "국내")
    private val big = Holding(id = "big", accountId = "a", name = "큰 종목", manualPrice = 110.0)
    private val small = Holding(id = "small", accountId = "a", name = "작은 종목", manualPrice = 30.0)
    private val noPrice = Holding(id = "none", accountId = "a", name = "가격 없음", market = Market.KR)
    private val records = listOf(
        Record(id = "1", accountId = "a", type = RecordType.BUY, date = day, holdingId = "big", quantity = 10.0, price = 100.0),
        Record(id = "2", accountId = "a", type = RecordType.BUY, date = day, holdingId = "small", quantity = 10.0, price = 20.0),
        Record(id = "3", accountId = "a", type = RecordType.BUY, date = day, holdingId = "none", quantity = 1.0, price = 5.0),
    )
    private val valuations = PortfolioCalculator.summarize(listOf(account), listOf(noPrice, small, big), records, null).accounts.single().holdings
    private val today = mapOf("big" to -0.01, "small" to 0.02)

    private fun ids(sort: HoldingSort) =
        HoldingSorter.sort(valuations, sort, { it.returnRate }, { today[it.holding.id] }).map { it.holding.id }

    @Test
    fun sortsHighFirstAndMissingLast() {
        assertEquals(listOf("big", "small", "none"), ids(HoldingSort.VALUE))
        // 수익률: small +50%, big +10%
        assertEquals(listOf("small", "big", "none"), ids(HoldingSort.TOTAL_RETURN))
        assertEquals(listOf("small", "big", "none"), ids(HoldingSort.TODAY_RETURN))
    }
}
