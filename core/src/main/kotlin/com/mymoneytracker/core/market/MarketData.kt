package com.mymoneytracker.core.market

import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.PricePoint
import java.time.LocalDate
import java.util.TreeMap

/** 한 종목의 시세 기록 (종목 통화). */
data class PriceHistory(
    val closes: TreeMap<LocalDate, Double> = TreeMap(),
    /** 가장 최근에 받은 현재가와 그 날짜. */
    val latest: PricePoint? = null,
    /** [latest] 의 전일 종가 (오늘 수익률 계산용). */
    val prevClose: Double? = null,
)

/** 앱이 받아 둔 시세·환율 전체. */
data class MarketSnapshot(
    val prices: Map<String, PriceHistory> = emptyMap(),
    /** 날짜별 USD 매매기준율 (지난 날짜만). 0 이하 값은 "그날 데이터 없음" 표시. */
    val usdKrw: TreeMap<LocalDate, Double> = TreeMap(),
    /** 오늘 받은 환율 (오늘 평가금용, 과거 환율 목록에는 넣지 않는다). */
    val usdKrwLatest: Pair<LocalDate, Double>? = null,
    /** [usdKrwLatest] 의 출처 ([FxRules.SOURCE_KIS] / [FxRules.SOURCE_EXIM], 모르면 null). */
    val usdKrwLatestSource: String? = null,
) {
    fun history(holding: Holding): PriceHistory? = MarketKeys.of(holding)?.let { prices[it] }

    /** 가장 최근 환율 (오늘 받은 환율이 과거 목록보다 최근이면 그것). */
    fun latestUsdKrw(): Pair<LocalDate, Double>? {
        val past = usdKrw.descendingMap().entries.firstOrNull { it.value > 0 }?.let { it.key to it.value }
        val today = usdKrwLatest?.takeIf { it.second > 0 }
        return if (today != null && (past == null || !today.first.isBefore(past.first))) today else past
    }

    /** 가장 최근 환율의 출처 표시 글 (오늘 받은 환율일 때만, 과거 목록 값이면 null). */
    fun latestUsdKrwSourceLabel(): String? =
        if (usdKrwLatest != null && latestUsdKrw() == usdKrwLatest) FxRules.sourceLabel(usdKrwLatestSource) else null

    /** [date] 당일 또는 그 이전의 가장 가까운 환율. */
    fun usdKrwAt(date: LocalDate): Double? =
        usdKrw.headMap(date, true).descendingMap().values.firstOrNull { it > 0 }

    /**
     * 매매일 환율 (원화 보기의 환차익 계산용): 그날 또는 직전 영업일(최대 7일 전)까지 받아 둔 환율.
     * 그 사이에 아직 받지 않은 날이 있으면 null (멀리 떨어진 옛 환율을 쓰지 않도록). 오늘 이후는 최신 환율.
     */
    fun tradeUsdKrw(date: LocalDate, today: LocalDate): Double? {
        if (!date.isBefore(today)) return latestUsdKrw()?.second
        var day = date
        for (step in 0..7) {
            val rate = usdKrw[day] ?: return null
            if (rate > 0) return rate
            day = day.minusDays(1)
        }
        return null
    }
}

/** 환율 받는 규칙: 최근 1주일은 계속 다시 받아 바꾸고, 그보다 오래된 과거는 두 번 받아 같은 값만 고정한다. */
object FxRules {
    /** 다시 받아 바꾸는 최근 기간 (어제 ~ 7일 전). */
    const val RECENT_DAYS = 7L

    /** 최근 1주일(어제 ~ 7일 전)인지. */
    fun isRecent(date: LocalDate, today: LocalDate): Boolean = date.isBefore(today) && !date.isBefore(today.minusDays(RECENT_DAYS))

    /** 받은 값들 중 두 번 이상 같은 값 (없으면 null). "없음"은 -1. */
    fun agreed(values: List<Double>): Double? = values.firstOrNull { v -> values.count { it == v } >= 2 }

    /** 더 받아야 하는지: 두 번 같은 값이 없고 아직 세 번을 받지 않았으면. */
    fun needsMore(values: List<Double>): Boolean = agreed(values) == null && values.size < 3

    /**
     * 한투 일별 종가를 여러 번 받은 것([fetches])으로 [from] ~ [to] 날짜별 고정 값을 만든다 (v2.8):
     * 두 번 이상 같은 값, 모두 없으면 "없음"(-1, 주말 등). 받은 값이 서로 달라 정할 수 없는 날짜는 빠진다.
     */
    fun agreeDaily(fetches: List<Map<LocalDate, Double>>, from: LocalDate, to: LocalDate): Map<LocalDate, Double> {
        val result = linkedMapOf<LocalDate, Double>()
        var day = from
        while (!day.isAfter(to)) {
            agreed(fetches.map { it[day] ?: -1.0 })?.let { result[day] = it }
            day = day.plusDays(1)
        }
        return result
    }

    /** 한 페이지를 더 받아야 하는지: 정할 수 없는 날짜가 있고 아직 세 번을 받지 않았으면. */
    fun needsMoreDaily(fetches: List<Map<LocalDate, Double>>, from: LocalDate, to: LocalDate): Boolean {
        if (fetches.size >= 3) return false
        if (fetches.size < 2) return true
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.count()
        return agreeDaily(fetches, from, to).size < days
    }

    /** 출처 표시 글: "KIS" → 한투, "EXIM" → 수출입은행. */
    fun sourceLabel(source: String?): String? = when (source) {
        SOURCE_KIS -> "한투"
        SOURCE_EXIM -> "수출입은행"
        else -> null
    }

    const val SOURCE_KIS = "KIS"
    const val SOURCE_EXIM = "EXIM"
}

object MarketKeys {
    /** 자동 시세를 받을 수 있는 종목인지 (펀드·채권은 직접 입력, 금현물은 국내 시세 조회). */
    fun quotable(holding: Holding): Boolean =
        holding.code.isNotBlank() && holding.assetType in QUOTABLE_TYPES

    private val QUOTABLE_TYPES = setOf(AssetType.STOCK, AssetType.ETF, AssetType.GOLD)

    /** 시세 저장 키. 같은 종목이면 계좌가 달라도 같은 시세를 쓴다. */
    fun of(holding: Holding): String? = if (quotable(holding)) key(holding.market, holding.code) else null

    fun key(market: Market, code: String): String {
        val group = if (market == Market.KR) "KR" else "US"
        return group + "_" + code.uppercase().replace("/", "_")
    }
}

object PriceLookup {

    /** [date] 당일 또는 그 이전의 가장 가까운 종가. */
    fun closeOnOrBefore(history: PriceHistory?, date: LocalDate): PricePoint? =
        history?.closes?.floorEntry(date)?.let { PricePoint(it.value, it.key) }

    /** 직접 입력한 가격과 자동 시세 중 날짜가 더 최근인 것 (같은 날이면 직접 입력 우선). */
    fun newer(manual: PricePoint?, auto: PricePoint?): PricePoint? = when {
        manual == null -> auto
        auto == null -> manual
        (manual.date ?: LocalDate.MIN) >= (auto.date ?: LocalDate.MIN) -> manual
        else -> auto
    }

    /** 자동 시세끼리는 같은 날이면 현재가(실시간에 가까움)를 우선한다. */
    private fun newerAuto(latest: PricePoint?, close: PricePoint?): PricePoint? = when {
        latest == null -> close
        close == null -> latest
        (latest.date ?: LocalDate.MIN) >= (close.date ?: LocalDate.MIN) -> latest
        else -> close
    }

    /** 오늘 기준 현재가: 최신 현재가 또는 마지막 종가 중 최근 것과, 직접 입력 가격 중 최근 것. */
    fun current(holding: Holding, snapshot: MarketSnapshot): PricePoint? {
        val history = snapshot.history(holding)
        val lastClose = history?.closes?.lastEntry()?.let { PricePoint(it.value, it.key) }
        val auto = newerAuto(history?.latest, lastClose)
        return newer(holding.manualPrice?.let { PricePoint(it, holding.manualPriceDate) }, auto)
    }

    /**
     * 오늘 수익률 = 현재가 ÷ 전일 종가 − 1. 자동 시세(최신 현재가)를 쓰는 종목만 계산한다
     * (직접 입력 가격을 쓰거나 전일 종가가 없으면 null).
     */
    fun dailyChangeRate(holding: Holding, snapshot: MarketSnapshot): Double? {
        val history = snapshot.history(holding) ?: return null
        val prev = history.prevClose?.takeIf { it > 0 } ?: return null
        val latest = history.latest ?: return null
        if (current(holding, snapshot) != latest) return null
        return latest.price / prev - 1
    }

    /** 과거 [date] 기준 가격: 그날 이전 종가, 없으면 그날 이전에 직접 입력한 가격, 그것도 없으면 직접 입력 가격. */
    fun at(holding: Holding, snapshot: MarketSnapshot, date: LocalDate): PricePoint? {
        val close = closeOnOrBefore(snapshot.history(holding), date)
        val manual = holding.manualPrice?.let { PricePoint(it, holding.manualPriceDate) }
        val manualBefore = manual?.takeIf { (it.date ?: LocalDate.MIN) <= date }
        return newer(manualBefore, close) ?: manual
    }
}
