package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import java.time.LocalDate

/**
 * 통화 하나의 예수금과 그 근거. [anchor]·[anchorDate] 는 쓴 기준점(입력하거나 불러온 예수금), [change] 는 기준점 이후(또는 이전) 기록으로
 * 더한 금액, [estimated] 는 기록으로 계산한 값인지 (기준점 그대로면 false).
 */
data class CashDetail(
    val amount: Double,
    val anchor: Double? = null,
    val anchorDate: LocalDate? = null,
    val change: Double = 0.0,
    val estimated: Boolean = false,
)

/**
 * 예수금 (혼합 방식).
 * - 증권사에서 불러오는 계좌 (한투에서 불러온 기록이 있는 계좌): 불러온 예수금 기록만 쓴다. 그날 또는 이전의 마지막 값, 없으면 0.
 * - 직접 입력 계좌: 입력한 예수금(그날 기록이 모두 반영된 잔액)을 기준점으로 쓰고, 그 사이는 기록으로 계산한다.
 *   기준점 이후 = 기준점 + 다음 날부터의 기록, 첫 기준점 이전 = 첫 기준점 − 그 사이 기록 (거꾸로 추정), 기준점이 없으면 기록만으로.
 *   입금·출금, 이체(보내면 −, 받으면 +), 매수 −(금액 + 제비용합), 매도 +(금액 − 제비용합), 배당 +, 환전(원화·달러 양쪽).
 *   초기 보유분 매수(앱을 쓰기 전부터 가진 종목)는 예수금에서 나간 돈이 아니므로 빼지 않는다.
 */
object CashCalculator {

    /** 증권사에서 불러오는 계좌인지 (한투에서 불러온 기록이 있으면). 폰·웹이 같은 규칙을 쓴다. */
    fun isBrokerLinked(accountId: String, records: List<Record>): Boolean =
        records.any { it.accountId == accountId && it.externalId != null }

    /** 기록 하나가 이 계좌의 [currency] 예수금을 바꾸는 금액. [holdingCurrency] 는 종목 id → 통화. */
    fun delta(r: Record, accountId: String, currency: Currency, holdingCurrency: (String) -> Currency?): Double {
        val own = r.accountId == accountId
        return when (r.type) {
            RecordType.DEPOSIT -> if (own && r.currency == currency) r.amount else 0.0
            RecordType.WITHDRAW -> if (own && r.currency == currency) -r.amount else 0.0
            RecordType.TRANSFER -> when {
                r.currency != currency -> 0.0
                own && r.toAccountId != accountId -> -r.amount
                !own && r.toAccountId == accountId -> r.amount
                else -> 0.0
            }
            RecordType.BUY, RecordType.SELL, RecordType.DIVIDEND -> {
                if (!own || r.initial || r.holdingId?.let(holdingCurrency) != currency) return 0.0
                when (r.type) {
                    RecordType.BUY -> -(r.quantity * r.price + r.totalCost)
                    RecordType.SELL -> r.quantity * r.price - r.totalCost
                    else -> r.amount
                }
            }
            // 환전: currency 는 바꾸기 전 통화, krwAmount 원화 금액, amount 달러 금액
            RecordType.EXCHANGE -> when {
                !own -> 0.0
                r.currency == Currency.KRW -> if (currency == Currency.KRW) -r.krwAmount else r.amount
                else -> if (currency == Currency.USD) -r.amount else r.krwAmount
            }
            RecordType.CASH_ADJUST, RecordType.CASH_BALANCE -> 0.0
        }
    }

    /**
     * [date] 의 [currency] 예수금. [records] 는 이 계좌와 관련된 모든 기록 (날짜 제한 없이 — 첫 기준점 이전 추정에 뒤 기록이 필요).
     * [date] 가 null 이면 모든 기록 기준 (오늘). [excludeAnchorOn] 날의 기준점은 빼고 계산한다 (입력 창의 "계산값" 용).
     */
    fun detail(
        accountId: String,
        currency: Currency,
        records: List<Record>,
        holdings: List<Holding>,
        date: LocalDate?,
        linked: Boolean,
        excludeAnchorOn: LocalDate? = null,
    ): CashDetail {
        val sorted = records.sortedWith(PortfolioCalculator.recordOrder)
        val anchors = sorted.filter {
            it.type == RecordType.CASH_BALANCE && it.accountId == accountId && it.currency == currency && it.date != excludeAnchorOn
        }
        val last = anchors.lastOrNull { date == null || !it.date.isAfter(date) }
        if (linked) return last?.let { CashDetail(it.amount, it.amount, it.date) } ?: CashDetail(0.0)

        val currencyOf = holdings.associate { it.id to it.currency }
        val change = { from: LocalDate?, to: LocalDate? ->
            // (from, to] 사이 기록의 합 (from 이 null 이면 처음부터, to 가 null 이면 끝까지)
            sorted.filter { (from == null || it.date.isAfter(from)) && (to == null || !it.date.isAfter(to)) }
                .sumOf { delta(it, accountId, currency) { id -> currencyOf[id] } }
        }
        if (last != null) {
            val c = change(last.date, date)
            return CashDetail(last.amount + c, last.amount, last.date, c, estimated = abs(c) > 1e-9)
        }
        val first = anchors.firstOrNull()
        if (first != null) {
            val c = -change(date, first.date)
            return CashDetail(first.amount + c, first.amount, first.date, c, estimated = true)
        }
        val c = change(null, date)
        return CashDetail(c, change = c, estimated = abs(c) > 1e-9)
    }

    private fun abs(v: Double) = kotlin.math.abs(v)
}
