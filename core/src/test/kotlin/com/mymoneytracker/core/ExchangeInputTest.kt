package com.mymoneytracker.core

import com.mymoneytracker.core.portfolio.ExchangeField
import com.mymoneytracker.core.portfolio.ExchangeInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// 웹 exchangeInput.test.ts 와 같은 케이스.
class ExchangeInputTest {
    @Test
    fun lastTwoFieldsDecideTheAutoField() {
        var edited = emptyList<ExchangeField>()
        assertNull(ExchangeInput.autoField(edited))
        edited = ExchangeInput.touch(edited, ExchangeField.KRW)
        assertNull(ExchangeInput.autoField(edited))
        edited = ExchangeInput.touch(edited, ExchangeField.RATE)
        assertEquals(ExchangeField.USD, ExchangeInput.autoField(edited))
        // 자동 칸(달러)을 고치면 가장 오래전에 입력한 원화가 자동
        edited = ExchangeInput.touch(edited, ExchangeField.USD)
        assertEquals(ExchangeField.KRW, ExchangeInput.autoField(edited))
        // 다시 원화를 고치면 환율이 자동
        edited = ExchangeInput.touch(edited, ExchangeField.KRW)
        assertEquals(ExchangeField.RATE, ExchangeInput.autoField(edited))
    }

    @Test
    fun computesTheThirdValue() {
        assertEquals(1_394.5, ExchangeInput.compute(ExchangeField.RATE, 1_394_500.0, 1_000.0, null)!!, 1e-9)
        // 1,000,000원 ÷ 1,394.5 = 717.10…달러 → 센트 반올림
        assertEquals(717.1, ExchangeInput.compute(ExchangeField.USD, 1_000_000.0, null, 1_394.5)!!, 1e-9)
        // 717.1달러 × 1,394.5 = 999,995.95 → 원 반올림
        assertEquals(999_996.0, ExchangeInput.compute(ExchangeField.KRW, null, 717.1, 1_394.5)!!, 1e-9)
        assertNull(ExchangeInput.compute(ExchangeField.KRW, null, 717.1, null))
        assertNull(ExchangeInput.compute(ExchangeField.RATE, 1_000.0, 0.0, null))
    }
}
