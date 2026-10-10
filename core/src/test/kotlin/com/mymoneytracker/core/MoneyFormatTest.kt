package com.mymoneytracker.core

import com.mymoneytracker.core.model.Currency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyFormatTest {
    @Test
    fun formatsWithThousandsSeparator() {
        assertEquals("1,234,567원", MoneyFormat.won(1_234_567))
        assertEquals("+1,000원", MoneyFormat.signedWon(1000))
        assertEquals("-1,000원", MoneyFormat.signedWon(-1000))
        assertEquals("1,235원", MoneyFormat.won(1234.6))
    }

    @Test
    fun formatsUsdAndPercent() {
        assertEquals("$1,234.50", MoneyFormat.usd(1234.5))
        assertEquals("-$3.00", MoneyFormat.usd(-3.0))
        assertEquals("+$1.25", MoneyFormat.signedAmount(Currency.USD, 1.25))
        assertEquals("+12.35%", MoneyFormat.percent(0.12345))
        assertEquals("-5.00%", MoneyFormat.percent(-0.05))
        assertEquals("-", MoneyFormat.percent(null))
    }

    @Test
    fun formatsDecimals() {
        assertEquals("12.5", MoneyFormat.decimal(12.5))
        assertEquals("1,000", MoneyFormat.decimal(1000.0))
        assertEquals("0.1235", MoneyFormat.decimal(0.123456))
    }

    @Test
    fun parsesInput() {
        assertEquals(1_234_000L, MoneyFormat.parse("1,234,000"))
        assertNull(MoneyFormat.parse(""))
        assertEquals(1234.56, MoneyFormat.parseDecimal("1,234.56")!!, 1e-9)
        assertEquals(-3.0, MoneyFormat.parseDecimal("-3")!!, 1e-9)
        assertNull(MoneyFormat.parseDecimal("abc"))
        assertNull(MoneyFormat.parseDecimal("."))
        assertNull(MoneyFormat.parseDecimal("1.2.3"))
    }

    @Test
    fun previewsInManwon() {
        assertEquals("600만원", MoneyFormat.manwon(6_000_000.0))
        assertEquals("105.5만원", MoneyFormat.manwon(1_055_000.0))
        assertEquals("200만원", MoneyFormat.manwon(2_000_000.0))
        assertEquals("12,345.6789만원", MoneyFormat.manwon(123_456_789.0))
        assertEquals("1만원", MoneyFormat.manwon(10_000.0))
        assertEquals("5,000원", MoneyFormat.manwon(5_000.0))
        assertEquals("-350만원", MoneyFormat.manwon(-3_500_000.0))
        assertEquals("$12.50", MoneyFormat.amountPreview(Currency.USD, 12.5))
    }
}
