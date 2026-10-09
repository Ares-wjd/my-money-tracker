package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.PricePoint
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import java.time.LocalDate

/** 통화별 금액. */
data class CashBalance(val krw: Double = 0.0, val usd: Double = 0.0) {
    operator fun get(currency: Currency): Double = when (currency) {
        Currency.KRW -> krw
        Currency.USD -> usd
    }

    fun toKrw(usdKrw: Double?): Double = krw + usd * (usdKrw ?: 0.0)
}

/** 종목의 보유 현황 (종목 통화 기준). 평균단가는 이동평균법, 수수료·세금은 평균단가에 넣지 않는다. */
data class Position(
    val holding: Holding,
    val quantity: Double,
    val averagePrice: Double,
    val realizedProfit: Double,
    val dividends: Double,
    val feesAndTaxes: Double,
) {
    val costBasis: Double get() = quantity * averagePrice
}

data class HoldingValuation(
    val position: Position,
    val price: PricePoint?,
    /** 평가금 (종목 통화). 가격이 없으면 null. */
    val marketValue: Double?,
    /** 원화 평가금. 가격이나 (달러 종목의) 환율이 없으면 null. */
    val marketValueKrw: Double?,
) {
    val holding: Holding get() = position.holding
    val unrealizedProfit: Double? get() = marketValue?.minus(position.costBasis)

    /** 평가손익률 (배당 미포함). */
    val returnRate: Double? get() = rate(unrealizedProfit)

    /** 평가손익률 (배당 포함). */
    val returnRateWithDividends: Double? get() = rate(unrealizedProfit?.plus(position.dividends))

    private fun rate(profit: Double?): Double? =
        if (profit == null || position.costBasis <= 0.0) null else profit / position.costBasis
}

data class AccountSummary(
    val account: InvestmentAccount,
    /** 투자금 = 입금 − 출금 ± 이체 (원화). */
    val investedKrw: Double,
    /** 예수금: 통화별로 가장 최근의 예수금 기록 (직접 입력 또는 한투에서 불러온 값). 기록이 없으면 0. */
    val cash: CashBalance,
    /** 가장 최근 예수금 기록의 날짜 (없으면 null). */
    val cashDate: LocalDate? = null,
    val holdings: List<HoldingValuation>,
    val dividendsKrw: Double,
    /** 가격이 없는 종목이 있어 평가금이 실제보다 작게 계산됐는지. */
    val missingPrice: Boolean,
    /** 달러 금액이 있는데 환율이 없는지. */
    val missingFx: Boolean,
    val usdKrw: Double?,
) {
    /** 계좌 평가금 (원화) = 종목 평가금 + 예수금. */
    val valueKrw: Double get() = holdings.sumOf { it.marketValueKrw ?: 0.0 } + cash.toKrw(usdKrw)

    /** 수익금 (배당 포함) = 평가금 − 투자금. 예탁금 이용료 등도 예수금을 통해 여기에 반영된다. */
    val profitKrw: Double get() = valueKrw - investedKrw

    /** 수익금 (배당 미포함). */
    val profitExDividendsKrw: Double get() = profitKrw - dividendsKrw

    val returnRate: Double? get() = if (investedKrw > 0) profitKrw / investedKrw else null
    val returnRateExDividends: Double? get() = if (investedKrw > 0) profitExDividendsKrw / investedKrw else null
}

data class PortfolioSummary(
    val accounts: List<AccountSummary>,
    val usdKrw: Double?,
) {
    val investedKrw: Double get() = accounts.sumOf { it.investedKrw }
    val valueKrw: Double get() = accounts.sumOf { it.valueKrw }
    val dividendsKrw: Double get() = accounts.sumOf { it.dividendsKrw }
    val profitKrw: Double get() = valueKrw - investedKrw
    val profitExDividendsKrw: Double get() = profitKrw - dividendsKrw
    val returnRate: Double? get() = if (investedKrw > 0) profitKrw / investedKrw else null
    val returnRateExDividends: Double? get() = if (investedKrw > 0) profitExDividendsKrw / investedKrw else null
    val missingPrice: Boolean get() = accounts.any { it.missingPrice }
    val missingFx: Boolean get() = accounts.any { it.missingFx }
}

object PortfolioCalculator {

    /** 기록을 날짜순(같은 날이면 입력순)으로 정렬. */
    val recordOrder: Comparator<Record> = compareBy<Record>({ it.date }, { it.createdAt }, { it.id })

    /**
     * @param priceOf 종목의 현재가 (없으면 null)
     * @param usdKrw 원/달러 환율 (없으면 달러 금액은 원화 합계에서 빠진다)
     * @param asOf 이 날짜까지의 기록만 반영 (null 이면 전부)
     */
    fun summarize(
        accounts: List<InvestmentAccount>,
        holdings: List<Holding>,
        records: List<Record>,
        usdKrw: Double?,
        priceOf: (Holding) -> PricePoint? = { h -> h.manualPrice?.let { PricePoint(it, h.manualPriceDate) } },
        asOf: LocalDate? = null,
    ): PortfolioSummary {
        val effective = records
            .filter { asOf == null || !it.date.isAfter(asOf) }
            .sortedWith(recordOrder)
        val summaries = accounts.map { account ->
            summarizeAccount(
                account = account,
                holdings = holdings.filter { it.accountId == account.id },
                records = effective.filter { it.accountId == account.id || it.toAccountId == account.id },
                usdKrw = usdKrw,
                priceOf = priceOf,
            )
        }
        return PortfolioSummary(summaries, usdKrw)
    }

    private fun summarizeAccount(
        account: InvestmentAccount,
        holdings: List<Holding>,
        records: List<Record>,
        usdKrw: Double?,
        priceOf: (Holding) -> PricePoint?,
    ): AccountSummary {
        var invested = 0.0
        var cash = CashBalance()
        var cashDate: LocalDate? = null

        // 예수금은 계산하지 않는다. 통화별로 가장 최근의 예수금 기록(records 는 날짜순)을 쓴다.
        for (r in records) {
            val incoming = r.type == RecordType.TRANSFER && r.toAccountId == account.id && r.accountId != account.id
            when (r.type) {
                RecordType.DEPOSIT -> invested += r.krwAmount
                RecordType.WITHDRAW -> invested -= r.krwAmount
                RecordType.TRANSFER -> invested += (if (incoming) 1.0 else -1.0) * r.krwAmount
                RecordType.CASH_BALANCE -> if (r.accountId == account.id) {
                    cash = when (r.currency) {
                        Currency.KRW -> cash.copy(krw = r.amount)
                        Currency.USD -> cash.copy(usd = r.amount)
                    }
                    cashDate = r.date
                }
                else -> Unit
            }
        }

        val valuations = holdings.map { holding ->
            val position = position(holding, records.filter { it.holdingId == holding.id && it.accountId == account.id })
            val price = priceOf(holding)
            val marketValue = price?.let { position.quantity * it.price }
            val marketValueKrw = when {
                marketValue == null -> null
                holding.currency == Currency.KRW -> marketValue
                usdKrw != null -> marketValue * usdKrw
                else -> null
            }
            HoldingValuation(position, price, marketValue, marketValueKrw)
        }

        val dividendsKrw = valuations.sumOf { v ->
            when (v.holding.currency) {
                Currency.KRW -> v.position.dividends
                Currency.USD -> v.position.dividends * (usdKrw ?: 0.0)
            }
        }
        val hasUsd = cash.usd != 0.0 || valuations.any { it.holding.currency == Currency.USD && it.position.quantity > 0 }

        return AccountSummary(
            account = account,
            investedKrw = invested,
            cash = cash,
            cashDate = cashDate,
            holdings = valuations,
            dividendsKrw = dividendsKrw,
            missingPrice = valuations.any { it.position.quantity > 0 && it.price == null },
            missingFx = hasUsd && usdKrw == null,
            usdKrw = usdKrw,
        )
    }

    /** 한 종목의 매수·매도·배당 기록(날짜순)으로 보유 현황을 계산한다. */
    fun position(holding: Holding, records: List<Record>): Position {
        var quantity = 0.0
        var average = 0.0
        var realized = 0.0
        var dividends = 0.0
        var fees = 0.0
        for (r in records.sortedWith(recordOrder)) {
            when (r.type) {
                RecordType.BUY -> {
                    val newQuantity = quantity + r.quantity
                    if (newQuantity > 0) average = (quantity * average + r.quantity * r.price) / newQuantity
                    quantity = newQuantity
                    fees += r.fee + r.tax
                }
                RecordType.SELL -> {
                    val sold = minOf(r.quantity, quantity)
                    realized += sold * (r.price - average) - r.fee - r.tax
                    quantity -= sold
                    if (quantity <= EPSILON) {
                        quantity = 0.0
                        average = 0.0
                    }
                    fees += r.fee + r.tax
                }
                RecordType.DIVIDEND -> dividends += r.amount
                else -> Unit
            }
        }
        return Position(holding, quantity, average, realized, dividends, fees)
    }

    /** [date] 시점의 보유 수량 (매도 가능 수량 확인용). [excludeRecordId] 는 수정 중인 기록을 빼고 계산할 때 사용. */
    fun quantityAt(holding: Holding, records: List<Record>, date: LocalDate, excludeRecordId: String? = null): Double =
        position(
            holding,
            records.filter { it.holdingId == holding.id && !it.date.isAfter(date) && it.id != excludeRecordId },
        ).quantity

    private const val EPSILON = 1e-9
}
