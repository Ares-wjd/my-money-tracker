package com.mymoneytracker.core.portfolio

/** 보유 종목 정렬 기준 (모두 높은 순). */
enum class HoldingSort(val label: String) {
    VALUE("평가금"),
    TOTAL_RETURN("총 수익률"),
    TODAY_RETURN("오늘 수익률"),
}

object HoldingSorter {
    /**
     * 보유 종목을 [sort] 기준으로 높은 순 정렬한다. 값이 없는 종목은 맨 뒤, 같으면 평가금이 큰 순.
     * @param totalRate 총 수익률 (화면의 보기에 따라 원화/달러 수익률)
     * @param todayRate 오늘 수익률
     */
    fun sort(
        holdings: List<HoldingValuation>,
        sort: HoldingSort,
        totalRate: (HoldingValuation) -> Double?,
        todayRate: (HoldingValuation) -> Double?,
    ): List<HoldingValuation> {
        val key: (HoldingValuation) -> Double? = when (sort) {
            HoldingSort.VALUE -> { v -> v.marketValueKrw }
            HoldingSort.TOTAL_RETURN -> totalRate
            HoldingSort.TODAY_RETURN -> todayRate
        }
        return holdings.sortedWith(
            compareBy<HoldingValuation> { key(it) == null }
                .thenByDescending { key(it) ?: 0.0 }
                .thenByDescending { it.marketValueKrw ?: 0.0 },
        )
    }
}
