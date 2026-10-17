package com.mymoneytracker.app.data

import com.google.firebase.firestore.DocumentSnapshot
import java.time.LocalDate
import java.util.TreeMap

/** users/{uid}/marketData 문서 이름과 읽기 도구 (MarketDataRepository · FxSync · PriceHistorySync 공용). */
internal object MarketDocs {
    const val PRICE_PREFIX = "price_"
    const val FX_DOC = "fx_USD"
    const val FX_CHECKED_DOC = "fx_USD_checked"
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
}
