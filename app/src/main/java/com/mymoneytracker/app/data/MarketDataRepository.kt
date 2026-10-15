package com.mymoneytracker.app.data

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.mymoneytracker.core.market.FxRules
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.TreeMap

/**
 * 받아 온 시세·환율을 Firestore 에 캐시한다. 과거 종가와 환율은 바뀌지 않으므로 한 번 받으면 다시 받지 않는다.
 *
 * users/{uid}/marketData/price_{KR|US}_{코드}  { closes: {날짜: 종가}, latest, latestDate, prevClose, historyFrom, historyTo }
 * users/{uid}/marketData/fx_USD                 { rates: {날짜: 환율, 데이터 없는 날은 -1} } 최근 1주일(계속 다시 받음)·확인 전 값
 * users/{uid}/marketData/fx_USD_checked         { rates: {날짜: 고정된 환율 (두 번 받아 같은 값)}, latest, latestDate, latestSource (오늘 환율),
 *                                                 kisFrom, kisTo (한투 종가로 고정한 구간), kisComplete (한투에 더 오래된 값 없음) }
 * 환율은 한투 원/달러(FX@KRW) 가 기본, 수출입은행 매매기준율은 한투가 없을 때와 한투 구간 이전 날짜에만 (v2.8).
 */
class MarketDataRepository(
    db: FirebaseFirestore,
    uid: String,
    private val kis: KisClient,
    private val exim: EximClient,
) {
    private val col = db.collection("users").document(uid).collection("marketData")
    private var cache = MarketSnapshot()
    /** 환율: fx_USD (최근 1주일·확인 전), fx_USD_checked (고정된 과거), 오늘 환율. 계산에는 앞의 것 위에 고정 값을 덮어 쓴다. */
    private var fxRecent = TreeMap<LocalDate, Double>()
    private var fxChecked = TreeMap<LocalDate, Double>()
    private var fxLatest: Pair<LocalDate, Double>? = null
    private var fxLatestSource: String? = null
    private var lastRecentFxAt = 0L
    /** 한투 종가로 고정한 구간 (그 구간은 수출입은행으로 받지 않는다). */
    private var kisFrom: LocalDate? = null
    private var kisTo: LocalDate? = null
    private var kisComplete = false
    private var lastLatestFxSavedAt = 0L
    private val docMeta = mutableMapOf<String, Pair<LocalDate?, LocalDate?>>()

    /** 자주 받는 현재가는 메모리에 두고, Firestore 에는 [PERSIST_INTERVAL_MS] 마다 한 번만 저장한다 (쓰기 횟수 절약). */
    private val liveQuotes = MutableStateFlow<Map<String, LiveQuote>>(emptyMap())
    private val lastPersistedAt = mutableMapOf<String, Long>()
    private var lastFxAttemptAt = 0L

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
                when {
                    doc.id == FX_DOC -> fxRecent = readDateMap(doc, "rates")
                    doc.id == FX_CHECKED_DOC -> {
                        fxChecked = readDateMap(doc, "rates")
                        val rate = doc.getDouble("latest")
                        val date = doc.getString("latestDate")?.let(::parseDate)
                        // 메모리에 같은 날 값(방금 받은 현재가, Firestore 에는 가끔 저장)이 있으면 그것을 둔다
                        val stored = if (rate != null && date != null) date to rate else null
                        val memory = fxLatest
                        if (stored != null && (memory == null || stored.first.isAfter(memory.first))) {
                            fxLatest = stored
                            fxLatestSource = doc.getString("latestSource")
                        }
                        kisFrom = doc.getString("kisFrom")?.let(::parseDate)
                        kisTo = doc.getString("kisTo")?.let(::parseDate)
                        kisComplete = doc.getBoolean("kisComplete") == true
                    }
                    doc.id.startsWith(PRICE_PREFIX) -> {
                        val key = doc.id.removePrefix(PRICE_PREFIX)
                        val latest = doc.getDouble("latest")?.let { price ->
                            PricePoint(price, doc.getString("latestDate")?.let(::parseDate))
                        }
                        prices[key] = PriceHistory(readDateMap(doc, "closes"), latest, doc.getDouble("prevClose"))
                        docMeta[key] = doc.getString("historyFrom")?.let(::parseDate) to doc.getString("historyTo")?.let(::parseDate)
                    }
                }
            }
            cache = MarketSnapshot(prices, fxUnion(), fxLatest, fxLatestSource)
            loaded.complete(Unit)
            trySend(cache)
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
        if (needsFx) {
            // 한투 원/달러가 기본 (새로고침할 때마다 현재가, 실패하면 수출입은행)
            var kisError: String? = null
            if (kis.hasCredentials) {
                try {
                    refreshKisFx(today)
                    return errors
                } catch (e: ApiException) {
                    kisError = "환율(한투): ${e.message}"
                } catch (e: Exception) {
                    kisError = "환율(한투): 네트워크 오류"
                }
            }
            // 수출입은행은 하루 한 번 바뀌므로 30분에 한 번만 조회한다 (일일 호출 한도 1,000회).
            val fxDue = forceFx || System.currentTimeMillis() - lastFxAttemptAt >= FX_INTERVAL_MS
            if (exim.hasKey) {
                if (fxDue) {
                    lastFxAttemptAt = System.currentTimeMillis()
                    try {
                        refreshFxNow(today)
                    } catch (e: ApiException) {
                        errors += listOfNotNull(kisError, e.message)
                    } catch (e: Exception) {
                        errors += listOfNotNull(kisError, "환율: 네트워크 오류")
                    }
                }
            } else {
                errors += kisError ?: "환율을 받을 한투·수출입은행 키가 없어 설정의 직접 입력 환율을 사용합니다."
            }
        }
        return errors
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

    /**
     * [from] ~ 8일 전의 일별 환율을 최근 날짜부터 두 번 받아 같으면 고정한다 (이미 고정한 날·"없음"으로 고정한 휴일은 건너뜀).
     * 최근 1주일은 [refreshFxNow] 가 계속 다시 받는다. 수출입은행 호출은 최대 [budget] 회. 돌려주는 값: (쓴 호출 수, 오류).
     */
    suspend fun backfillFx(from: LocalDate, budget: Int): Pair<Int, List<String>> {
        if (!exim.hasKey || budget <= 0) return 0 to emptyList()
        awaitLoaded()
        val today = LocalDate.now()
        var calls = 0
        val found = mutableMapOf<String, Double>()
        return try {
            // 최근 1주일은 따로 계속 다시 받으므로, 그보다 오래된 날짜만 두 번 받아 고정한다
            var day = today.minusDays(FxRules.RECENT_DAYS + 1)
            // 한투 종가로 고정한 구간은 건너뛰고 그 이전만
            kisFrom?.let { if (!day.isBefore(it)) day = it.minusDays(1) }
            while (!day.isBefore(from) && calls < budget) {
                if (fxChecked[day] == null) {
                    val (rate, used) = verifiedRate(day)
                    calls += used
                    rate?.let { found[day.toString()] = it }
                    // 중간에 끊겨도 받은 만큼은 남도록 나눠 저장
                    if (found.size >= FX_SAVE_CHUNK) {
                        saveCheckedFx(found.toMap())
                        found.clear()
                    }
                }
                day = day.minusDays(1)
            }
            calls to emptyList()
        } catch (e: ApiException) {
            calls to listOf(e.message.orEmpty())
        } catch (e: Exception) {
            calls to listOf("환율: 네트워크 오류")
        } finally {
            if (found.isNotEmpty()) saveCheckedFx(found)
        }
    }

    /**
     * 지난 날짜의 환율을 두 번 이상 받아 같은 값 (세 번 중 두 번 같은 값). fx_USD 에 이미 있는 값은 첫 번째로 본다.
     * 돌려주는 값: (고정할 값 — "없음"은 -1, 셋 다 다르면 null, 쓴 호출 수).
     */
    private suspend fun verifiedRate(day: LocalDate): Pair<Double?, Int> {
        val values = mutableListOf<Double>()
        fxRecent[day]?.let { values += it }
        var calls = 0
        while (FxRules.needsMore(values)) {
            values += exim.usdKrw(day) ?: -1.0
            calls++
        }
        return FxRules.agreed(values) to calls
    }

    /** (예비) 수출입은행 오늘 환율 (11시 이후 발표) 과, 최근 1주일 환율 (3시간마다 다시 받아 바꿈). */
    private suspend fun refreshFxNow(today: LocalDate) {
        exim.usdKrw(today)?.let { rate ->
            fxLatest = today to rate
            fxLatestSource = FxRules.SOURCE_EXIM
            col.document(FX_CHECKED_DOC).set(
                mapOf("latest" to rate, "latestDate" to today.toString(), "latestSource" to FxRules.SOURCE_EXIM),
                SetOptions.merge(),
            )
            cache = cache.copy(usdKrwLatest = fxLatest, usdKrwLatestSource = fxLatestSource)
        }
        val now = System.currentTimeMillis()
        if (now - lastRecentFxAt < RECENT_FX_INTERVAL_MS) return
        lastRecentFxAt = now
        val recent = mutableMapOf<String, Double>()
        for (back in 1..FxRules.RECENT_DAYS) {
            val day = today.minusDays(back)
            recent[day.toString()] = exim.usdKrw(day) ?: -1.0
        }
        saveRecentFx(recent)
    }

    /**
     * 한투 원/달러: 현재가(오늘 평가금)와 최근 1주일 일별 종가 (fx_USD, 바뀐 날만 저장). 현재가는 메모리에 바로,
     * Firestore 에는 [PERSIST_INTERVAL_MS] 마다 (날짜가 바뀌면 바로).
     */
    private suspend fun refreshKisFx(today: LocalDate) {
        val page = kis.usdKrwDaily(today.minusDays(FxRules.RECENT_DAYS + 7), today)
        val closes = page.closes.associate { it.date to it.close }
        // 오늘 줄이 있으면 오늘, 없으면(주말 등) 마지막 거래일
        val lastDay = closes.keys.maxOrNull()
        val current = page.current ?: lastDay?.let { closes[it] }
        if (current != null) {
            val date = if (lastDay == null || closes.containsKey(today)) today else lastDay
            val dateChanged = fxLatest?.first != date
            fxLatest = date to current
            fxLatestSource = FxRules.SOURCE_KIS
            cache = cache.copy(usdKrwLatest = fxLatest, usdKrwLatestSource = fxLatestSource)
            val now = System.currentTimeMillis()
            if (dateChanged || now - lastLatestFxSavedAt >= PERSIST_INTERVAL_MS) {
                lastLatestFxSavedAt = now
                col.document(FX_CHECKED_DOC).set(
                    mapOf("latest" to current, "latestDate" to date.toString(), "latestSource" to FxRules.SOURCE_KIS),
                    SetOptions.merge(),
                )
            }
        }
        val recent = (1..FxRules.RECENT_DAYS).associate { back ->
            val day = today.minusDays(back)
            day.toString() to (closes[day] ?: -1.0)
        }.filter { (k, v) -> parseDate(k)?.let { fxRecent[it] } != v }
        if (recent.isNotEmpty()) saveRecentFx(recent)
    }

    /**
     * 한투 원/달러 일별 종가로 1주일보다 오래된 과거 환율을 고정한다 (같은 기간을 두 번 받아 같은 날짜만, 다르면 한 번 더).
     * 처음에는 8일 전부터 [from] 까지 거꾸로 (기존 수출입은행 값을 바꿈), 그 뒤에는 새로 1주일이 지난 날짜와
     * [from] 이 더 이르게 바뀐 만큼만. 한투에 더 오래된 값이 없으면 kisComplete. 돌려주는 값: 오류.
     */
    suspend fun backfillKisFx(from: LocalDate): List<String> {
        if (!kis.hasCredentials) return emptyList()
        awaitLoaded()
        val end = LocalDate.now().minusDays(FxRules.RECENT_DAYS + 1)
        return try {
            val to = kisTo
            val start = kisFrom
            if (to == null || start == null) {
                confirmKisRange(from, end)
            } else {
                // 새로 1주일이 지난 날짜
                if (to.isBefore(end)) confirmKisRange(to.plusDays(1), end)
                // 필요한 기간이 더 이르면 그만큼 더 거꾸로
                if (!kisComplete && from.isBefore(start)) confirmKisRange(from, start.minusDays(1))
            }
            emptyList()
        } catch (e: ApiException) {
            listOf("환율(한투): ${e.message}")
        } catch (e: Exception) {
            listOf("환율(한투): 네트워크 오류")
        }
    }

    /** [from] ~ [to] 를 최근부터 [KIS_FX_PAGE_DAYS] 일씩 거꾸로 받아 고정하고, 고정한 구간을 kisFrom·kisTo 에 넓혀 저장한다. */
    private suspend fun confirmKisRange(from: LocalDate, to: LocalDate) {
        var until = to
        var pages = 0
        while (!until.isBefore(from) && pages < KIS_FX_MAX_PAGES) {
            val pageFrom = maxOf(from, until.minusDays(KIS_FX_PAGE_DAYS - 1))
            val fetches = mutableListOf<Map<LocalDate, Double>>()
            var truncatedAt: LocalDate? = null
            while (FxRules.needsMoreDaily(fetches, pageFrom, until)) {
                val page = kis.usdKrwDaily(pageFrom, until)
                // 한 번에 최대 100개라 기간 앞쪽이 잘렸으면 받은 가장 이른 날짜까지만 고정
                if (page.closes.size >= KIS_FX_PAGE_LIMIT) {
                    val oldest = page.closes.minOf { it.date }
                    truncatedAt = maxOf(truncatedAt ?: oldest, oldest)
                }
                fetches += page.closes.associate { it.date to it.close }
                // 두 번 다 비어 있으면 한투에 더 오래된 값이 없음
                if (fetches.size >= 2 && fetches.all { it.isEmpty() }) break
            }
            pages++
            if (fetches.all { it.isEmpty() }) {
                kisComplete = true
                col.document(FX_CHECKED_DOC).set(mapOf("kisComplete" to true), SetOptions.merge())
                break
            }
            // 가장 이른 값이 기간 시작보다 1주일 넘게 뒤면 한투 기록의 시작 (그 이전은 수출입은행이 채우도록 비워 둔다)
            val oldestRow = fetches.flatMap { it.keys }.min()
            val historyStart = truncatedAt == null && oldestRow.isAfter(pageFrom.plusDays(7))
            val fixedFrom = truncatedAt ?: if (historyStart) oldestRow else pageFrom
            val agreed = FxRules.agreeDaily(fetches, fixedFrom, until)
            val newFrom = minOf(kisFrom ?: fixedFrom, fixedFrom)
            val newTo = maxOf(kisTo ?: until, until)
            kisFrom = newFrom
            kisTo = newTo
            col.document(FX_CHECKED_DOC).set(
                mapOf(
                    "rates" to agreed.mapKeys { it.key.toString() },
                    "kisFrom" to newFrom.toString(),
                    "kisTo" to newTo.toString(),
                ),
                SetOptions.merge(),
            )
            agreed.forEach { (d, v) -> fxChecked[d] = v }
            cache = cache.copy(usdKrw = fxUnion())
            if (historyStart) {
                kisComplete = true
                col.document(FX_CHECKED_DOC).set(mapOf("kisComplete" to true), SetOptions.merge())
                break
            }
            until = fixedFrom.minusDays(1)
        }
    }

    /** 그래프 날짜들의 환율을 받아 둔다 (이미 받은 날짜·오늘은 건너뛴다). */
    suspend fun ensureFx(dates: List<LocalDate>): List<String> {
        if (!exim.hasKey) return emptyList()
        val today = LocalDate.now()
        return try {
            for (date in dates.distinct().filter { it.isBefore(today) }) {
                if (cache.usdKrw.containsKey(date) && (FxRules.isRecent(date, today) || fxChecked.containsKey(date))) continue
                // 한투로 받는 구간은 수출입은행으로 받지 않는다
                if (kis.hasCredentials && (FxRules.isRecent(date, today) || kisFrom?.let { !date.isBefore(it) } == true)) continue
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
     * [date] 부터 거꾸로 데이터가 있는 영업일을 찾을 때까지 환율을 받아 저장한다 (매매일 환율용).
     * 최근 1주일은 한 번 받아 fx_USD 에, 그보다 오래된 날짜는 두 번 받아 같으면 고정한다. "없음"(주말·공휴일)은 -1.
     */
    private suspend fun fetchFxOnOrBefore(date: LocalDate, maxBack: Int) {
        val today = LocalDate.now()
        val recent = mutableMapOf<String, Double>()
        val checked = mutableMapOf<String, Double>()
        var day = date
        for (step in 0..maxBack) {
            if (!day.isBefore(today)) break
            if (FxRules.isRecent(day, today)) {
                val rate = fxRecent[day] ?: (exim.usdKrw(day) ?: -1.0).also { recent[day.toString()] = it }
                if (rate > 0) break
            } else {
                val rate = fxChecked[day] ?: verifiedRate(day).first?.also { checked[day.toString()] = it }
                // 셋 다 달라 고정하지 못했으면 다음에 다시
                if (rate == null || rate > 0) break
            }
            day = day.minusDays(1)
        }
        if (recent.isNotEmpty()) saveRecentFx(recent)
        if (checked.isNotEmpty()) saveCheckedFx(checked)
    }

    /** 계산에 쓰는 환율: fx_USD (최근 1주일·확인 전) 위에 고정된 값을 덮어 쓴다. */
    private fun fxUnion(): TreeMap<LocalDate, Double> = TreeMap(fxRecent).apply { putAll(fxChecked) }

    private fun saveRecentFx(rates: Map<String, Double>) {
        col.document(FX_DOC).set(mapOf("rates" to rates), SetOptions.merge())
        rates.forEach { (k, v) -> parseDate(k)?.let { fxRecent[it] = v } }
        cache = cache.copy(usdKrw = fxUnion())
    }

    private fun saveCheckedFx(rates: Map<String, Double>) {
        col.document(FX_CHECKED_DOC).set(mapOf("rates" to rates), SetOptions.merge())
        rates.forEach { (k, v) -> parseDate(k)?.let { fxChecked[it] = v } }
        cache = cache.copy(usdKrw = fxUnion())
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
        const val FX_CHECKED_DOC = "fx_USD_checked"
        /** 최근 1주일 환율을 다시 받는 간격. */
        const val RECENT_FX_INTERVAL_MS = 3 * 60 * 60 * 1000L
        const val PERSIST_INTERVAL_MS = 15 * 60 * 1000L
        const val FX_INTERVAL_MS = 30 * 60 * 1000L
        const val LOAD_TIMEOUT_MS = 10_000L
        const val FX_SAVE_CHUNK = 50
        /** 한투 환율 일별 조회 한 번의 기간 (최대 100개라 140일이면 거의 다 들어온다, 넘치면 받은 만큼만). */
        const val KIS_FX_PAGE_DAYS = 140L
        const val KIS_FX_PAGE_LIMIT = 100
        /** 한 번에 받는 최대 페이지 (약 23년). */
        const val KIS_FX_MAX_PAGES = 60
        const val HISTORY_GAP_MS = 500L
    }
}
