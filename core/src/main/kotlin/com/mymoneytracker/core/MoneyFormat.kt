package com.mymoneytracker.core

import java.text.NumberFormat
import java.util.Locale

object MoneyFormat {
    private val formatter: NumberFormat = NumberFormat.getNumberInstance(Locale.KOREA)

    /** 1234567 -> "1,234,567원" */
    fun won(amount: Long): String = formatter.format(amount) + "원"

    /** 부호를 붙여 표시: +1,000원 / -1,000원 */
    fun signedWon(amount: Long): String = when {
        amount > 0 -> "+" + won(amount)
        amount < 0 -> "-" + won(-amount)
        else -> won(0)
    }

    /** 입력창 문자열에서 숫자만 추출해 금액으로 변환. 비어 있거나 너무 크면 null. */
    fun parse(input: String): Long? {
        val digits = input.filter { it.isDigit() }
        if (digits.isEmpty() || digits.length > 15) return null
        return digits.toLong()
    }
}
