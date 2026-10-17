package com.mymoneytracker.app.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.mymoneytracker.app.data.MarketDocs.PERSIST_INTERVAL_MS
import com.mymoneytracker.app.data.MarketDocs.PRICE_PREFIX
import com.mymoneytracker.app.data.MarketDocs.parseDate
import com.mymoneytracker.app.data.MarketDocs.readDateMap
import com.mymoneytracker.core.market.HistoryRanges
import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceHistory
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.PricePoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * 받아 온 시세·환율을 Firestore 에 캐시한다. 과거 종가와 환율은 바뀌지 않으므로 한 번 받으면 다시 받지 않는다.
 *
 * users/{uid}/marketData/price_{KR|US}_{코드}  { closes: {날짜: 종가}, latest, latestDate, prevClose, historyFrom, historyTo }
 * users/{uid}/marketData/fx_USD                 { rates: {날짜: 환율, 데이터 없는 날은 -1} } 최근 1주일(계속 다시 받음)·확인 전 값
 * users/{uid}/marketData/fx_USD_checked         { rates: {날짜: 고정된 환율 (두 번 받아 같은 값)}, latest, latestDate, latestSource (오늘 환율),
 *                                                 kisFrom, kisTo (한투 종가로 고정한 구간), kisComplete (한투에 더 오래된 값 없음) }
 * 환율은 [FxSync], 과거 종가는 [PriceHistorySync] 가 받고, 여기서는 현재가와 Firestore 읽기를 맡아 이어 준다.
 */
class MarketDataRepository(
    db: FirebaseFirestore,
    uid: String,
    private val kis: KisClient,
    exim: EximClient,
) {
    private val col = db.collection("users").document(uid).collection("marketData")
    private val fx = FxSync(col, kis, exim, ::awaitLoaded)
    private val history = PriceHistorySync(col, kis, ::awaitLoaded)

    /** 자주 받는 현재가는 메모리에 두고, Firestore 에는 [PERSIST_INTERVAL_MS] 마다 한 번만 저장한다 (쓰기 횟수 절약). */
    private val liveQuotes = MutableStateFlow<Map<String, LiveQuote>>(emptyMap())
    private val lastPersistedAt = mutableMapOf<String, Long>()

    /** Firestore 에 저장된 시세를 한 번이라도 읽었는지 (읽기 전에는 이미 받은 기간을 몰라 다시 받게 되므로 기다린다). */
    private val loaded = CompletableDeferred<Unit>()

    /** 저장된 시세를 한 번 읽을 때까지 기다린다 (그래프 계산도 시세를 읽은 뒤에). */
    suspend fun awaitLoaded() {
        withTimeoutOrNull(LOAD_TIMEOUT_MS) { loaded.await() }
    }

    /** Firestore 캐시 + 메모리의 최신 현재가. */
    fun snapshot(): Flow<MarketSnapshot> = combine(storedSnapshot(), liveQuotes) { stored, live ->
        if (live.isEmpty()) return@combine stored
        val prices = stored.prices.toMutableMap()
        live.forEach { (key, quote) ->
            val history = prices[key] ?: PriceHistory()
            val latest = history.latest
            val newer = latest == null || (quote.point.date ?: LocalDate.MIN) >= (latest.date ?: LocalDate.MIN)
            // 전일 종가가 없는 값(잔고 조회 가격)이면, 같은 날 저장된 전일 종가는 그대로 쓴다.
            val prevClose = quote.prevClose ?: history.prevClose.takeIf { latest?.date == quote.point.date }
            prices[key] = if (newer) history.copy(latest = quote.point, prevClose = prevClose) else history
        }
        stored.copy(prices = prices)
    }

    /**
     * 현재가 저장: 메모리에는 바로, Firestore 에는 가끔.
     * @param prevClose 전일 종가 (시세 조회에서 받은 값)
     * @param persist false 면 메모리에만 둔다. 잔고 조회 가격은 전일 종가가 없어 저장된 전일 종가를 덮지 않도록 이렇게 하고,
     *   곧이어 시세 조회가 전일 종가와 함께 저장한다.
     */
    fun saveLatest(key: String, price: Double, prevClose: Double? = null, persist: Boolean = true) {
        val today = LocalDate.now()
        liveQuotes.value = liveQuotes.value + (key to LiveQuote(PricePoint(price, today), prevClose))
        if (!persist) return
        val now = System.currentTimeMillis()
        if (now - (lastPersistedAt[key] ?: 0L) >= PERSIST_INTERVAL_MS) {
            lastPersistedAt[key] = now
            col.document(PRICE_PREFIX + key).set(
                mapOf("latest" to price, "latestDate" to today.toString(), "prevClose" to prevClose),
                SetOptions.merge(),
            )
        }
    }

    private data class LiveQuote(val point: PricePoint, val prevClose: Double?)

    private fun storedSnapshot(): Flow<MarketSnapshot> = callbackFlow {
        val registration = col.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "시세 캐시 불러오기 실패", error)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            val prices = mutableMapOf<String, PriceHistory>()
            for (doc in snapshot.documents) {
                if (fx.read(doc)) continue
                if (doc.id.startsWith(PRICE_PREFIX)) {
                    val key = doc.id.removePrefix(PRICE_PREFIX)
                    val latest = doc.getDouble("latest")?.let { price ->
                        PricePoint(price, doc.getString("latestDate")?.let(::parseDate))
                    }
                    prices[key] = PriceHistory(readDateMap(doc, "closes"), latest, doc.getDouble("prevClose"))
                    history.read(key, doc)
                }
            }
            loaded.complete(Unit)
            trySend(MarketSnapshot(prices, fx.union(), fx.latest, fx.latestSource))
        }
        awaitClose { registration.remove() }
    }

    /**
     * 금현물 현재가: 국내 현재가 조회에 KRX 금 코드를 넣고, 안 되면 최근 일별 종가 (전일 종가는 그 전날 종가).
     */
    private suspend fun goldQuote(code: String): Quote {
        val quote = try {
            kis.domesticQuote(code)
        } catch (e: ApiException) {
            if (e.message?.contains("App Key") == true || e.message?.contains("토큰") == true) throw e
            null
        }
        if (quote != null && quote.price > 0) return quote
        val rows = kis.domesticRecentDaily(code)
        val latest = rows.firstOrNull() ?: throw ApiException("$code 금현물 시세가 없습니다.")
        return Quote(latest.close, rows.getOrNull(1)?.close)
    }

    /**
     * 보유 종목의 현재가와 최신 환율을 받아 저장한다. 실패한 항목은 메시지로 모아 돌려준다.
     */
    suspend fun refreshLatest(holdings: List<Holding>, needsFx: Boolean, forceFx: Boolean = false): List<String> {
        val errors = mutableListOf<String>()
        val today = LocalDate.now()
        if (kis.hasCredentials) {
            holdings.filter { MarketKeys.quotable(it) }.distinctBy { MarketKeys.of(it) }.forEach { holding ->
                try {
                    val quote = if (holding.assetType == AssetType.GOLD) {
                        goldQuote(holding.code)
                    } else if (holding.market == Market.KR) {
                        kis.domesticQuote(holding.code)
                    } else {
                        kis.overseasQuote(holding.market, holding.code)
                    }
                    MarketKeys.of(holding)?.let { saveLatest(it, quote.price, quote.prevClose) }
                } catch (e: ApiException) {
                    errors += if (e.message == KisClient.RATE_LIMIT_MESSAGE) KisClient.RATE_LIMIT_MESSAGE else "${holding.name}: ${e.message}"
                    if (e.message?.contains("App Key") == true || e.message?.contains("토큰") == true) return errors
                } catch (e: Exception) {
                    errors += "${holding.name}: 네트워크 오류"
                }
            }
        } else if (holdings.any { MarketKeys.quotable(it) }) {
            errors += "한국투자증권 API 키가 없어 시세를 받지 못했습니다. 설정에서 입력하세요."
        }
        if (needsFx) errors += fx.refreshLatest(today, forceFx)
        return errors
    }

    /**
     * 보유 기간의 일별 종가를 받아 저장한다 ([HistoryRanges.prices]). 이미 받은 기간은 건너뛰고, 오늘은 현재가를 쓰므로 어제까지만.
     * 받은 종가는 Firestore 에 저장되어 그래프(폰·PC 웹)가 다시 조회하지 않고 쓴다.
     */
    suspend fun ensureHistory(ranges: List<HistoryRanges.Range>): List<String> = history.ensureHistory(ranges)

    /** 수출입은행으로 [from] ~ 8일 전 과거 환율을 고정한다 (호출 최대 [budget] 회). 돌려주는 값: (쓴 호출 수, 오류). */
    suspend fun backfillFx(from: LocalDate, budget: Int): Pair<Int, List<String>> = fx.backfillFx(from, budget)

    /** 한투 원/달러 일별 종가로 1주일보다 오래된 과거 환율을 고정한다. 돌려주는 값: 오류. */
    suspend fun backfillKisFx(from: LocalDate): List<String> = fx.backfillKisFx(from)

    /** 매매일 환율을 받아 둔다 (이미 받은 날짜·오늘은 건너뛴다). */
    suspend fun ensureFx(dates: List<LocalDate>): List<String> = fx.ensureFx(dates)

    private companion object {
        const val TAG = "MarketData"
        const val LOAD_TIMEOUT_MS = 10_000L
    }
}
