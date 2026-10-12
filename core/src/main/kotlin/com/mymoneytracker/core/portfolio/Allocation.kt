package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * 비중 설정: 대상 계좌들의 종목별 목표 비중(%).
 * [targets] 의 키는 [AllocationCalculator.keyOf] (시장:코드, 코드 없으면 id:종목ID), 예수금은 [AllocationCalculator.CASH].
 */
data class AllocationTarget(
    val id: String = "",
    val name: String,
    val accountIds: List<String>,
    /** 종목 키 → 목표 비중 (%, 0~100). */
    val targets: Map<String, Double>,
    /** 예수금을 비중에 넣을지. */
    val includeCash: Boolean = false,
    /** 차이 강조 기준 (%p). */
    val threshold: Double = 3.0,
    val createdAt: Long = 0L,
    /** 비중 계산에서 뺄 종목 키 (대상 계좌에 있어도 계산하지 않는다). */
    val excluded: List<String> = emptyList(),
    /** 계좌에 없는(아직 안 산) 종목을 직접 추가할 때의 이름: 종목 키 → 이름. */
    val names: Map<String, String> = emptyMap(),
    /** 매수 단위: 종목 키 → 금액 단위(원). 없거나 0 이면 1주 단위. */
    val units: Map<String, Double> = emptyMap(),
    /** 직접 입력한 현재가 (계좌에 없는 종목): 종목 키 → 1주 가격 (국내 원, 해외 달러). */
    val prices: Map<String, Double> = emptyMap(),
    /** 지금 매수 안 함: 비중 표에는 보이지만 매수 계산기에서 빼는 종목 키 (예: 비상장). */
    val noBuy: List<String> = emptyList(),
) {
    val targetSum: Double get() = targets.filterKeys { includeCash || it != AllocationCalculator.CASH }.values.sum()
}

/** 비중 표의 한 줄 (종목 하나 또는 예수금). 비중은 0~1. */
data class AllocationRow(
    val key: String,
    val name: String,
    /** 대표 종목 (티커 배지·현재가용). 예수금이면 null. */
    val holding: Holding?,
    val quantity: Double,
    val valueKrw: Double,
    /** 1주 원화 가격 (계산기 주 수용). 없으면 null. */
    val priceKrw: Double?,
    /** 1주 현지 통화 가격. */
    val priceLocal: Double?,
    val currentWeight: Double,
    val targetWeight: Double,
) {
    /** 실제 − 목표 (%p). */
    val diffPoints: Double get() = (currentWeight - targetWeight) * 100
    val isCash: Boolean get() = key == AllocationCalculator.CASH

    /** 해외 종목인지 (계산기에서 달러 금액도 보여줄지). */
    val isForeign: Boolean get() = holding?.currency == Currency.USD ||
        key.startsWith(AllocationCalculator.US_PREFIX) || key.startsWith(AllocationCalculator.MANUAL_US_PREFIX)
}

data class AllocationView(
    val target: AllocationTarget,
    /** 목표가 큰 순, 같으면 평가금이 큰 순. */
    val rows: List<AllocationRow>,
    val totalKrw: Double,
    /** 현재가가 없어 평가금에서 빠진 종목이 있는지. */
    val missingPrice: Boolean,
) {
    /** 강조할 줄: |차이| 가 기준 이상. */
    fun highlighted(row: AllocationRow): Boolean = abs(row.diffPoints) >= target.threshold - 1e-9 && target.threshold > 0
}

/**
 * 계산기 결과 한 줄: 살(+)/팔(−) 원화 금액과 주 수.
 * [shares] 는 1주 단위면 정확한 주 수, 금액 단위면 대략 주 수(소수), 가격을 모르면 null. [fractional] 은 금액 단위인지.
 */
data class TradeSuggestion(val row: AllocationRow, val amountKrw: Double, val shares: Double?, val fractional: Boolean = false)

/** 매수 계산기 결과: 종목별 살 금액과 단위 때문에 남는 돈. */
data class BuyPlan(val trades: List<TradeSuggestion>, val leftoverKrw: Double)

object AllocationCalculator {
    const val CASH = "CASH"
    const val KR_PREFIX = "KR:"
    const val US_PREFIX = "US:"
    /** 종목코드 없이 직접 추가한 종목의 키 앞부분. */
    const val MANUAL_KR_PREFIX = "manual-kr:"
    const val MANUAL_US_PREFIX = "manual-us:"

    /**
     * 종목 키: 같은 종목이 여러 계좌에 있어도 같은 키. 국내 "KR:코드", 해외 "US:티커" (거래소는 구분하지 않는다 —
     * 직접 추가한 종목과 나중에 계좌에 추가한 종목이 거래소 선택과 상관없이 연결되도록). 코드가 없는 펀드·채권은 종목 하나로.
     */
    fun keyOf(holding: Holding): String = when {
        holding.code.isBlank() -> "id:${holding.id}"
        else -> manualKey(holding.currency == Currency.USD, holding.code)
    }

    /** 직접 추가하는 종목의 키 (keyOf 와 같은 규칙). */
    fun manualKey(foreign: Boolean, code: String): String =
        (if (foreign) US_PREFIX else KR_PREFIX) + code.trim().uppercase()

    /** 종목코드 없이 직접 추가하는 종목의 키 (계좌 종목과 자동 연결되지 않는다). */
    fun codelessKey(foreign: Boolean, id: String): String = (if (foreign) MANUAL_US_PREFIX else MANUAL_KR_PREFIX) + id

    /** 종목코드 없이 직접 추가한 종목인지. */
    fun isCodeless(key: String): Boolean = key.startsWith(MANUAL_KR_PREFIX) || key.startsWith(MANUAL_US_PREFIX)

    /** 대상 계좌들의 종목을 합쳐 실제·목표 비중을 계산한다. */
    fun view(target: AllocationTarget, accounts: List<AccountSummary>, usdKrw: Double?): AllocationView {
        val included = accounts.filter { it.account.id in target.accountIds }
        // 제외한 종목은 합계·비중·계산기 모두에서 뺀다
        val byKey = included.flatMap { it.holdings }.groupBy { keyOf(it.holding) }.filterKeys { it !in target.excluded }
        var missingPrice = false

        data class Raw(val key: String, val name: String, val holding: Holding?, val qty: Double, val value: Double, val priceKrw: Double?, val priceLocal: Double?)

        val raws = mutableListOf<Raw>()
        byKey.forEach { (key, valuations) ->
            val qty = valuations.sumOf { it.position.quantity }
            val held = qty > 0
            if (!held && (target.targets[key] ?: 0.0) <= 0.0) return@forEach
            if (held && valuations.any { it.position.quantity > 0 && it.marketValueKrw == null }) missingPrice = true
            val value = valuations.sumOf { it.marketValueKrw ?: 0.0 }
            val sample = valuations.maxByOrNull { it.position.quantity }!!
            val local = sample.price?.price
            val fx = if (sample.holding.currency == Currency.USD) usdKrw else 1.0
            raws += Raw(key, sample.holding.name, sample.holding, qty, value, local?.let { p -> fx?.let { p * it } }, local)
        }
        if (target.includeCash) {
            val cash = included.sumOf { it.cash.toKrw(usdKrw) }
            raws += Raw(CASH, "예수금", null, 0.0, cash, null, null)
        }
        // 목표에는 있는데 대상 계좌에 종목이 없는 키 (종목을 지웠거나 계좌를 뺐을 때)
        target.targets.filter { (k, v) -> v > 0 && k != CASH && k !in target.excluded && raws.none { it.key == k } }.forEach { (k, _) ->
            // 현재가를 직접 넣었으면 주 수 계산에 쓴다 (해외는 달러 → 원)
            val local = target.prices[k]?.takeIf { it > 0 }
            val foreign = k.startsWith(US_PREFIX) || k.startsWith(MANUAL_US_PREFIX)
            val krw = local?.let { p -> if (foreign) usdKrw?.let { p * it } else p }
            val fallback = if (isCodeless(k)) "직접 추가 종목" else k.substringAfter(':')
            raws += Raw(k, target.names[k]?.ifBlank { null } ?: fallback, null, 0.0, 0.0, krw, local)
        }

        val total = raws.sumOf { it.value }
        val rows = raws.map { r ->
            AllocationRow(
                key = r.key,
                name = r.name,
                holding = r.holding,
                quantity = r.qty,
                valueKrw = r.value,
                priceKrw = r.priceKrw,
                priceLocal = r.priceLocal,
                currentWeight = if (total > 0) r.value / total else 0.0,
                targetWeight = (target.targets[r.key] ?: 0.0) / 100,
            )
        }.sortedWith(compareByDescending<AllocationRow> { it.targetWeight }.thenByDescending { it.valueKrw })
        return AllocationView(target, rows, total, missingPrice)
    }

    /** 종목의 매수 단위(원): 금액 단위가 있으면 그 금액, 없으면 1주 가격, 가격을 모르면 null (내림하지 않음). */
    private fun unitOf(target: AllocationTarget, row: AllocationRow): Double? {
        if (row.isCash) return null
        target.units[row.key]?.takeIf { it > 0 }?.let { return it }
        return row.priceKrw?.takeIf { it > 0 }
    }

    private fun suggestion(target: AllocationTarget, row: AllocationRow, amount: Double): TradeSuggestion {
        val fractional = !row.isCash && (target.units[row.key] ?: 0.0) > 0
        val price = row.priceKrw?.takeIf { it > 0 && !row.isCash }
        val shares = price?.let { abs(amount) / it }?.let { if (fractional) it else Math.round(it).toDouble() }
        return TradeSuggestion(row, amount, shares, fractional)
    }

    /**
     * 팔지 않고 [amountKrw] 만 더 사서 목표에 가장 가깝게: 넣은 뒤 합계 × 목표 − 지금 평가금 = 부족분.
     * 부족분 합이 넣을 돈보다 크면 부족분 비율로 나누고, 남으면 목표 비율로 더 나눈다. 목표가 없는 종목은 사지 않는다.
     * 그다음 종목별 매수 단위로 내림하고, 남은 돈으로 목표보다 가장 많이 부족한 종목부터 한 단위씩 더 산다
     * (목표와의 차이 합이 줄어들 때만 — 예수금 목표가 있으면 예수금 차이도 함께).
     */
    fun buyOnly(view: AllocationView, amountKrw: Double): BuyPlan {
        if (amountKrw <= 0) return BuyPlan(emptyList(), 0.0)
        // 목표 합계는 "지금 매수 안 함" 종목까지 포함한 원래 목표 기준 (나머지 종목의 목표를 부풀리지 않는다)
        val targetSum = view.rows.sumOf { it.targetWeight }
        val rows = view.rows.filter { it.targetWeight > 0 && it.key !in view.target.noBuy }
        val buySum = rows.sumOf { it.targetWeight }
        if (rows.isEmpty() || targetSum <= 0 || buySum <= 0) return BuyPlan(emptyList(), amountKrw)
        val newTotal = view.totalKrw + amountKrw
        val deficits = rows.associateWith { max(0.0, it.targetWeight / targetSum * newTotal - it.valueKrw) }
        val deficitSum = deficits.values.sum()
        val ideal = if (deficitSum >= amountKrw && deficitSum > 0) {
            rows.associateWith { deficits.getValue(it) / deficitSum * amountKrw }
        } else {
            // 부족분을 다 채우고 남는 돈은 살 수 있는 종목의 목표 비율로 (빠진 종목 몫을 현금으로 남기지 않는다)
            val left = amountKrw - deficitSum
            rows.associateWith { deficits.getValue(it) + left * it.targetWeight / buySum }
        }
        // 매수 단위로 내림 (단위를 모르면 그대로). 예수금은 "사고 남은 돈"이라 마지막에 남은 돈으로 정한다.
        val stocks = rows.filter { !it.isCash }
        val units = stocks.associateWith { unitOf(view.target, it) }
        val buys = stocks.associateWith { r ->
            val u = units.getValue(r)
            val raw = ideal.getValue(r)
            if (u == null) raw else floor(raw / u + 1e-9) * u
        }.toMutableMap()
        var leftover = amountKrw - buys.values.sum()
        // 남은 돈으로 한 단위 더 사기: 목표와의 차이 합이 줄어들 때만, 가장 부족한 종목부터.
        // 예수금 목표가 있으면 남은 돈은 예수금이 되므로 예수금의 목표 차이도 함께 따진다.
        val cashRow = rows.firstOrNull { it.isCash }
        fun goal(r: AllocationRow) = max(1e-9, r.targetWeight / targetSum * newTotal)
        fun need(r: AllocationRow, bought: Double) = goal(r) - (r.valueKrw + bought)
        var guard = 0
        while (guard++ < 100_000) {
            val cashNeed = cashRow?.let { need(it, leftover) } ?: 0.0
            val pick = stocks
                .filter { r -> units.getValue(r)?.let { it <= leftover + 1e-9 } == true }
                .map { r -> r to need(r, buys.getValue(r)) }
                .filter { (r, d) ->
                    // 예수금 목표가 없으면(0%) 남기는 돈도 목표에서 벗어나므로 살 수 있으면 산다
                    if (cashRow == null) return@filter true
                    val u = units.getValue(r)!!
                    val change = abs(d - u) - abs(d) + abs(cashNeed + u) - abs(cashNeed)
                    // 차이 합이 줄면 산다. 같으면(주식 부족분이 예수금 부족분으로 옮겨갈 뿐이면) 단위 때문에 원래 몫보다
                    // 반 단위 이상 덜 산 종목이고 목표 대비 예수금보다 더 부족할 때만 산다 (비율 배분을 흐트러뜨리지 않게)
                    change < -1e-9 || (
                        abs(change) <= 1e-9 &&
                            ideal.getValue(r) - buys.getValue(r) >= u / 2 - 1e-9 &&
                            d / goal(r) > cashNeed / goal(cashRow)
                        )
                }
                .maxByOrNull { it.second }
                ?: break
            val u = units.getValue(pick.first)!!
            buys[pick.first] = buys.getValue(pick.first) + u
            leftover -= u
        }
        leftover = max(0.0, leftover)
        val trades = stocks.map { suggestion(view.target, it, buys.getValue(it)) }.toMutableList()
        // 예수금 목표가 있으면 남은 돈은 예수금으로 (그 경우 "남는 돈"은 0)
        if (cashRow != null) {
            trades += suggestion(view.target, cashRow, leftover)
            leftover = 0.0
        }
        return BuyPlan(trades.filter { it.amountKrw >= 0.5 }, leftover)
    }

    /** 사고팔아 정확히 맞추기: 종목별 목표 × 합계 − 평가금 (+ 사기, − 팔기), 매수 단위로 0 쪽 내림. */
    fun rebalance(view: AllocationView): List<TradeSuggestion> {
        val targetSum = view.rows.sumOf { it.targetWeight }
        if (targetSum <= 0) return emptyList()
        return view.rows
            .filter { it.key !in view.target.noBuy }
            .map { r ->
                val raw = r.targetWeight / targetSum * view.totalKrw - r.valueKrw
                val u = unitOf(view.target, r)
                val amount = if (u == null) raw else kotlin.math.sign(raw) * floor(abs(raw) / u + 1e-9) * u
                suggestion(view.target, r, amount)
            }
            .filter { abs(it.amountKrw) >= 0.5 }
    }
}
