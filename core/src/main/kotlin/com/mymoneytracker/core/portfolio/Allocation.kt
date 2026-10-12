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
    val isForeign: Boolean get() = holding?.currency == Currency.USD || key.startsWith(AllocationCalculator.US_PREFIX)
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

/** 계산기 결과 한 줄: 살(+)/팔(−) 원화 금액과 대략 주 수. */
data class TradeSuggestion(val row: AllocationRow, val amountKrw: Double) {
    /** 대략 주 수 (사는 쪽은 내림). 가격이 없거나 예수금이면 null. */
    val shares: Double? get() = row.priceKrw?.takeIf { it > 0 && !row.isCash }?.let { floor(abs(amountKrw) / it) }
}

object AllocationCalculator {
    const val CASH = "CASH"
    const val KR_PREFIX = "KR:"
    const val US_PREFIX = "US:"

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
            raws += Raw(k, target.names[k]?.ifBlank { null } ?: k.substringAfter(':'), null, 0.0, 0.0, null, null)
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

    /**
     * 팔지 않고 [amountKrw] 만 더 사서 목표에 가장 가깝게: 넣은 뒤 합계 × 목표 − 지금 평가금 = 부족분.
     * 부족분 합이 넣을 돈보다 크면 부족분 비율로 나누고, 남으면 목표 비율로 더 나눈다. 목표가 없는 종목은 사지 않는다.
     */
    fun buyOnly(view: AllocationView, amountKrw: Double): List<TradeSuggestion> {
        if (amountKrw <= 0) return emptyList()
        val rows = view.rows.filter { it.targetWeight > 0 }
        val targetSum = rows.sumOf { it.targetWeight }
        if (rows.isEmpty() || targetSum <= 0) return emptyList()
        val newTotal = view.totalKrw + amountKrw
        val deficits = rows.associateWith { max(0.0, it.targetWeight / targetSum * newTotal - it.valueKrw) }
        val deficitSum = deficits.values.sum()
        val buys = if (deficitSum >= amountKrw && deficitSum > 0) {
            rows.associateWith { deficits.getValue(it) / deficitSum * amountKrw }
        } else {
            val left = amountKrw - deficitSum
            rows.associateWith { deficits.getValue(it) + left * it.targetWeight / targetSum }
        }
        return rows.map { TradeSuggestion(it, buys.getValue(it)) }.filter { it.amountKrw >= 0.5 }
    }

    /** 사고팔아 정확히 맞추기: 종목별 목표 × 합계 − 평가금 (+ 사기, − 팔기). */
    fun rebalance(view: AllocationView): List<TradeSuggestion> {
        val targetSum = view.rows.sumOf { it.targetWeight }
        if (targetSum <= 0) return emptyList()
        return view.rows
            .map { TradeSuggestion(it, it.targetWeight / targetSum * view.totalKrw - it.valueKrw) }
            .filter { abs(it.amountKrw) >= 0.5 }
    }
}
