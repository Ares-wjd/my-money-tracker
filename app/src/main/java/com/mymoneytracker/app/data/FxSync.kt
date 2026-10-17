package com.mymoneytracker.app.data

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.SetOptions
import com.mymoneytracker.app.data.MarketDocs.FX_CHECKED_DOC
import com.mymoneytracker.app.data.MarketDocs.FX_DOC
import com.mymoneytracker.app.data.MarketDocs.PERSIST_INTERVAL_MS
import com.mymoneytracker.app.data.MarketDocs.parseDate
import com.mymoneytracker.app.data.MarketDocs.readDateMap
import com.mymoneytracker.core.market.FxRules
import java.time.LocalDate
import java.util.TreeMap

/**
 * 원/달러 환율을 받아 marketData 의 fx_USD (최근 1주일·확인 전) · fx_USD_checked (고정된 과거, 오늘 환율) 에 저장한다.
 * 한투 원/달러(FX@KRW) 가 기본, 수출입은행 매매기준율은 한투가 없을 때와 한투 구간 이전 날짜에만 (v2.8).
 * 환율 상태는 이 클래스 안에서만 바뀐다. [awaitLoaded] 는 저장된 시세를 한 번 읽을 때까지 기다린다.
 */
class FxSync(
    private val col: CollectionReference,
    private val kis: KisClient,
    private val exim: EximClient,
    private val awaitLoaded: suspend () -> Unit,
) {
    /** 환율: fx_USD (최근 1주일·확인 전), fx_USD_checked (고정된 과거), 오늘 환율. 계산에는 앞의 것 위에 고정 값을 덮어 쓴다. */
    private var fxRecent = TreeMap<LocalDate, Double>()
    private var fxChecked = TreeMap<LocalDate, Double>()
    /** 오늘 환율 (날짜, 값)과 출처. */
    var latest: Pair<LocalDate, Double>? = null
        private set
    var latestSource: String? = null
        private set
    private var lastRecentFxAt = 0L
    /** 한투 종가로 고정한 구간 (그 구간은 수출입은행으로 받지 않는다). */
    private var kisFrom: LocalDate? = null
    private var kisTo: LocalDate? = null
    private var kisComplete = false
    private var lastLatestFxSavedAt = 0L
    private var lastFxAttemptAt = 0L

    /** 계산에 쓰는 환율: fx_USD (최근 1주일·확인 전) 위에 고정된 값을 덮어 쓴다. */
    fun union(): TreeMap<LocalDate, Double> = TreeMap(fxRecent).apply { putAll(fxChecked) }

    /** Firestore 에서 읽은 환율 문서 (fx_USD · fx_USD_checked) 를 반영한다. 다른 문서면 false. */
    fun read(doc: DocumentSnapshot): Boolean {
        when (doc.id) {
            FX_DOC -> fxRecent = readDateMap(doc, "rates")
            FX_CHECKED_DOC -> {
                fxChecked = readDateMap(doc, "rates")
                val rate = doc.getDouble("latest")
                val date = doc.getString("latestDate")?.let(::parseDate)
                // 메모리에 같은 날 값(방금 받은 현재가, Firestore 에는 가끔 저장)이 있으면 그것을 둔다
                val stored = if (rate != null && date != null) date to rate else null
                val memory = latest
                if (stored != null && (memory == null || stored.first.isAfter(memory.first))) {
                    latest = stored
                    latestSource = doc.getString("latestSource")
                }
                kisFrom = doc.getString("kisFrom")?.let(::parseDate)
                kisTo = doc.getString("kisTo")?.let(::parseDate)
                kisComplete = doc.getBoolean("kisComplete") == true
            }
            else -> return false
        }
        return true
    }

    /** 최신 환율을 받는다 (시세 새로고침 때). 실패하면 메시지 목록. */
    suspend fun refreshLatest(today: LocalDate, forceFx: Boolean): List<String> {
        val errors = mutableListOf<String>()
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
            latest = today to rate
            latestSource = FxRules.SOURCE_EXIM
            col.document(FX_CHECKED_DOC).set(
                mapOf("latest" to rate, "latestDate" to today.toString(), "latestSource" to FxRules.SOURCE_EXIM),
                SetOptions.merge(),
            )
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
            val dateChanged = latest?.first != date
            latest = date to current
            latestSource = FxRules.SOURCE_KIS
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
                if (union().containsKey(date) && (FxRules.isRecent(date, today) || fxChecked.containsKey(date))) continue
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

    private fun saveRecentFx(rates: Map<String, Double>) {
        col.document(FX_DOC).set(mapOf("rates" to rates), SetOptions.merge())
        rates.forEach { (k, v) -> parseDate(k)?.let { fxRecent[it] = v } }
    }

    private fun saveCheckedFx(rates: Map<String, Double>) {
        col.document(FX_CHECKED_DOC).set(mapOf("rates" to rates), SetOptions.merge())
        rates.forEach { (k, v) -> parseDate(k)?.let { fxChecked[it] = v } }
    }

    private companion object {
        /** 최근 1주일 환율을 다시 받는 간격. */
        const val RECENT_FX_INTERVAL_MS = 3 * 60 * 60 * 1000L
        const val FX_INTERVAL_MS = 30 * 60 * 1000L
        const val FX_SAVE_CHUNK = 50
        /** 한투 환율 일별 조회 한 번의 기간 (최대 100개라 140일이면 거의 다 들어온다, 넘치면 받은 만큼만). */
        const val KIS_FX_PAGE_DAYS = 140L
        const val KIS_FX_PAGE_LIMIT = 100
        /** 한 번에 받는 최대 페이지 (약 23년). */
        const val KIS_FX_MAX_PAGES = 60
    }
}
