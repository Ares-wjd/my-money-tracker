package com.mymoneytracker.core.market

import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import java.time.LocalDate

/**
 * 미리 받아 저장해 둘 과거 시세의 기간. 그래프는 저장된 값만 쓰므로 (폰·PC 모두), 보유했던 기간의 종가와
 * 해외 종목·달러가 있던 기간의 환율을 미리 받아 둔다.
 */
object HistoryRanges {
    /** 휴장일에도 직전 거래일 종가를 쓸 수 있게 앞쪽에 더 받는 날 수. */
    const val LEAD_DAYS = 7L

    /** 시세 키 하나의 받을 기간. [holding] 은 조회에 쓸 대표 종목 (같은 키면 계좌가 달라도 같은 시세). */
    data class Range(val key: String, val holding: Holding, val from: LocalDate, val to: LocalDate)

    /**
     * 시세 키별 받을 기간: 첫 매수일 − 7일 ~ 다 판 날 (지금 보유 중이면 [today]).
     * 같은 종목이 여러 계좌에 있으면 가장 이른 시작 ~ 가장 늦은 끝. 매매 기록이 없거나 자동 시세가 없는 종목은 뺀다.
     */
    fun prices(holdings: List<Holding>, records: List<Record>, today: LocalDate): List<Range> {
        val byHolding = records.filter { it.type == RecordType.BUY || it.type == RecordType.SELL }.groupBy { it.holdingId }
        val ranges = holdings.mapNotNull { h ->
            val key = MarketKeys.of(h) ?: return@mapNotNull null
            val trades = byHolding[h.id].orEmpty().sortedWith(PortfolioCalculator.recordOrder)
            val first = trades.firstOrNull { it.type == RecordType.BUY }?.date ?: return@mapNotNull null
            var quantity = 0.0
            var soldOut: LocalDate? = null
            for (r in trades) {
                quantity = if (r.type == RecordType.BUY) quantity + r.quantity else maxOf(0.0, quantity - r.quantity)
                soldOut = if (quantity <= 1e-9) r.date else null
            }
            Range(key, h, first.minusDays(LEAD_DAYS), minOf(soldOut ?: today, today))
        }
        return ranges.groupBy { it.key }.map { (key, list) ->
            Range(key, list.first().holding, list.minOf { it.from }, list.maxOf { it.to })
        }
    }

    /**
     * 환율을 받을 기간: 해외(달러) 종목 기록이나 달러 기록(입출금·예수금 등) 중 가장 이른 날 − 7일 ~ [today].
     * 달러가 전혀 없으면 null.
     */
    fun fx(holdings: List<Holding>, records: List<Record>, today: LocalDate): Pair<LocalDate, LocalDate>? {
        val usdHoldings = holdings.filter { it.currency == Currency.USD }.map { it.id }.toSet()
        val first = records.filter { it.holdingId in usdHoldings || it.currency == Currency.USD }.minOfOrNull { it.date } ?: return null
        return first.minusDays(LEAD_DAYS) to today
    }
}
