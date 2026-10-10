package com.mymoneytracker.core

import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.portfolio.DisplayConversion
import com.mymoneytracker.core.portfolio.DisplayCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayCurrencyTest {
    @Test
    fun krwModeConvertsOnlyForeignHoldings() {
        val usInKrw = DisplayConversion.of(Currency.USD, DisplayCurrency.KRW, 1_400.0)
        assertEquals(Currency.KRW, usInKrw.currency)
        assertEquals(140_000.0, usInKrw.convert(100.0)!!, 1e-9)

        val krInKrw = DisplayConversion.of(Currency.KRW, DisplayCurrency.KRW, 1_400.0)
        assertEquals(Currency.KRW, krInKrw.currency)
        assertEquals(100.0, krInKrw.convert(100.0)!!, 1e-9)
    }

    @Test
    fun foreignModeKeepsHoldingCurrency() {
        val us = DisplayConversion.of(Currency.USD, DisplayCurrency.FOREIGN, 1_400.0)
        assertEquals(Currency.USD, us.currency)
        assertEquals(100.0, us.convert(100.0)!!, 1e-9)
    }

    @Test
    fun missingFxCannotConvert() {
        val us = DisplayConversion.of(Currency.USD, DisplayCurrency.KRW, null)
        assertNull(us.convert(100.0))
        // 외화 보기는 환율이 없어도 표시할 수 있다.
        assertEquals(100.0, DisplayConversion.of(Currency.USD, DisplayCurrency.FOREIGN, null).convert(100.0)!!, 1e-9)
    }
}
