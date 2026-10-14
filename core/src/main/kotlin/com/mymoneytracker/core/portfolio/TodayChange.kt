package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 오늘 변동의 종목 하나 (같은 시세 키는 계좌가 달라도 합침). [rate]·[changeKrw] 가 null 이면 시세 없음 (직접 입력 가격·전일 종가 없음).
 * [changeKrw] 는 가격 변동만 (해외는 오늘 환율로 원화, 환율 효과는 따로).
 */
data class TodayItem(
    val key: String,
    val holding: Holding,
    val valueKrw: Double,
    val changeKrw: Double?,
    val rate: Double?,
) {
    /** 타일·목록에 쓰는 이름: 금현물, 종목명, 이름이 없으면 종목코드. */
    val label: String
        get() = when {
            holding.assetType == AssetType.GOLD -> "금현물"
            holding.name.isNotBlank() -> holding.name.trim()
            else -> holding.code.uppercase()
        }
}

/** 오늘 변동 (웹 todayChange.ts 와 같은 규칙). */
data class TodayChange(
    val totalKrw: Double,
    /** 오늘 변동 ÷ 전일 기준 평가금 (대상 종목 + 달러 예수금). 기준이 없으면 null. */
    val rate: Double?,
    val domesticKrw: Double,
    val foreignKrw: Double,
    val fxKrw: Double,
    /** 환율 효과에 쓴 전 영업일·오늘 환율 (오늘 환율 발표 전이면 null). */
    val fxFrom: Double?,
    val fxTo: Double?,
    /** 종목별 (평가금 큰 순). */
    val items: List<TodayItem>,
) {
    val included: List<TodayItem> get() = items.filter { it.rate != null }
    val excluded: List<TodayItem> get() = items.filter { it.rate == null }

    /** 많이 오른 종목 (등락률 큰 순, 상승만). */
    fun topGainers(count: Int = 3): List<TodayItem> = included.filter { it.rate!! > 0 }.sortedByDescending { it.rate }.take(count)

    /** 많이 내린 종목 (등락률 작은 순, 하락만). */
    fun topLosers(count: Int = 3): List<TodayItem> = included.filter { it.rate!! < 0 }.sortedBy { it.rate }.take(count)
}

/** 타일 하나의 자리 (0 ~ 폭, 0 ~ 높이). */
data class TileRect(val x: Double, val y: Double, val w: Double, val h: Double)

object TodayChangeCalculator {
    /** 타일이 이보다 많으면 작은 것부터 "기타"로 묶는다. */
    const val MAX_TILES = 16

    /**
     * 오늘 변동. [today] 는 오늘 날짜 (오늘 환율이 오늘 것인지 판단). 보유 종목이 없으면 null.
     */
    fun calculate(summary: PortfolioSummary, snapshot: MarketSnapshot, today: LocalDate): TodayChange? {
        val fxTo = summary.usdKrw
        // 오늘 발표된 환율일 때만 환율 효과 (전 영업일 환율과 비교)
        val latestFx = snapshot.latestUsdKrw()
        val fxFrom = if (latestFx != null && latestFx.first == today && fxTo != null) snapshot.usdKrwAt(today.minusDays(1)) else null
        val fxMove = if (fxFrom != null && fxTo != null) fxTo - fxFrom else 0.0
        val fxBase = fxFrom ?: fxTo ?: 0.0

        var domestic = 0.0
        var foreign = 0.0
        var fx = 0.0
        var previous = 0.0
        val merged = linkedMapOf<String, TodayItem>()
        for (account in summary.accounts) {
            fx += account.cash.usd * fxMove
            previous += account.cash.usd * fxBase
            for (v in account.holdings) {
                val quantity = v.position.quantity
                if (quantity <= 1e-9) continue
                val h = v.holding
                val key = MarketKeys.of(h) ?: "id:${h.id}"
                val value = v.marketValueKrw ?: 0.0
                val rate = PriceLookup.dailyChangeRate(h, snapshot)
                val price = v.price?.price
                var change: Double? = null
                if (rate != null && price != null) {
                    val prev = price / (1 + rate)
                    if (h.currency == Currency.USD) {
                        val rateNow = fxTo ?: 0.0
                        change = quantity * (price - prev) * rateNow
                        foreign += change
                        fx += quantity * prev * fxMove
                        previous += quantity * prev * fxBase
                    } else {
                        change = quantity * (price - prev)
                        domestic += change
                        previous += quantity * prev
                    }
                }
                val old = merged[key]
                merged[key] = if (old == null) {
                    TodayItem(key, h, value, change, if (change != null) rate else null)
                } else {
                    old.copy(valueKrw = old.valueKrw + value, changeKrw = sumOrNull(old.changeKrw, change))
                }
            }
        }
        if (merged.isEmpty()) return null
        val total = domestic + foreign + fx
        return TodayChange(
            totalKrw = total,
            rate = if (previous > 0) total / previous else null,
            domesticKrw = domestic,
            foreignKrw = foreign,
            fxKrw = fx,
            fxFrom = fxFrom,
            fxTo = if (fxFrom != null) fxTo else null,
            items = merged.values.sortedByDescending { it.valueKrw },
        )
    }

    private fun sumOrNull(a: Double?, b: Double?): Double? = if (a == null || b == null) a ?: b else a + b

    /** 등락 색 단계: 0 = 보합(0.1% 미만), ±1 = 1% 미만, ±2 = 2% 미만, ±3 = 2% 이상. 시세 없음은 null. */
    fun level(rate: Double?): Int? {
        rate ?: return null
        val a = abs(rate)
        val step = when {
            a < 0.001 -> 0
            a < 0.01 -> 1
            a < 0.02 -> 2
            else -> 3
        }
        return if (rate < 0) -step else step
    }

    /** 타일로 그릴 항목: 평가금이 있는 것만, [MAX_TILES] 를 넘으면 작은 것들을 하나로 (null = 기타). */
    fun tiles(items: List<TodayItem>): List<Pair<TodayItem?, Double>> {
        val sized = items.filter { it.valueKrw > 0 }.sortedByDescending { it.valueKrw }
        if (sized.size <= MAX_TILES) return sized.map { it to it.valueKrw }
        val kept = sized.take(MAX_TILES - 1)
        return kept.map { it to it.valueKrw } + (null to sized.drop(MAX_TILES - 1).sumOf { it.valueKrw })
    }

    /**
     * Squarified treemap: [weights] (큰 순으로 넘기면 보기 좋음) 를 [width] × [height] 안에 넓이 비례로 나눈다. 같은 순서로 돌려준다.
     */
    fun treemap(weights: List<Double>, width: Double, height: Double): List<TileRect> {
        val total = weights.sum()
        if (weights.isEmpty() || total <= 0 || width <= 0 || height <= 0) return weights.map { TileRect(0.0, 0.0, 0.0, 0.0) }
        val areas = weights.map { it / total * width * height }
        val out = MutableList(weights.size) { TileRect(0.0, 0.0, 0.0, 0.0) }
        var x = 0.0
        var y = 0.0
        var w = width
        var h = height
        var i = 0
        fun worst(row: List<Double>, side: Double): Double {
            val sum = row.sum()
            val mx = row.max()
            val mn = row.min()
            return max(side * side * mx / (sum * sum), sum * sum / (side * side * mn))
        }
        while (i < areas.size) {
            val side = min(w, h)
            val row = mutableListOf(areas[i])
            var j = i + 1
            while (j < areas.size && worst(row + areas[j], side) <= worst(row, side)) {
                row += areas[j]
                j++
            }
            val sum = row.sum()
            if (w >= h) {
                // 왼쪽에 세로 줄
                val stripW = if (h > 0) sum / h else 0.0
                var yy = y
                row.forEachIndexed { k, a ->
                    val hh = if (stripW > 0) a / stripW else 0.0
                    out[i + k] = TileRect(x, yy, stripW, hh)
                    yy += hh
                }
                x += stripW
                w -= stripW
            } else {
                // 위쪽에 가로 줄
                val stripH = if (w > 0) sum / w else 0.0
                var xx = x
                row.forEachIndexed { k, a ->
                    val ww = if (stripH > 0) a / stripH else 0.0
                    out[i + k] = TileRect(xx, y, ww, stripH)
                    xx += ww
                }
                y += stripH
                h -= stripH
            }
            i = j
        }
        return out
    }
}
