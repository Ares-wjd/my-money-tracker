package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import java.time.LocalDate

/**
 * 해외 주식 양도소득 (기본공제 연 250만원) 추정. 해외(달러) 종목의 매도 기록에서 원화 실현손익을 계산한다.
 * 원화 평균단가는 이동평균법(매수일 환율), 매도는 매도일 환율. 실제 신고는 결제일 환율·결제일 귀속이고 매수 수수료도 빼므로
 * 차이가 날 수 있어 계산 한도는 공제보다 10만원 낮게 둔다.
 */
object OverseasTax {
    const val DEDUCTION_KRW = 2_500_000.0
    const val LIMIT_KRW = 2_400_000.0

    /** [realizedKrw] 실현손익(원, 손실은 −), [estimated] 환율이 없는 날을 [fallbackFx] 로 추정했는지. */
    data class Realized(val realizedKrw: Double, val estimated: Boolean)

    private class Running(var quantity: Double = 0.0, var averageKrw: Double = 0.0)

    /**
     * 종목 하나의 기록을 따라가며 원화 이동평균을 계산한다. [onSell] 은 매도마다 (기록, 원화 실현손익).
     * 환율은 [fxAt] (그날 또는 직전 영업일), 없으면 [fallbackFx] (추정). 둘 다 없으면 그 기록은 건너뛴다.
     */
    private fun walk(
        holding: Holding,
        records: List<Record>,
        fxAt: (LocalDate) -> Double?,
        fallbackFx: Double?,
        onEstimate: () -> Unit,
        onSell: (Record, Double) -> Unit = { _, _ -> },
    ): Running {
        val run = Running()
        for (r in records.filter { it.holdingId == holding.id }.sortedWith(PortfolioCalculator.recordOrder)) {
            if (r.type != RecordType.BUY && r.type != RecordType.SELL) continue
            val fx = fxAt(r.date) ?: fallbackFx?.also { onEstimate() } ?: continue
            if (r.type == RecordType.BUY) {
                val q = run.quantity + r.quantity
                if (q > 0) run.averageKrw = (run.quantity * run.averageKrw + r.quantity * r.price * fx) / q
                run.quantity = q
            } else {
                val sold = minOf(r.quantity, run.quantity)
                onSell(r, sold * (r.price * fx - run.averageKrw) - r.totalCost * fx)
                run.quantity -= sold
                if (run.quantity <= 1e-9) {
                    run.quantity = 0.0
                    run.averageKrw = 0.0
                }
            }
        }
        return run
    }

    /** [year] 에 매도한 해외(달러) 종목의 원화 실현손익 합 (모든 계좌). */
    fun realizedInYear(
        holdings: List<Holding>,
        records: List<Record>,
        year: Int,
        fxAt: (LocalDate) -> Double?,
        fallbackFx: Double?,
    ): Realized {
        var total = 0.0
        var estimated = false
        holdings.filter { it.currency == Currency.USD }.forEach { h ->
            walk(h, records, fxAt, fallbackFx, { estimated = true }) { r, gain -> if (r.date.year == year) total += gain }
        }
        return Realized(total, estimated)
    }

    /** 종목 키 → 원화 평균단가 (1주, 대상 계좌들의 같은 종목은 수량 가중 평균). 해외(달러) 종목만. */
    fun averageCostKrwByKey(
        holdings: List<Holding>,
        records: List<Record>,
        fxAt: (LocalDate) -> Double?,
        fallbackFx: Double?,
    ): Map<String, Double> =
        holdings.filter { it.currency == Currency.USD }
            .map { h -> AllocationCalculator.keyOf(h) to walk(h, records, fxAt, fallbackFx, {}) }
            .filter { it.second.quantity > 0 }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, runs) -> runs.sumOf { it.quantity * it.averageKrw } / runs.sumOf { it.quantity } }
}
