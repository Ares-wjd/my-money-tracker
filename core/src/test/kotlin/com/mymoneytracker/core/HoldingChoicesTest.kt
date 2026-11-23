package com.mymoneytracker.core

import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.HoldingChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 종목 숨기기 (v2.17, 웹 holdingChoices.test.ts 와 같은 케이스). */
class HoldingChoicesTest {
    private val held = Holding(id = "held", accountId = "a", name = "보유")
    private val sold = Holding(id = "sold", accountId = "a", name = "다 판 종목")
    private val hidden = Holding(id = "hidden", accountId = "a", name = "숨긴 종목", hidden = true)
    private val other = Holding(id = "other", accountId = "b", name = "다른 계좌")
    private val holdings = listOf(held, sold, hidden, other)
    private fun d(day: Int) = LocalDate.of(2026, 11, day)
    private val records = listOf(
        Record(id = "1", accountId = "a", type = RecordType.BUY, date = d(1), holdingId = "held", quantity = 10.0, price = 100.0),
        Record(id = "2", accountId = "a", type = RecordType.BUY, date = d(1), holdingId = "sold", quantity = 5.0, price = 100.0),
        Record(id = "3", accountId = "a", type = RecordType.SELL, date = d(2), holdingId = "sold", quantity = 5.0, price = 120.0),
        Record(id = "4", accountId = "b", type = RecordType.BUY, date = d(1), holdingId = "other", quantity = 1.0, price = 100.0),
    )
    private fun ids(type: RecordType, current: String? = null) = HoldingChoices.forRecord(type, "a", holdings, records, current).map { it.id }

    @Test
    fun buyAndDividendSkipHidden() {
        assertEquals(listOf("held", "sold"), ids(RecordType.BUY))
        assertEquals(listOf("held", "sold"), ids(RecordType.DIVIDEND))
    }

    @Test
    fun sellOnlyHeldNow() {
        assertEquals(listOf("held"), ids(RecordType.SELL))
    }

    @Test
    fun editingKeepsCurrentHolding() {
        assertEquals(listOf("held", "sold", "hidden"), ids(RecordType.BUY, "hidden"))
        assertEquals(listOf("held", "sold"), ids(RecordType.SELL, "sold"))
    }

    @Test
    fun hideOnlyWhenEmpty() {
        assertFalse(HoldingChoices.canHide(held, records))
        assertTrue(HoldingChoices.canHide(sold, records))
        assertEquals(1, HoldingChoices.hiddenCount("a", holdings))
        assertEquals(0, HoldingChoices.hiddenCount("b", holdings))
    }
}
