package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import java.time.LocalDate

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

/**
 * 원화 보기의 해외 종목 숫자 (환차익 반영). 매입금액·평균단가는 매수한 날의 환율로 쌓고(이동평균),
 * 평가금은 오늘 환율로 환산한다. 실현손익은 매도한 날, 배당은 받은 날의 환율로 환산한다.
 */
data class KrwHoldingView(
    val averagePriceKrw: Double,
    val costBasisKrw: Double,
    /** 원화 평가금 (가격이 없으면 null). */
    val marketValueKrw: Double?,
    /** 원화 평가손익 = 주가 손익 + 환차익. */
    val unrealizedProfitKrw: Double?,
    /** 주가 손익 = 달러 평가손익 × 오늘 환율. */
    val stockProfitKrw: Double?,
    /** 환차익 = 달러 매입금액 × 오늘 환율 − 원화 매입금액. */
    val fxProfitKrw: Double,
    val realizedProfitKrw: Double,
    val dividendsKrw: Double,
    val feesAndTaxesKrw: Double,
) {
    val returnRate: Double? get() = rate(unrealizedProfitKrw)
    val returnRateWithDividends: Double? get() = rate(unrealizedProfitKrw?.plus(dividendsKrw))

    private fun rate(profit: Double?): Double? = if (profit == null || costBasisKrw <= 0.0) null else profit / costBasisKrw
}

object KrwHoldingCalculator {
    private const val EPSILON = 1e-9

    /**
     * 해외(달러) 종목의 원화 보기 숫자. [fxAt] 은 그날(휴일이면 직전 영업일)의 원/달러 환율.
     * 달러 종목이 아니거나, 오늘 환율 또는 기록 중 하루라도 환율이 없으면 null (화면은 오늘 환율로 단순 환산한다).
     */
    fun view(
        valuation: HoldingValuation,
        records: List<Record>,
        usdKrw: Double?,
        fxAt: (LocalDate) -> Double?,
    ): KrwHoldingView? {
        if (valuation.holding.currency != Currency.USD || usdKrw == null) return null
        var quantity = 0.0
        var averageKrw = 0.0
        var realizedKrw = 0.0
        var dividendsKrw = 0.0
        var feesKrw = 0.0
        for (r in records.filter { it.holdingId == valuation.holding.id }.sortedWith(PortfolioCalculator.recordOrder)) {
            if (r.type != RecordType.BUY && r.type != RecordType.SELL && r.type != RecordType.DIVIDEND) continue
            val fx = fxAt(r.date) ?: return null
            when (r.type) {
                RecordType.BUY -> {
                    val newQuantity = quantity + r.quantity
                    if (newQuantity > 0) averageKrw = (quantity * averageKrw + r.quantity * r.price * fx) / newQuantity
                    quantity = newQuantity
                    feesKrw += r.totalCost * fx
                }
                RecordType.SELL -> {
                    val sold = minOf(r.quantity, quantity)
                    realizedKrw += sold * (r.price * fx - averageKrw) - r.totalCost * fx
                    quantity -= sold
                    if (quantity <= EPSILON) {
                        quantity = 0.0
                        averageKrw = 0.0
                    }
                    feesKrw += r.totalCost * fx
                }
                else -> dividendsKrw += r.amount * fx
            }
        }
        val costKrw = quantity * averageKrw
        val position = valuation.position
        val marketValueKrw = valuation.marketValue?.let { it * usdKrw }
        return KrwHoldingView(
            averagePriceKrw = averageKrw,
            costBasisKrw = costKrw,
            marketValueKrw = marketValueKrw,
            unrealizedProfitKrw = marketValueKrw?.minus(costKrw),
            stockProfitKrw = valuation.unrealizedProfit?.let { it * usdKrw },
            fxProfitKrw = position.costBasis * usdKrw - costKrw,
            realizedProfitKrw = realizedKrw,
            dividendsKrw = dividendsKrw,
            feesAndTaxesKrw = feesKrw,
        )
    }
}
