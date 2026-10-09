package com.mymoneytracker.app.data

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceHistory
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.PricePoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.LocalDate
import java.util.TreeMap

/**
 * 받아 온 시세·환율을 Firestore 에 캐시한다. 과거 종가와 환율은 바뀌지 않으므로 한 번 받으면 다시 받지 않는다.
 *
 * users/{uid}/marketData/price_{KR|US}_{코드}  { closes: {날짜: 종가}, latest, latestDate, historyFrom, historyTo }
 * users/{uid}/marketData/fx_USD                 { rates: {날짜: 매매기준율, 데이터 없는 날은 -1} }
 */
class MarketDataRepository(
    db: FirebaseFirestore,
    uid: String,
    private val kis: KisClient,
    private val exim: EximClient,
) {
    private val col = db.collection("users").document(uid).collection("marketData")
    private var cache = MarketSnapshot()
    private val docMeta = mutableMapOf<String, Pair<LocalDate?, LocalDate?>>()

    fun snapshot(): Flow<MarketSnapshot> = callbackFlow {
        val registration = col.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "시세 캐시 불러오기 실패", error)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            val prices = mutableMapOf<String, PriceHistory>()
            var fx = TreeMap<LocalDate, Double>()
            for (doc in snapshot.documents) {
                when {
                    doc.id == FX_DOC -> fx = readDateMap(doc, "rates")
                    doc.id.startsWith(PRICE_PREFIX) -> {
                        val key = doc.id.removePrefix(PRICE_PREFIX)
                        val latest = doc.getDouble("latest")?.let { price ->
                            PricePoint(price, doc.getString("latestDate")?.let(::parseDate))
                        }
                        prices[key] = PriceHistory(readDateMap(doc, "closes"), latest)
                        docMeta[key] = doc.getString("historyFrom")?.let(::parseDate) to doc.getString("historyTo")?.let(::parseDate)
                    }
                }
            }
            cache = MarketSnapshot(prices, fx)
            trySend(cache)
        }
        awaitClose { registration.remove() }
    }

    /**
     * 보유 종목의 현재가와 최신 환율을 받아 저장한다. 실패한 항목은 메시지로 모아 돌려준다.
     */
    suspend fun refreshLatest(holdings: List<Holding>, needsFx: Boolean): List<String> {
        val errors = mutableListOf<String>()
        val today = LocalDate.now()
        if (kis.hasCredentials) {
            holdings.filter { MarketKeys.quotable(it) }.distinctBy { MarketKeys.of(it) }.forEach { holding ->
                try {
                    val price = if (holding.market == Market.KR) {
                        kis.domesticPrice(holding.code)
                    } else {
                        kis.overseasPrice(holding.market, holding.code)
                    }
                    col.document(PRICE_PREFIX + MarketKeys.of(holding))
                        .set(mapOf("latest" to price, "latestDate" to today.toString()), SetOptions.merge())
                } catch (e: ApiException) {
                    errors += "${holding.name}: ${e.message}"
                    if (e.message?.contains("App Key") == true || e.message?.contains("토큰") == true) return errors
                } catch (e: Exception) {
                    errors += "${holding.name}: 네트워크 오류"
                }
            }
        } else if (holdings.any { MarketKeys.quotable(it) }) {
            errors += "한국투자증권 API 키가 없어 시세를 받지 못했습니다. 설정에서 입력하세요."
        }
        if (needsFx) {
            if (exim.hasKey) {
                try {
                    fetchFxOnOrBefore(today, maxBack = 7)
                } catch (e: ApiException) {
                    errors += e.message.orEmpty()
                } catch (e: Exception) {
                    errors += "환율: 네트워크 오류"
                }
            } else {
                errors += "수출입은행 환율 인증키가 없어 설정의 직접 입력 환율을 사용합니다."
            }
        }
        return errors
    }

    /** 그래프에 필요한 기간의 일별 종가를 받아 둔다 (이미 받은 기간은 건너뛴다). */
    suspend fun ensurePriceHistory(holdings: List<Holding>, from: LocalDate, to: LocalDate): List<String> {
        if (!kis.hasCredentials) return listOf("한국투자증권 API 키가 없어 과거 시세를 받지 못했습니다.")
        val errors = mutableListOf<String>()
        val end = minOf(to, LocalDate.now())
        holdings.filter { MarketKeys.quotable(it) }.distinctBy { MarketKeys.of(it) }.forEach { holding ->
            val key = MarketKeys.of(holding) ?: return@forEach
            val (coveredFrom, coveredTo) = docMeta[key] ?: (null to null)
            val ranges = mutableListOf<Pair<LocalDate, LocalDate>>()
            if (coveredFrom == null || coveredTo == null) {
                ranges += from to end
            } else {
                if (from.isBefore(coveredFrom)) ranges += from to coveredFrom.minusDays(1)
                if (end.isAfter(coveredTo)) ranges += coveredTo.plusDays(1) to end
            }
            if (ranges.isEmpty()) return@forEach
            try {
                val closes = mutableMapOf<String, Double>()
                for ((rangeFrom, rangeTo) in ranges) {
                    if (holding.market == Market.KR) {
                        var chunkEnd = rangeTo
                        while (!chunkEnd.isBefore(rangeFrom)) {
                            val chunkStart = maxOf(rangeFrom, chunkEnd.minusDays(140))
                            kis.domesticDaily(holding.code, chunkStart, chunkEnd).forEach { closes[it.date.toString()] = it.close }
                            chunkEnd = chunkStart.minusDays(1)
                        }
                    } else {
                        var cursor = rangeTo
                        var guard = 0
                        while (!cursor.isBefore(rangeFrom) && guard++ < 40) {
                            val rows = kis.overseasDaily(holding.market, holding.code, cursor)
                            if (rows.isEmpty()) break
                            rows.filter { !it.date.isBefore(rangeFrom) && !it.date.isAfter(rangeTo) }
                                .forEach { closes[it.date.toString()] = it.close }
                            val earliest = rows.minOf { it.date }
                            if (!earliest.isBefore(cursor)) break
                            cursor = earliest.minusDays(1)
                        }
                    }
                }
                val newFrom = listOfNotNull(coveredFrom, from).min()
                val newTo = listOfNotNull(coveredTo, end).max()
                col.document(PRICE_PREFIX + key).set(
                    mapOf("closes" to closes, "historyFrom" to newFrom.toString(), "historyTo" to newTo.toString()),
                    SetOptions.merge(),
                )
                docMeta[key] = newFrom to newTo
            } catch (e: ApiException) {
                errors += "${holding.name}: ${e.message}"
            } catch (e: Exception) {
                errors += "${holding.name}: 네트워크 오류"
            }
        }
        return errors
    }

    /** 그래프 날짜들의 환율을 받아 둔다 (이미 받은 날짜·오늘은 건너뛴다). */
    suspend fun ensureFx(dates: List<LocalDate>): List<String> {
        if (!exim.hasKey) return emptyList()
        val today = LocalDate.now()
        return try {
            for (date in dates.distinct().filter { it.isBefore(today) }) {
                if (cache.usdKrw.containsKey(date)) continue
                fetchFxOnOrBefore(date, maxBack = 7)
            }
            emptyList()
        } catch (e: ApiException) {
            listOf(e.message.orEmpty())
        } catch (e: Exception) {
            listOf("환율: 네트워크 오류")
        }
    }

    /**
     * [date] 부터 거꾸로 데이터가 있는 영업일을 찾을 때까지 환율을 받아 저장한다.
     * 데이터가 없는 지난 날짜(주말·공휴일)는 -1 로 저장해 다시 묻지 않는다.
     */
    private suspend fun fetchFxOnOrBefore(date: LocalDate, maxBack: Int) {
        val today = LocalDate.now()
        val found = mutableMapOf<String, Double>()
        var day = date
        for (step in 0..maxBack) {
            val known = cache.usdKrw[day]
            if (known != null && known > 0) break
            if (known == null) {
                val rate = exim.usdKrw(day)
                if (rate != null) {
                    found[day.toString()] = rate
                    break
                }
                // 오늘 데이터가 아직 없으면(11시 이전) "없음" 으로 저장하지 않는다.
                if (day.isBefore(today)) found[day.toString()] = -1.0
            }
            day = day.minusDays(1)
        }
        if (found.isNotEmpty()) saveFx(found)
    }

    private fun saveFx(rates: Map<String, Double>) {
        col.document(FX_DOC).set(mapOf("rates" to rates), SetOptions.merge())
        val updated = TreeMap(cache.usdKrw)
        rates.forEach { (k, v) -> parseDate(k)?.let { updated[it] = v } }
        cache = cache.copy(usdKrw = updated)
    }

    private fun readDateMap(doc: DocumentSnapshot, field: String): TreeMap<LocalDate, Double> {
        val result = TreeMap<LocalDate, Double>()
        (doc.get(field) as? Map<*, *>)?.forEach { (k, v) ->
            val date = (k as? String)?.let(::parseDate) ?: return@forEach
            val value = (v as? Number)?.toDouble() ?: return@forEach
            result[date] = value
        }
        return result
    }

    private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

    private companion object {
        const val TAG = "MarketData"
        const val PRICE_PREFIX = "price_"
        const val FX_DOC = "fx_USD"
    }
}
