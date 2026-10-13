package com.mymoneytracker.core.market

import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.PricePoint
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.TreeMap

/** 알고 있는 가격 하나. [close] 는 실제 종가인지 (매매 단가·직접 입력 가격이면 false). */
data class KnownPrice(val price: Double, val close: Boolean)

/**
 * 과거 날짜의 가격 추정 (웹 market.ts 의 PriceEstimator 와 같은 규칙).
 * 알고 있는 가격 = 저장된 종가 + 매수·매도 단가(같은 날이면 종가, 같은 날 여러 번이면 수량 가중평균, 같은 시세 키면 다른 계좌 기록도)
 * + 날짜가 있는 직접 입력 가격. 어떤 날의 가격은
 * ① 그날 알고 있는 가격 ② 앞뒤가 모두 종가이고 간격이 [HOLIDAY_GAP_DAYS]일 이하면(휴일) 전 거래일 종가
 * ③ 그 밖(시세를 알 수 없는 기간)은 앞뒤 가격을 날짜 비율로 잇는다 ④ 첫 가격 앞은 첫 가격, 마지막 가격 뒤는 마지막 가격.
 */
class PriceEstimator(holdings: List<Holding>, records: List<Record>, snapshot: MarketSnapshot) {
    private val series: Map<String, TreeMap<LocalDate, KnownPrice>>

    init {
        val out = mutableMapOf<String, TreeMap<LocalDate, KnownPrice>>()
        for (h in holdings) {
            val key = MarketKeys.of(h) ?: continue
            if (key in out) continue
            out[key] = TreeMap<LocalDate, KnownPrice>().apply {
                snapshot.prices[key]?.closes?.forEach { (d, v) -> if (v > 0) put(d, KnownPrice(v, true)) }
            }
        }
        val byId = holdings.associateBy { it.id }
        records
            .filter { (it.type == RecordType.BUY || it.type == RecordType.SELL) && it.price > 0 && it.quantity > 0 }
            .mapNotNull { r -> r.holdingId?.let(byId::get)?.let { keyOf(it) to r } }
            .groupBy({ (key, r) -> key to r.date }, { it.second })
            .forEach { (keyDate, list) ->
                val m = out.getOrPut(keyDate.first) { TreeMap() }
                if (m[keyDate.second]?.close == true) return@forEach
                m[keyDate.second] = KnownPrice(list.sumOf { it.price * it.quantity } / list.sumOf { it.quantity }, false)
            }
        for (h in holdings) {
            val price = h.manualPrice?.takeIf { it > 0 } ?: continue
            val date = h.manualPriceDate ?: continue
            val m = out.getOrPut(keyOf(h)) { TreeMap() }
            if (m[date]?.close != true) m[date] = KnownPrice(price, false)
        }
        series = out
    }

    /** 가격 계열 키: 자동 시세 종목은 시세 키(계좌가 달라도 같음), 아니면 종목마다. */
    fun keyOf(holding: Holding): String = MarketKeys.of(holding) ?: "id:${holding.id}"

    /** 추정 가격. 날짜는 쓴 앞쪽 알고 있는 가격의 날짜 (첫 가격 앞이면 첫 가격 날짜). 알고 있는 가격이 없으면 null. */
    fun estimate(holding: Holding, date: LocalDate): PricePoint? {
        val m = series[keyOf(holding)] ?: return null
        m[date]?.let { return PricePoint(it.price, date) }
        val prev = m.floorEntry(date)
        val next = m.ceilingEntry(date)
        return when {
            prev == null && next == null -> null
            prev == null -> PricePoint(next.value.price, next.key)
            next == null -> PricePoint(prev.value.price, prev.key)
            prev.value.close && next.value.close && ChronoUnit.DAYS.between(prev.key, next.key) <= HOLIDAY_GAP_DAYS ->
                PricePoint(prev.value.price, prev.key)
            else -> {
                val t = ChronoUnit.DAYS.between(prev.key, date).toDouble() / ChronoUnit.DAYS.between(prev.key, next.key)
                PricePoint(prev.value.price + (next.value.price - prev.value.price) * t, prev.key)
            }
        }
    }

    /** [date] 보다 앞의 마지막 알고 있는 가격의 날짜. */
    fun knownBefore(key: String, date: LocalDate): LocalDate? = series[key]?.lowerKey(date)

    /** [date] 이후(같은 날 포함) 첫 알고 있는 가격. */
    fun knownFrom(key: String, date: LocalDate): Map.Entry<LocalDate, KnownPrice>? = series[key]?.ceilingEntry(date)

    /** [date] 이전(같은 날 포함) 마지막 알고 있는 가격. */
    fun knownUntil(key: String, date: LocalDate): Map.Entry<LocalDate, KnownPrice>? = series[key]?.floorEntry(date)

    /** 과거 [date] 기준 가격: 그날 이전의 직접 입력 가격이 더 최근이면 그것, 아니면 추정 가격, 둘 다 없으면 직접 입력 가격. */
    fun at(holding: Holding, date: LocalDate): PricePoint? {
        val manual = holding.manualPrice?.let { PricePoint(it, holding.manualPriceDate) }
        val manualBefore = manual?.takeIf { (it.date ?: LocalDate.MIN) <= date }
        return PriceLookup.newer(manualBefore, estimate(holding, date)) ?: manual
    }

    companion object {
        /** 앞뒤 종가 간격이 이 날 수 이하면 휴일로 보고 전 거래일 종가를 쓴다. */
        const val HOLIDAY_GAP_DAYS = 14L
    }
}
