package com.mymoneytracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyFormatTest {
    @Test
    fun formatsWithThousandsSeparator() {
        assertEquals("1,234,567원", MoneyFormat.won(1_234_567))
        assertEquals("+1,000원", MoneyFormat.signedWon(1000))
        assertEquals("-1,000원", MoneyFormat.signedWon(-1000))
    }

    @Test
    fun parsesDigitsOnly() {
        assertEquals(1_234_000L, MoneyFormat.parse("1,234,000"))
        assertNull(MoneyFormat.parse(""))
        assertNull(MoneyFormat.parse("abc"))
    }
}
