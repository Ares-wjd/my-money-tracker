package com.mymoneytracker.app.ui

import android.util.Log
import com.mymoneytracker.app.data.ChartRepository
import com.mymoneytracker.app.data.MarketDataRepository
import com.mymoneytracker.core.chart.ChartCalculator
import com.mymoneytracker.core.chart.ChartInterval
import com.mymoneytracker.core.chart.ChartStore
import com.mymoneytracker.core.chart.StoredChart
import com.mymoneytracker.core.market.HistoryRanges
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.RecordType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 그래프: 일별 값을 계산해 저장하고 (기록·시세가 바뀐 범위만), 그래프에 필요한 과거 시세·환율과 매매일 환율을 뒤에서 받는다.
 * 그래프 화면 값은 저장된 일별 값 + 현재가로 바로 계산한 오늘 값.
 */
@OptIn(FlowPreview::class)
class ChartSync(
    private val scope: CoroutineScope,
    private val data: StateFlow<PortfolioData?>,
    private val apiStatus: StateFlow<ApiStatus>,
    private val chartRepository: ChartRepository,
    private val marketRepository: MarketDataRepository,
    private val prefs: DisplayPrefs,
) {
    private val selection = MutableStateFlow(ChartSelection())
    private val loading = MutableStateFlow(false)
    private val errors = MutableStateFlow<List<String>>(emptyList())
    private var historyJob: Job? = null
    private var historyCheckedAt = 0L

    /** 미리 계산해 저장한 그래프 일별 값 (계좌 id → 값). null 이면 아직 읽기 전. 기간별 수익 계산에도 쓴다. */
    val storedCharts: StateFlow<Map<String, StoredChart>?> = chartRepository.stored()
        .stateIn(scope, SharingStarted.Eagerly, null)
    private val _computing = MutableStateFlow(false)
    /** 그래프 일별 값을 계산하는 중 (설정의 [그래프 전체 다시 계산] 표시용). */
    val computing: StateFlow<Boolean> = _computing.asStateFlow()
    private val trigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var forceNext = false

    /** 그래프: 저장된 일별 값 + 현재가로 바로 계산한 오늘 값 (모든 보기 단위가 전체 기간). */
    val chart: StateFlow<ChartState> = combine(
        combine(data, storedCharts, _computing, ::Triple),
        selection,
        loading,
        errors,
    ) { (current, stored, computing), selection, loading, errors ->
        if (current == null) return@combine ChartState(selection, loading = true)
        val today = LocalDate.now()
        val accounts = current.accounts.filter { selection.accountId == null || it.id == selection.accountId }
        val firsts = ChartStore.firstDates(accounts, current.records)
        if (firsts.isEmpty()) return@combine ChartState(selection, loading = loading, errors = errors)
        if (!chartRepository.available) {
            // 저장값을 읽을 수 없으면 (보안 규칙 게시 전) 예전처럼 바로 계산
            val dates = ChartCalculator.pointDates(selection.interval, today, firsts.values.min())
            val points = ChartCalculator.series(
                dates, today, current.accounts, current.holdings, current.records, current.market, current.settings.manualUsdKrw, selection.accountId,
            )
            return@combine ChartState(selection, points, loading = loading, errors = errors)
        }
        val todayPoint = ChartCalculator.series(
            listOf(today), today, current.accounts, current.holdings, current.records, current.market,
            current.settings.manualUsdKrw, selection.accountId,
        ).single()
        val view = ChartStore.assemble(selection.interval, today, firsts, stored.orEmpty(), todayPoint)
        ChartState(
            selection = selection,
            points = view.points,
            loading = loading,
            computing = computing || view.pending,
            errors = errors,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ChartState())

    init {
        // 기록·종목·시세·저장값이 바뀌면 (잠깐 기다렸다가) 다시 계산할 범위를 찾아 계산한다. 한 번에 하나씩.
        scope.launch {
            merge(data.filterNotNull().map { }, storedCharts.filterNotNull().map { }, trigger)
                .debounce(CHART_DEBOUNCE_MS)
                .collect { runCatching { computeCharts() }.onFailure { Log.w(TAG, "그래프 계산 실패", it) } }
        }
    }

    fun selectInterval(interval: ChartInterval) {
        selection.value = selection.value.copy(interval = interval)
    }

    fun selectAccount(accountId: String?) {
        selection.value = selection.value.copy(accountId = accountId)
    }

    /** 설정의 [그래프 전체 다시 계산]: 모든 계좌의 일별 값을 처음부터 다시 계산한다. */
    fun recomputeAll() {
        forceNext = true
        trigger.tryEmit(Unit)
    }

    /**
     * 그래프 일별 값 계산: 계좌마다 지금 기록·종목·시세 요약을 저장된 것과 비교해 다시 계산할 범위만 계산하고 저장한다.
     * 과거 시세를 받는 중이면 끝난 뒤에 한다 (받는 동안 여러 번 다시 계산하지 않게).
     */
    private suspend fun computeCharts() {
        if (historyJob?.isActive == true || !chartRepository.available) return
        val current = data.value ?: return
        val stored = storedCharts.value ?: return
        marketRepository.awaitLoaded()
        val force = forceNext
        forceNext = false
        val end = LocalDate.now().minusDays(1)
        // 지운 계좌의 저장값은 지운다
        val ids = current.accounts.map { it.id }.toSet()
        stored.keys.filter { it !in ids }.forEach { chartRepository.delete(it) }
        val plans = withContext(Dispatchers.Default) {
            current.accounts.mapNotNull { account ->
                val plan = ChartStore.plan(
                    account, current.holdings, current.records, current.market, current.settings.manualUsdKrw, stored[account.id], end, force,
                )
                if (plan == null && stored[account.id] != null && ChartStore.related(account.id, current.records).isEmpty()) {
                    chartRepository.delete(account.id)
                }
                plan?.let { account to it }
            }
        }
        if (plans.isEmpty()) return
        _computing.value = true
        try {
            for ((account, plan) in plans) {
                val computed = withContext(Dispatchers.Default) {
                    ChartStore.compute(plan, account, current.holdings, current.records, current.market, current.settings.manualUsdKrw)
                }
                chartRepository.save(account.id, ChartStore.build(plan, computed))
            }
        } finally {
            _computing.value = false
        }
    }

    /**
     * 보유했던 기간의 과거 종가와 해외 종목·달러가 있던 기간의 환율을 뒤에서 받아 Firestore 에 저장한다.
     * 그래프(폰·PC 웹)는 저장된 값만 쓰므로 보기 단위를 바꿔도 조회하지 않는다. 30분에 한 번 확인하고, [force] 면 바로.
     * 이미 받은 기간은 건너뛰므로 처음 한 번 이후에는 새로 생긴 날짜만 받는다.
     */
    fun syncHistory(force: Boolean = false) {
        val current = data.value ?: return
        val status = apiStatus.value
        if (!status.hasKis && !status.hasExim) return
        if (historyJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force && now - historyCheckedAt < HISTORY_INTERVAL_MS) return
        historyCheckedAt = now
        historyJob = scope.launch {
            loading.value = true
            val found = mutableListOf<String>()
            val today = LocalDate.now()
            runCatching {
                if (status.hasKis) {
                    found += marketRepository.ensureHistory(HistoryRanges.prices(current.holdings, current.records, today))
                }
                HistoryRanges.fx(current.holdings, current.records, today)?.let { (from, _) ->
                    // 한투 원/달러 종가가 기본, 수출입은행은 한투 구간 이전 날짜만 (v2.8)
                    if (status.hasKis) found += marketRepository.backfillKisFx(from)
                    val (used, fxErrors) = marketRepository.backfillFx(from, prefs.fxBudgetLeft())
                    prefs.spendFxBudget(used)
                    found += fxErrors
                }
            }.onFailure { found += "과거 시세를 받지 못했습니다." }
            errors.value = found.distinct()
            loading.value = false
            // 받은 시세로 그래프 일별 값을 다시 계산
            trigger.tryEmit(Unit)
        }
    }

    /** 기록을 저장한 뒤 (과거 날짜 매수 등으로) 새로 필요해진 과거 시세를 받는다. 저장이 화면 데이터에 반영될 때까지 잠깐 기다린다. */
    fun syncHistorySoon() {
        scope.launch {
            delay(HISTORY_AFTER_SAVE_MS)
            syncHistory(force = true)
        }
    }

    /**
     * 해외 종목의 매매·배당 날짜 환율을 받아 둔다 (원화 보기의 환차익 계산용). 이미 받은 날짜는 다시 묻지 않는다.
     * 받은 환율은 Firestore 에 저장되어 PC 웹에서도 쓴다.
     */
    fun ensureTradeFx() {
        val current = data.value ?: return
        if (!apiStatus.value.hasExim) return
        val usdHoldings = current.holdings.filter { it.currency == Currency.USD }.map { it.id }.toSet()
        val dates = current.records
            .filter { it.holdingId in usdHoldings && (it.type == RecordType.BUY || it.type == RecordType.SELL || it.type == RecordType.DIVIDEND) }
            .map { it.date }
            .distinct()
        if (dates.isEmpty()) return
        scope.launch { runCatching { marketRepository.ensureFx(dates) } }
    }

    private companion object {
        const val HISTORY_INTERVAL_MS = 30 * 60 * 1000L
        const val HISTORY_AFTER_SAVE_MS = 3_000L
        /** 기록·시세가 잇달아 바뀔 때 그래프 계산을 한 번만 하도록 기다리는 시간. */
        const val CHART_DEBOUNCE_MS = 2_000L
        const val TAG = "PortfolioViewModel"
    }
}
