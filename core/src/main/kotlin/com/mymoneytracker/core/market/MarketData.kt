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
)

/** 앱이 받아 둔 시세·환율 전체. */
data class MarketSnapshot(
    val prices: Map<String, PriceHistory> = emptyMap(),
    /** 날짜별 USD 매매기준율. 0 이하 값은 "그날 데이터 없음" 표시. */
    val usdKrw: TreeMap<LocalDate, Double> = TreeMap(),
) {
    fun history(holding: Holding): PriceHistory? = MarketKeys.of(holding)?.let { prices[it] }

    /** 가장 최근 환율. */
    fun latestUsdKrw(): Pair<LocalDate, Double>? =
        usdKrw.descendingMap().entries.firstOrNull { it.value > 0 }?.let { it.key to it.value }

    /** [date] 당일 또는 그 이전의 가장 가까운 환율. */
    fun usdKrwAt(date: LocalDate): Double? =
        usdKrw.headMap(date, true).descendingMap().values.firstOrNull { it > 0 }
}

object MarketKeys {
    /** 자동 시세를 받을 수 있는 종목인지 (펀드·채권은 직접 입력). */
    fun quotable(holding: Holding): Boolean =
        holding.code.isNotBlank() && (holding.assetType == AssetType.STOCK || holding.assetType == AssetType.ETF)

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

    /** 과거 [date] 기준 가격: 그날 이전 종가, 없으면 그날 이전에 직접 입력한 가격, 그것도 없으면 직접 입력 가격. */
    fun at(holding: Holding, snapshot: MarketSnapshot, date: LocalDate): PricePoint? {
        val close = closeOnOrBefore(snapshot.history(holding), date)
        val manual = holding.manualPrice?.let { PricePoint(it, holding.manualPriceDate) }
        val manualBefore = manual?.takeIf { (it.date ?: LocalDate.MIN) <= date }
        return newer(manualBefore, close) ?: manual
    }
}
