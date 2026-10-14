package com.mymoneytracker.core.portfolio

import kotlin.math.roundToLong

/** 환전 입력 칸. */
enum class ExchangeField { KRW, USD, RATE }

/**
 * 환전 입력 (웹 exchangeInput.ts 와 같은 규칙): 원화 금액·달러 금액·환율 중 마지막으로 입력한 두 칸으로 나머지 한 칸을 계산한다.
 */
object ExchangeInput {
    /** [field] 를 입력했을 때의 입력 순서 (가장 최근이 뒤, 칸마다 한 번). */
    fun touch(edited: List<ExchangeField>, field: ExchangeField): List<ExchangeField> = edited.filter { it != field } + field

    /** 자동 계산할 칸: 최근에 입력한 두 칸이 아닌 칸. 두 칸을 입력하기 전이면 null. */
    fun autoField(edited: List<ExchangeField>): ExchangeField? {
        if (edited.size < 2) return null
        val recent = edited.takeLast(2)
        return ExchangeField.entries.first { it !in recent }
    }

    /** 자동 칸의 값: 환율 = 원화 ÷ 달러, 달러 = 원화 ÷ 환율 (센트 반올림), 원화 = 달러 × 환율 (원 반올림). 계산할 수 없으면 null. */
    fun compute(auto: ExchangeField, krw: Double?, usd: Double?, rate: Double?): Double? = when (auto) {
        ExchangeField.RATE -> if (krw != null && krw > 0 && usd != null && usd > 0) krw / usd else null
        ExchangeField.USD -> if (krw != null && krw > 0 && rate != null && rate > 0) (krw / rate * 100).roundToLong() / 100.0 else null
        ExchangeField.KRW -> if (usd != null && usd > 0 && rate != null && rate > 0) (usd * rate).roundToLong().toDouble() else null
    }
}
