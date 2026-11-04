package com.mymoneytracker.app.data

import com.google.firebase.firestore.DocumentSnapshot
import com.mymoneytracker.core.market.Candle
import com.mymoneytracker.core.market.MarketCalendar
import java.time.LocalDate
import java.util.TreeMap

/** users/{uid}/marketData 문서 이름과 읽기 도구 (MarketDataRepository · FxSync · PriceHistorySync · 일봉·개장일 공용). */
internal object MarketDocs {
    const val PRICE_PREFIX = "price_"
    const val FX_DOC = "fx_USD"
    const val FX_CHECKED_DOC = "fx_USD_checked"
    /** 국내 개장일 정보 (krClosed, krFrom, krTo, v2.10). */
    const val CALENDAR_DOC = "calendar"
    /** 자주 받는 현재가·오늘 환율을 Firestore 에 저장하는 간격 (쓰기 횟수 절약). */
    const val PERSIST_INTERVAL_MS = 15 * 60 * 1000L

    /** 문서의 {날짜: 값} 필드. 날짜·숫자가 아닌 항목은 건너뛴다. */
    fun readDateMap(doc: DocumentSnapshot, field: String): TreeMap<LocalDate, Double> {
        val result = TreeMap<LocalDate, Double>()
        (doc.get(field) as? Map<*, *>)?.forEach { (k, v) ->
            val date = (k as? String)?.let(::parseDate) ?: return@forEach
            val value = (v as? Number)?.toDouble() ?: return@forEach
            result[date] = value
        }
        return result
    }

    fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

    /** 시세 문서의 candle(또는 prevCandle) 맵 → 일봉 (날짜·기준가·현재가가 모두 있어야). */
    fun readCandle(doc: DocumentSnapshot, field: String = "candle"): Candle? {
        val m = doc.get(field) as? Map<*, *> ?: return null
        val date = (m["date"] as? String)?.let(::parseDate) ?: return null
        val base = (m["base"] as? Number)?.toDouble() ?: return null
        val last = (m["last"] as? Number)?.toDouble() ?: return null
        return Candle(date, base, last, (m["regular"] as? Number)?.toDouble(), (m["at"] as? Number)?.toLong() ?: 0L)
    }

    /** marketData/calendar 문서 → 국내 개장일 정보. */
    fun readCalendar(doc: DocumentSnapshot): MarketCalendar {
        val closed = (doc.get("krClosed") as? List<*>).orEmpty().mapNotNull { (it as? String)?.let(::parseDate) }.toSet()
        return MarketCalendar(closed, doc.getString("krFrom")?.let(::parseDate), doc.getString("krTo")?.let(::parseDate))
    }
}

/** 일봉 → Firestore 맵. */
internal fun Candle.toMap(): Map<String, Any?> =
    mapOf("date" to date.toString(), "base" to base, "last" to last, "regular" to regular, "at" to at)
