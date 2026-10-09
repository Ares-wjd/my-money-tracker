package com.mymoneytracker.core

import com.mymoneytracker.core.model.Currency
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object MoneyFormat {
    private val formatter: NumberFormat = NumberFormat.getNumberInstance(Locale.KOREA)
    private val symbols = DecimalFormatSymbols(Locale.US)

    /** 1234567 -> "1,234,567원" */
    fun won(amount: Long): String = formatter.format(amount) + "원"

    /** 원화 금액(소수 반올림). */
    fun won(amount: Double): String = won(amount.roundToLong())

    /** 부호를 붙여 표시: +1,000원 / -1,000원 */
    fun signedWon(amount: Long): String = when {
        amount > 0 -> "+" + won(amount)
        amount < 0 -> "-" + won(-amount)
        else -> won(0)
    }

    fun signedWon(amount: Double): String = signedWon(amount.roundToLong())

    /** 1234.5 -> "$1,234.50" */
    fun usd(amount: Double): String {
        val text = DecimalFormat("#,##0.00", symbols).format(abs(amount))
        return (if (amount < 0) "-$" else "$") + text
    }

    fun signedUsd(amount: Double): String = if (amount > 0) "+" + usd(amount) else usd(amount)

    fun amount(currency: Currency, value: Double): String = when (currency) {
        Currency.KRW -> won(value)
        Currency.USD -> usd(value)
    }

    fun signedAmount(currency: Currency, value: Double): String = when (currency) {
        Currency.KRW -> signedWon(value)
        Currency.USD -> signedUsd(value)
    }

    /** 0.1234 -> "+12.34%" (null 이면 "-") */
    fun percent(rate: Double?): String {
        if (rate == null || rate.isNaN() || rate.isInfinite()) return "-"
        val text = DecimalFormat("#,##0.00", symbols).format(abs(rate * 100))
        return when {
            rate > 0 -> "+$text%"
            rate < 0 -> "-$text%"
            else -> "$text%"
        }
    }

    /** 수량·단가처럼 소수가 있을 수 있는 숫자. 불필요한 0 은 지운다. 12.5000 -> "12.5" */
    fun decimal(value: Double, maxFractionDigits: Int = 4): String {
        val rounded = BigDecimal(value).setScale(maxFractionDigits, RoundingMode.HALF_UP).stripTrailingZeros()
        val pattern = if (rounded.scale() > 0) "#,##0." + "#".repeat(rounded.scale()) else "#,##0"
        return DecimalFormat(pattern, symbols).format(rounded)
    }

    /** 입력창 문자열에서 숫자만 추출해 금액으로 변환. 비어 있거나 너무 크면 null. */
    fun parse(input: String): Long? {
        val digits = input.filter { it.isDigit() }
        if (digits.isEmpty() || digits.length > 15) return null
        return digits.toLong()
    }

    /** "1,234.56" -> 1234.56. 숫자가 아니면 null. 쉼표는 무시한다. */
    fun parseDecimal(input: String): Double? {
        val cleaned = input.replace(",", "").trim()
        if (cleaned.isEmpty() || cleaned.length > 20) return null
        if (!cleaned.matches(Regex("-?\\d*\\.?\\d*")) || cleaned == "." || cleaned == "-") return null
        return cleaned.toDoubleOrNull()
    }
}
