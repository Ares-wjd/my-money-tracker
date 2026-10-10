package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.model.Currency

/** 해외 종목 금액을 어떤 통화로 보여줄지. */
enum class DisplayCurrency(val label: String) {
    KRW("원화"),
    FOREIGN("외화"),
}

/**
 * 종목 금액의 표시 통화와 곱할 비율. 원화 보기의 해외 종목은 현재 환율로 단순 환산한다 (수익률은 그대로).
 * 환율이 없으면 [rate] 가 null 이고, 이때 금액은 표시할 수 없다.
 */
data class DisplayConversion(val currency: Currency, val rate: Double?) {
    fun convert(value: Double): Double? = rate?.let { value * it }

    companion object {
        fun of(holdingCurrency: Currency, mode: DisplayCurrency, usdKrw: Double?): DisplayConversion =
            if (mode == DisplayCurrency.KRW && holdingCurrency == Currency.USD) {
                DisplayConversion(Currency.KRW, usdKrw)
            } else {
                DisplayConversion(holdingCurrency, 1.0)
            }
    }
}
