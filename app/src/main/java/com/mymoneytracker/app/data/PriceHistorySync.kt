package com.mymoneytracker.app.data

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.SetOptions
import com.mymoneytracker.app.data.MarketDocs.PRICE_PREFIX
import com.mymoneytracker.app.data.MarketDocs.parseDate
import com.mymoneytracker.core.market.HistoryRanges
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Market
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * 종목의 과거 일별 종가를 받아 marketData 의 price_{키} 문서(closes, historyFrom·historyTo)에 저장한다.
 * 받은 기간을 기억해 이미 받은 기간은 다시 받지 않는다. [awaitLoaded] 는 저장된 시세를 한 번 읽을 때까지 기다린다.
 */
class PriceHistorySync(
    private val col: CollectionReference,
    private val kis: KisClient,
    private val awaitLoaded: suspend () -> Unit,
) {
    /** 종목 키 → 이미 받은 기간 (historyFrom, historyTo). */
    private val docMeta = mutableMapOf<String, Pair<LocalDate?, LocalDate?>>()

    /** Firestore 에서 읽은 시세 문서의 받은 기간을 기억한다. */
    fun read(key: String, doc: DocumentSnapshot) {
        docMeta[key] = doc.getString("historyFrom")?.let(::parseDate) to doc.getString("historyTo")?.let(::parseDate)
    }

    /**
     * 보유 기간의 일별 종가를 받아 저장한다 ([HistoryRanges.prices]). 이미 받은 기간은 건너뛰고, 오늘은 현재가를 쓰므로 어제까지만.
     * 받은 종가는 Firestore 에 저장되어 그래프(폰·PC 웹)가 다시 조회하지 않고 쓴다.
     */
    suspend fun ensureHistory(ranges: List<HistoryRanges.Range>): List<String> {
        if (ranges.isEmpty()) return emptyList()
        if (!kis.hasCredentials) return listOf("한국투자증권 API 키가 없어 과거 시세를 받지 못했습니다.")
        awaitLoaded()
        val errors = mutableListOf<String>()
        val yesterday = LocalDate.now().minusDays(1)
        ranges.forEach { range ->
            val holding = range.holding
            val key = range.key
            val from = range.from
            val end = minOf(range.to, yesterday)
            if (end.isBefore(from)) return@forEach
            val (coveredFrom, coveredTo) = docMeta[key] ?: (null to null)
            // 아직 받지 않은 앞·뒤 기간만
            val missing = mutableListOf<Pair<LocalDate, LocalDate>>()
            if (coveredFrom == null || coveredTo == null) {
                missing += from to end
            } else {
                if (from.isBefore(coveredFrom)) missing += from to coveredFrom.minusDays(1)
                // 마지막으로 받은 날도 다시 받는다 (예전 버전은 장중에 오늘 종가를 저장했을 수 있어서)
                if (end.isAfter(coveredTo)) missing += coveredTo to end
            }
            if (missing.isEmpty()) return@forEach
            // 뒤에서 받는 작업이라 종목 사이마다 잠깐 쉬어, 잔고·현재가 새로고침이 먼저 나가게 한다
            delay(HISTORY_GAP_MS)
            try {
                val closes = mutableMapOf<String, Double>()
                // 금현물: 기간별 일봉이 안 되면 최근 30 영업일 종가만 쓴다 (한 번만 받음)
                var recent: List<DailyClose>? = null
                for ((rangeFrom, rangeTo) in missing) {
                    if (holding.assetType == AssetType.GOLD) {
                        val rows = runCatching { kis.domesticDaily(holding.code, rangeFrom, rangeTo) }.getOrNull()
                        val usable = if (rows.isNullOrEmpty()) {
                            (recent ?: kis.domesticRecentDaily(holding.code).also { recent = it })
                                .filter { !it.date.isBefore(rangeFrom) && !it.date.isAfter(rangeTo) }
                        } else {
                            rows
                        }
                        usable.forEach { closes[it.date.toString()] = it.close }
                    } else if (holding.market == Market.KR) {
                        var chunkEnd = rangeTo
                        while (!chunkEnd.isBefore(rangeFrom)) {
                            val chunkStart = maxOf(rangeFrom, chunkEnd.minusDays(130)) // 한 번에 최대 100 영업일
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
                errors += if (e.message == KisClient.RATE_LIMIT_MESSAGE) KisClient.RATE_LIMIT_MESSAGE else "${holding.name}: ${e.message}"
            } catch (e: Exception) {
                errors += "${holding.name}: 네트워크 오류"
            }
        }
        return errors
    }

    private companion object {
        const val HISTORY_GAP_MS = 500L
    }
}
