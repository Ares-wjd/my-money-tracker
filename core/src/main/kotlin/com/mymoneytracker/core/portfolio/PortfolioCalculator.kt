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
    /** 예수금 (혼합 방식, [CashCalculator]): 한투 연결 계좌는 불러온 값, 직접 입력 계좌는 입력값 + 기록으로 계산. */
    val cash: CashBalance,
    /** 가장 최근 예수금 기준점(입력하거나 불러온 기록)의 날짜 (없으면 null). */
    val cashDate: LocalDate? = null,
    val holdings: List<HoldingValuation>,
    val dividendsKrw: Double,
    /** 가격이 없는 종목이 있어 평가금이 실제보다 작게 계산됐는지. */
    val missingPrice: Boolean,
    /** 달러 금액이 있는데 환율이 없는지. */
    val missingFx: Boolean,
    val usdKrw: Double?,
    /** 통화별 예수금 근거 (기준점, 기록으로 더한 금액, 계산값인지). */
    val cashDetails: Map<Currency, CashDetail> = emptyMap(),
    /** 증권사에서 불러오는 계좌인지 (예수금을 계산하지 않음). */
    val brokerLinked: Boolean = false,
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
        val sorted = records.sortedWith(recordOrder)
        val effective = sorted.filter { asOf == null || !it.date.isAfter(asOf) }
        val summaries = accounts.map { account ->
            val related = { r: Record -> r.accountId == account.id || r.toAccountId == account.id }
            summarizeAccount(
                account = account,
                holdings = holdings.filter { it.accountId == account.id },
                records = effective.filter(related),
                allRecords = sorted.filter(related),
                asOf = asOf,
                usdKrw = usdKrw,
                priceOf = priceOf,
            )
        }
        return PortfolioSummary(summaries, usdKrw)
    }

    /** 기록 하나가 이 계좌 투자금을 바꾸는 금액 (입금 +, 출금 −, 이체는 보낸 계좌 −·받은 계좌 +, 그 밖은 null). */
    fun investedDelta(r: Record, accountId: String): Double? = when (r.type) {
        RecordType.DEPOSIT -> if (r.accountId == accountId) r.krwAmount else null
        RecordType.WITHDRAW -> if (r.accountId == accountId) -r.krwAmount else null
        RecordType.TRANSFER -> when {
            r.toAccountId == accountId && r.accountId != accountId -> r.krwAmount
            r.accountId == accountId -> -r.krwAmount
            else -> null
        }
        else -> null
    }

    /** 수익률을 계산할 수 없는 이유 (웹 noRateReason 과 같은 규칙). 투자금이 0원보다 크면 null. */
    enum class NoRateReason { NO_DEPOSIT, BALANCED, NEGATIVE }

    /** [investedKrw] 가 0원 이하일 때 그 이유: 들어온 기록(입금·다른 계좌에서 온 이체)이 없음 / 들어온 돈 = 나간 돈 / 나간 돈이 더 많음. */
    fun noRateReason(accountId: String, investedKrw: Double, records: List<Record>): NoRateReason? = when {
        investedKrw > 0 -> null
        records.none { (investedDelta(it, accountId) ?: 0.0) > 0 } -> NoRateReason.NO_DEPOSIT
        investedKrw < -0.5 -> NoRateReason.NEGATIVE
        else -> NoRateReason.BALANCED
    }

    /** 입금·출금·이체 기록 id → 그 기록까지 반영한 이 계좌의 누적 투자금 (날짜순·입력순). */
    fun cumulativeInvested(accountId: String, records: List<Record>): Map<String, Double> {
        var total = 0.0
        val out = mutableMapOf<String, Double>()
        for (r in records.sortedWith(recordOrder)) {
            val delta = investedDelta(r, accountId) ?: continue
            total += delta
            out[r.id] = total
        }
        return out
    }

    private fun summarizeAccount(
        account: InvestmentAccount,
        holdings: List<Holding>,
        records: List<Record>,
        allRecords: List<Record>,
        asOf: LocalDate?,
        usdKrw: Double?,
        priceOf: (Holding) -> PricePoint?,
    ): AccountSummary {
        var invested = 0.0
        // 예수금 (혼합 방식): 첫 기준점 이전 추정에 그 뒤 기록도 필요해 날짜 제한 없는 기록으로 계산한다.
        val linked = CashCalculator.isBrokerLinked(account.id, allRecords)
        val details = Currency.entries.associateWith { CashCalculator.detail(account.id, it, allRecords, holdings, asOf, linked) }
        val cash = CashBalance(details.getValue(Currency.KRW).amount, details.getValue(Currency.USD).amount)
        val cashDate = details.values.mapNotNull { it.anchorDate }.maxOrNull()

        for (r in records) {
            val incoming = r.type == RecordType.TRANSFER && r.toAccountId == account.id && r.accountId != account.id
            when (r.type) {
                RecordType.DEPOSIT -> invested += r.krwAmount
                RecordType.WITHDRAW -> invested -= r.krwAmount
                RecordType.TRANSFER -> invested += (if (incoming) 1.0 else -1.0) * r.krwAmount
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
            cashDetails = details,
            brokerLinked = linked,
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
                    fees += r.totalCost
                }
                RecordType.SELL -> {
                    val sold = minOf(r.quantity, quantity)
                    realized += sold * (r.price - average) - r.totalCost
                    quantity -= sold
                    if (quantity <= EPSILON) {
                        quantity = 0.0
                        average = 0.0
                    }
                    fees += r.totalCost
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
