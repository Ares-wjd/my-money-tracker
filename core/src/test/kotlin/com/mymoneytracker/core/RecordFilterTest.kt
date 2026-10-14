package com.mymoneytracker.core

import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.RecordFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// 웹 recordFilter.test.ts 와 같은 케이스.
class RecordFilterTest {
    private val day = LocalDate.of(2026, 10, 1)
    private fun rec(id: String, type: RecordType) = Record(id = id, accountId = "a", type = type, date = day)
    private val records = listOf(rec("1", RecordType.BUY), rec("2", RecordType.DEPOSIT), rec("3", RecordType.SELL), rec("4", RecordType.WITHDRAW))

    @Test
    fun defaultShowsAllAndToggles() {
        val present = RecordFilter.present(records)
        assertEquals(listOf(RecordType.DEPOSIT, RecordType.WITHDRAW, RecordType.BUY, RecordType.SELL), present)
        assertEquals("이체", RecordFilter.label(RecordType.TRANSFER))
        // 기본: 전부 보임 (= 전체)
        assertTrue(RecordFilter.allShown(present, emptySet()))
        assertEquals(records, RecordFilter.apply(records, emptySet()))
        // [전체] → 전체 해제 → 아무것도 안 보임
        val none = RecordFilter.toggleAll(present, emptySet())
        assertTrue(RecordFilter.apply(records, none).isEmpty())
        // 매수·매도만 켜기
        val trades = RecordFilter.toggle(RecordType.SELL, RecordFilter.toggle(RecordType.BUY, none))
        assertEquals(listOf("1", "3"), RecordFilter.apply(records, trades).map { it.id })
        assertFalse(RecordFilter.allShown(present, trades))
        // 일부만 켜져 있으면 [전체] → 전체 선택
        assertEquals(emptySet<RecordType>(), RecordFilter.toggleAll(present, trades))
    }
}
