package com.mymoneytracker.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.mymoneytracker.app.data.ApiException
import com.mymoneytracker.app.data.ChartRepository
import com.mymoneytracker.app.data.AppSettings
import com.mymoneytracker.app.data.EximClient
import com.mymoneytracker.app.data.KisClient
import com.mymoneytracker.app.data.KisSyncService
import com.mymoneytracker.app.data.MarketDataRepository
import com.mymoneytracker.app.data.PortfolioRepository
import com.mymoneytracker.app.data.SecureStore
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.chart.ChartCalculator
import com.mymoneytracker.core.chart.ChartInterval
import com.mymoneytracker.core.chart.ChartPoint
import com.mymoneytracker.core.chart.ChartStore
import com.mymoneytracker.core.chart.StoredChart
import com.mymoneytracker.core.market.HistoryRanges
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.DisplayCurrency
import com.mymoneytracker.core.portfolio.AllocationTarget
import com.mymoneytracker.core.portfolio.HoldingSort
import com.mymoneytracker.core.portfolio.HoldingValuation
import com.mymoneytracker.core.portfolio.KrwHoldingCalculator
import com.mymoneytracker.core.portfolio.KrwHoldingView
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.portfolio.PortfolioSummary
import com.mymoneytracker.core.portfolio.TodayChange
import com.mymoneytracker.core.portfolio.TodayChangeCalculator
import com.mymoneytracker.core.portfolio.TodayFilter
import com.mymoneytracker.core.portfolio.TodayKind
import com.mymoneytracker.core.portfolio.TradeImport
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 화면들이 공유하는 투자 데이터 전체. */
data class PortfolioData(
    val accounts: List<InvestmentAccount>,
    val holdings: List<Holding>,
    val records: List<Record>,
    val settings: AppSettings,
    val market: MarketSnapshot,
    val summary: PortfolioSummary,
) {
    /** 계산에 쓰는 환율: 자동으로 받은 최신 환율, 없으면 직접 입력한 환율. */
    val usdKrw: Double? get() = market.latestUsdKrw()?.second ?: settings.manualUsdKrw
    val usdKrwDate: LocalDate? get() = market.latestUsdKrw()?.first
    /** 환율 출처 표시 ("한투" / "수출입은행", 과거 목록 값이면 null). */
    val usdKrwSource: String? get() = market.latestUsdKrwSourceLabel()

    fun account(id: String): InvestmentAccount? = accounts.firstOrNull { it.id == id }
    fun holding(id: String): Holding? = holdings.firstOrNull { it.id == id }
    fun record(id: String): Record? = records.firstOrNull { it.id == id }
    fun accountSummary(id: String) = summary.accounts.firstOrNull { it.account.id == id }
    fun holdingsOf(accountId: String): List<Holding> = holdings.filter { it.accountId == accountId }

    /** 계좌의 기록 (이 계좌가 받는 이체 포함), 최신순. */
    fun recordsOf(accountId: String): List<Record> =
        records.filter { it.accountId == accountId || it.toAccountId == accountId }
            .sortedWith(PortfolioCalculator.recordOrder.reversed())

    /** 오늘 수익률 (전일 종가 대비). 자동 시세를 쓰지 않거나 전일 종가가 없으면 null. */
    fun todayRate(valuation: HoldingValuation): Double? = PriceLookup.dailyChangeRate(valuation.holding, market)

    /** 원화 보기의 해외 종목 숫자 (매매일 환율로 환차익 반영). 매매일 환율이 하나라도 없으면 null. */
    fun krwView(valuation: HoldingValuation): KrwHoldingView? =
        KrwHoldingCalculator.view(valuation, records, usdKrw) { market.tradeUsdKrw(it, LocalDate.now()) }

    fun recordsOfHolding(holdingId: String): List<Record> =
        records.filter { it.holdingId == holdingId }.sortedWith(PortfolioCalculator.recordOrder.reversed())
}

/** 기기에만 저장된 API 연결 정보 요약 (비밀 값은 앞부분만). */
data class ApiStatus(
    val kisKeyHint: String? = null,
    val kisServiceExpiry: LocalDate? = null,
    val eximKeyHint: String? = null,
    val linkedAccountNo: String? = null,
    val linkedProduct: String = "01",
    val linkedAccountId: String? = null,
    val syncStart: LocalDate? = null,
    val lastSync: LocalDate? = null,
) {
    val hasKis: Boolean get() = kisKeyHint != null
    val hasExim: Boolean get() = eximKeyHint != null

    /** KIS 서비스 만료까지 남은 날 (만료일을 입력한 경우). */
    fun daysUntilKisExpiry(today: LocalDate = LocalDate.now()): Long? =
        kisServiceExpiry?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }
}

data class RefreshState(
    val running: Boolean = false,
    val lastRefreshedAt: Long? = null,
    val errors: List<String> = emptyList(),
)

data class ChartSelection(
    val interval: ChartInterval = ChartInterval.MONTH,
    val accountId: String? = null,
)

/** [loading] 과거 시세를 받는 중, [computing] 일별 값을 계산하는 중 (또는 아직 계산하지 않은 날이 있음). */
data class ChartState(
    val selection: ChartSelection = ChartSelection(),
    val points: List<ChartPoint> = emptyList(),
    val loading: Boolean = false,
    val computing: Boolean = false,
    val errors: List<String> = emptyList(),
)

/** 로그인한 사용자 한 명의 투자 데이터·시세·그래프·한투 연결을 화면들에 공급한다. */
@OptIn(FlowPreview::class)
class PortfolioViewModel(application: Application, uid: String) : AndroidViewModel(application) {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val store = SecureStore(application)
    private val kis = KisClient(store)
    private val exim = EximClient(store)
    private val firestore = FirebaseFirestore.getInstance()
    private val repository = PortfolioRepository(firestore, uid) { _message.value = it }
    private val marketRepository = MarketDataRepository(firestore, uid, kis, exim)
    private val chartRepository = ChartRepository(firestore, uid)
    private val syncService = KisSyncService(kis, repository)

    private val _apiStatus = MutableStateFlow(readApiStatus())
    val apiStatus: StateFlow<ApiStatus> = _apiStatus.asStateFlow()

    private val baseData = combine(repository.accounts(), repository.holdings(), repository.records()) { a, h, r -> Triple(a, h, r) }

    /** null 이면 아직 첫 데이터를 받기 전(로딩 중). */
    val data: StateFlow<PortfolioData?> = combine(
        baseData,
        repository.settings(),
        marketRepository.snapshot(),
    ) { (accounts, holdings, records), settings, market ->
        val fx = market.latestUsdKrw()?.second ?: settings.manualUsdKrw
        PortfolioData(
            accounts = accounts,
            holdings = holdings,
            records = records,
            settings = settings,
            market = market,
            summary = PortfolioCalculator.summarize(
                accounts, holdings, records, fx,
                priceOf = { PriceLookup.current(it, market) },
            ),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val summary: StateFlow<PortfolioSummary?> = data.map { it?.summary }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 오늘 변동 (홈 카드, 항상 전체). */
    val todayChange: StateFlow<TodayChange?> = data.map { current ->
        current?.let { TodayChangeCalculator.calculate(it.summary, it.market, LocalDate.now()) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _refresh = MutableStateFlow(RefreshState())
    val refresh: StateFlow<RefreshState> = _refresh.asStateFlow()

    /** 해외 종목 금액 보기 (원화 / 외화). 이 기기에만 기억한다. */
    private val displayPrefs = application.getSharedPreferences("display", android.content.Context.MODE_PRIVATE)
    private val _displayCurrency = MutableStateFlow(
        DisplayCurrency.entries.firstOrNull { it.name == displayPrefs.getString(KEY_DISPLAY_CURRENCY, null) } ?: DisplayCurrency.FOREIGN,
    )
    val displayCurrency: StateFlow<DisplayCurrency> = _displayCurrency.asStateFlow()

    fun setDisplayCurrency(mode: DisplayCurrency) {
        _displayCurrency.value = mode
        displayPrefs.edit().putString(KEY_DISPLAY_CURRENCY, mode.name).apply()
    }

    /** 보유 종목 정렬 기준 (평가금 / 총 수익률 / 오늘 수익률). 이 기기에만 기억한다. */
    private val _holdingSort = MutableStateFlow(
        HoldingSort.entries.firstOrNull { it.name == displayPrefs.getString(KEY_HOLDING_SORT, null) } ?: HoldingSort.VALUE,
    )
    val holdingSort: StateFlow<HoldingSort> = _holdingSort.asStateFlow()

    fun setHoldingSort(sort: HoldingSort) {
        _holdingSort.value = sort
        displayPrefs.edit().putString(KEY_HOLDING_SORT, sort.name).apply()
    }

    /** 비중 설정들. */
    val allocationTargets: StateFlow<List<AllocationTarget>?> = repository.allocationTargets()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveAllocationTarget(target: AllocationTarget): String = repository.saveAllocationTarget(target)

    fun deleteAllocationTarget(id: String) = repository.deleteAllocationTarget(id)

    /** 비중 탭에서 마지막으로 본 설정·도넛 보기. 이 기기에만 기억한다. */
    private val _selectedAllocation = MutableStateFlow(displayPrefs.getString(KEY_ALLOCATION, null))
    val selectedAllocation: StateFlow<String?> = _selectedAllocation.asStateFlow()

    fun selectAllocation(id: String) {
        _selectedAllocation.value = id
        displayPrefs.edit().putString(KEY_ALLOCATION, id).apply()
    }

    private val _allocationRing = MutableStateFlow(displayPrefs.getString(KEY_ALLOCATION_RING, null) ?: "COMPARE")
    val allocationRing: StateFlow<String> = _allocationRing.asStateFlow()

    fun setAllocationRing(ring: String) {
        _allocationRing.value = ring
        displayPrefs.edit().putString(KEY_ALLOCATION_RING, ring).apply()
    }

    /** 비중 매수 계산기에서 현재 예수금도 함께 쓸지 (기본 켬). 이 기기에만 기억한다. */
    private val _allocationUseCash = MutableStateFlow(displayPrefs.getBoolean(KEY_ALLOCATION_USE_CASH, true))
    val allocationUseCash: StateFlow<Boolean> = _allocationUseCash.asStateFlow()

    fun setAllocationUseCash(use: Boolean) {
        _allocationUseCash.value = use
        displayPrefs.edit().putBoolean(KEY_ALLOCATION_USE_CASH, use).apply()
    }

    /** 계좌별 기록 유형 필터 (뺀 유형, 기본은 전부 보임). 이 기기에만 기억한다. */
    private val _recordHidden = MutableStateFlow(
        displayPrefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_RECORD_HIDDEN)) return@mapNotNull null
            val types = (value as? String).orEmpty().split(",").mapNotNull { name -> RecordType.entries.firstOrNull { it.name == name } }.toSet()
            key.removePrefix(KEY_RECORD_HIDDEN) to types
        }.toMap(),
    )
    val recordHidden: StateFlow<Map<String, Set<RecordType>>> = _recordHidden.asStateFlow()

    fun setRecordHidden(accountId: String, hidden: Set<RecordType>) {
        _recordHidden.value = _recordHidden.value + (accountId to hidden)
        displayPrefs.edit().putString(KEY_RECORD_HIDDEN + accountId, hidden.joinToString(",") { it.name }).apply()
    }

    /** 오늘 변동 상세 화면의 필터 (뺀 계좌·종류, 기본은 전부 보임). 이 기기에만 기억한다. */
    private val _todayFilter = MutableStateFlow(
        TodayFilter(
            hiddenAccounts = displayPrefs.getString(KEY_TODAY_HIDDEN_ACCOUNTS, null).orEmpty().split(",").filter { it.isNotEmpty() }.toSet(),
            hiddenKinds = displayPrefs.getString(KEY_TODAY_HIDDEN_KINDS, null).orEmpty().split(",")
                .mapNotNull { name -> TodayKind.entries.firstOrNull { it.name == name } }.toSet(),
        ),
    )
    val todayFilter: StateFlow<TodayFilter> = _todayFilter.asStateFlow()

    fun setTodayFilter(filter: TodayFilter) {
        _todayFilter.value = filter
        displayPrefs.edit()
            .putString(KEY_TODAY_HIDDEN_ACCOUNTS, filter.hiddenAccounts.joinToString(","))
            .putString(KEY_TODAY_HIDDEN_KINDS, filter.hiddenKinds.joinToString(",") { it.name })
            .apply()
    }

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val chartSelection = MutableStateFlow(ChartSelection())
    private val chartLoading = MutableStateFlow(false)
    private val chartErrors = MutableStateFlow<List<String>>(emptyList())
    private var historyJob: Job? = null
    private var historyCheckedAt = 0L

    /** 미리 계산해 저장한 그래프 일별 값 (계좌 id → 값). null 이면 아직 읽기 전. 기간별 수익 계산에도 쓴다. */
    val storedCharts: StateFlow<Map<String, StoredChart>?> = chartRepository.stored()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _chartComputing = MutableStateFlow(false)
    /** 그래프 일별 값을 계산하는 중 (설정의 [그래프 전체 다시 계산] 표시용). */
    val chartComputing: StateFlow<Boolean> = _chartComputing.asStateFlow()
    private val chartTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var forceChart = false

    /** 그래프: 저장된 일별 값 + 현재가로 바로 계산한 오늘 값 (모든 보기 단위가 전체 기간). */
    val chart: StateFlow<ChartState> = combine(
        combine(data, storedCharts, _chartComputing, ::Triple),
        chartSelection,
        chartLoading,
        chartErrors,
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChartState())

    init {
        // 앱을 열면 첫 데이터가 온 뒤 한 번 시세·환율을 새로 받고, 그래프에 필요한 과거 시세를 채운다.
        viewModelScope.launch {
            data.filterNotNull().first()
            refreshQuotes(silent = true)
            ensureTradeFx()
        }
        // 기록·종목·시세·저장값이 바뀌면 (잠깐 기다렸다가) 다시 계산할 범위를 찾아 계산한다. 한 번에 하나씩.
        viewModelScope.launch {
            merge(data.filterNotNull().map { }, storedCharts.filterNotNull().map { }, chartTrigger)
                .debounce(CHART_DEBOUNCE_MS)
                .collect { runCatching { computeCharts() }.onFailure { Log.w(TAG, "그래프 계산 실패", it) } }
        }
    }

    // ---------------------------------------------------------------- 기록

    fun saveAccount(account: InvestmentAccount): String = repository.saveAccount(account)

    fun deleteAccount(accountId: String) {
        val current = data.value ?: return
        repository.deleteAccount(accountId, current.holdings, current.records)
        if (_apiStatus.value.linkedAccountId == accountId) {
            store.put(SecureStore.KIS_LINKED_ACCOUNT_ID, null)
            store.put(SecureStore.KIS_LAST_SYNC, null)
            _apiStatus.value = readApiStatus()
        }
    }

    fun saveHolding(holding: Holding): String = repository.saveHolding(holding)

    fun deleteHolding(holdingId: String) {
        val current = data.value ?: return
        repository.deleteHolding(holdingId, current.records)
    }

    fun saveRecord(record: Record) {
        repository.saveRecord(record)
        syncHistorySoon()
    }

    /**
     * 표로 일괄 입력 저장: 새 종목([TradeImport.NewHolding]) → 매매 기록. 저장한 기록 수를 돌려준다.
     */
    fun saveImport(accountId: String, analysis: TradeImport.Analysis): Int {
        val newHoldings = analysis.newHoldings.associate { n ->
            n.key to Holding(
                accountId = accountId,
                name = n.name,
                code = n.code,
                market = if (n.assetType == AssetType.GOLD) Market.KR else n.market,
                assetType = n.assetType,
            )
        }
        // 새 종목 기록은 holdingId 에 키를 넣어 두고, 저장할 때 새 종목 id 로 바꾼다
        val records = TradeImport.build(analysis.rows, accountId) { key -> key }.map { it.second }
        repository.importTrades(newHoldings.filterKeys { key -> records.any { it.holdingId == key } }, records)
        _message.value = "${records.size}건을 저장했습니다."
        syncHistorySoon()
        return records.size
    }

    /** 표로 일괄 입력 저장 (입출금·배당): 기록을 한꺼번에 저장한다. */
    fun saveImportedRecords(records: List<Record>) {
        if (records.isEmpty()) return
        repository.importTrades(emptyMap(), records)
        _message.value = "${records.size}건을 저장했습니다."
        syncHistorySoon()
    }

    fun deleteRecord(recordId: String) = repository.deleteRecord(recordId)

    fun saveManualUsdKrw(rate: Double?) = repository.saveManualUsdKrw(rate)

    /** 직접 입력한 예수금을 기록한다. 같은 날 다시 입력하면 그날 값을 덮어쓴다. */
    fun saveCashBalance(accountId: String, date: LocalDate, krw: Double?, usd: Double?, memo: String) {
        listOfNotNull(krw?.let { Currency.KRW to it }, usd?.let { Currency.USD to it }).forEach { (currency, value) ->
            repository.saveRecord(cashBalanceRecord("cash", accountId, date, currency, value, memo, externalId = null))
        }
    }

    /**
     * 한투 예수금을 연결 계좌의 예수금 기록으로 남긴다. 하루에 한 번은 꼭 기록하고(그날 첫 새로고침), 그 뒤에는 바뀌었을 때만
     * 그날 값을 덮어쓴다 (1분 새로고침마다 쓰지 않도록). 과거 예수금은 API 로 받을 수 없어서 매일 쌓아 둔다.
     */
    private fun saveBrokerCash(accountId: String, krw: Double?, usd: Double?) {
        val current = data.value ?: return
        val cash = current.accountSummary(accountId)?.cash ?: return
        val today = LocalDate.now()
        listOfNotNull(krw?.let { Currency.KRW to it }, usd?.let { Currency.USD to it }).forEach { (currency, value) ->
            val record = cashBalanceRecord("kis_cash", accountId, today, currency, value, "한투에서 불러옴", externalId = "KIS:balance")
            val recordedToday = current.record(record.id) != null
            if (!recordedToday || kotlin.math.abs(cash[currency] - record.amount) >= 0.005) repository.saveRecord(record)
        }
    }

    private fun cashBalanceRecord(
        prefix: String,
        accountId: String,
        date: LocalDate,
        currency: Currency,
        value: Double,
        memo: String,
        externalId: String?,
    ) = Record(
        id = "${prefix}_${accountId}_${date.toString().replace("-", "")}_${currency.name}".replace(Regex("[^A-Za-z0-9_\\-]"), "_"),
        accountId = accountId,
        type = RecordType.CASH_BALANCE,
        date = date,
        currency = currency,
        amount = if (currency == Currency.KRW) Math.round(value).toDouble() else Math.round(value * 100) / 100.0,
        externalId = externalId,
        memo = memo,
        createdAt = System.currentTimeMillis(),
    )

    // ---------------------------------------------------------------- 시세

    /**
     * 보유 종목 현재가와 환율을 새로 받는다. 한투 연결 계좌가 있으면 잔고 조회로 예수금·현재가도 갱신한다.
     * @param silent 자동 새로고침이면 성공 메시지를 띄우지 않는다.
     */
    fun refreshQuotes(silent: Boolean = false) {
        val current = data.value ?: return
        if (_refresh.value.running || _syncing.value) return
        val status = _apiStatus.value
        if (silent && !status.hasKis && !status.hasExim) return
        _refresh.value = _refresh.value.copy(running = true)
        viewModelScope.launch {
            val errors = mutableListOf<String>()
            val linked = status.linkedAccountId?.takeIf { status.hasKis && !status.linkedAccountNo.isNullOrBlank() }
            if (linked != null && current.account(linked) != null) {
                try {
                    val snapshot = syncService.brokerSnapshot()
                    snapshot.prices.forEach { (key, price) -> marketRepository.saveLatest(key, price, persist = false) }
                    saveBrokerCash(linked, snapshot.cashKrw, snapshot.cashUsd)
                    errors += snapshot.warnings
                } catch (e: ApiException) {
                    errors += "한투 잔고: ${e.message}"
                } catch (e: Exception) {
                    errors += "한투 잔고: 네트워크 오류"
                }
            }
            // 연결 계좌 종목도 시세 조회를 한다 (전일 종가 → 오늘 수익률). 잔고 가격은 시세 조회가 실패할 때를 대비한 값.
            val held = current.summary.accounts.flatMap { a -> a.holdings.filter { it.position.quantity > 0 }.map { it.holding } }
            val needsFx = current.summary.accounts.any { a ->
                a.cash.usd != 0.0 || a.holdings.any { it.holding.currency == Currency.USD && it.position.quantity > 0 }
            }
            errors += runCatching { marketRepository.refreshLatest(held, needsFx, forceFx = !silent) }
                .getOrElse { listOf("시세 새로고침 실패") }
            _refresh.value = RefreshState(running = false, lastRefreshedAt = System.currentTimeMillis(), errors = errors.distinct())
            if (!silent && errors.isEmpty()) _message.value = "시세와 환율을 새로 받았습니다."
            // 새로고침 뒤 과거 시세도 확인 (30분에 한 번, 이미 받은 기간은 건너뜀)
            syncHistory()
        }
    }

    // ---------------------------------------------------------------- 그래프

    fun selectChartInterval(interval: ChartInterval) {
        chartSelection.value = chartSelection.value.copy(interval = interval)
    }

    fun selectChartAccount(accountId: String?) {
        chartSelection.value = chartSelection.value.copy(accountId = accountId)
    }

    /** 설정의 [그래프 전체 다시 계산]: 모든 계좌의 일별 값을 처음부터 다시 계산한다. */
    fun recomputeCharts() {
        forceChart = true
        chartTrigger.tryEmit(Unit)
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
        val force = forceChart
        forceChart = false
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
        _chartComputing.value = true
        try {
            for ((account, plan) in plans) {
                val computed = withContext(Dispatchers.Default) {
                    ChartStore.compute(plan, account, current.holdings, current.records, current.market, current.settings.manualUsdKrw)
                }
                chartRepository.save(account.id, ChartStore.build(plan, computed))
            }
        } finally {
            _chartComputing.value = false
        }
    }

    /**
     * 보유했던 기간의 과거 종가와 해외 종목·달러가 있던 기간의 환율을 뒤에서 받아 Firestore 에 저장한다.
     * 그래프(폰·PC 웹)는 저장된 값만 쓰므로 보기 단위를 바꿔도 조회하지 않는다. 30분에 한 번 확인하고, [force] 면 바로.
     * 이미 받은 기간은 건너뛰므로 처음 한 번 이후에는 새로 생긴 날짜만 받는다.
     */
    private fun syncHistory(force: Boolean = false) {
        val current = data.value ?: return
        val status = _apiStatus.value
        if (!status.hasKis && !status.hasExim) return
        if (historyJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force && now - historyCheckedAt < HISTORY_INTERVAL_MS) return
        historyCheckedAt = now
        historyJob = viewModelScope.launch {
            chartLoading.value = true
            val errors = mutableListOf<String>()
            val today = LocalDate.now()
            runCatching {
                if (status.hasKis) {
                    errors += marketRepository.ensureHistory(HistoryRanges.prices(current.holdings, current.records, today))
                }
                HistoryRanges.fx(current.holdings, current.records, today)?.let { (from, _) ->
                    // 한투 원/달러 종가가 기본, 수출입은행은 한투 구간 이전 날짜만 (v2.8)
                    if (status.hasKis) errors += marketRepository.backfillKisFx(from)
                    val (used, fxErrors) = marketRepository.backfillFx(from, fxBudgetLeft())
                    spendFxBudget(used)
                    errors += fxErrors
                }
            }.onFailure { errors += "과거 시세를 받지 못했습니다." }
            chartErrors.value = errors.distinct()
            chartLoading.value = false
            // 받은 시세로 그래프 일별 값을 다시 계산
            chartTrigger.tryEmit(Unit)
        }
    }

    /** 오늘 과거 환율 채우기에 더 쓸 수 있는 수출입은행 호출 수 (하루 1,000회 한도 중 [FX_DAILY_BUDGET]). */
    private fun fxBudgetLeft(): Int {
        val today = LocalDate.now().toString()
        val used = if (displayPrefs.getString(KEY_FX_DAY, null) == today) displayPrefs.getInt(KEY_FX_CALLS, 0) else 0
        return (FX_DAILY_BUDGET - used).coerceAtLeast(0)
    }

    private fun spendFxBudget(calls: Int) {
        if (calls <= 0) return
        val today = LocalDate.now().toString()
        val used = if (displayPrefs.getString(KEY_FX_DAY, null) == today) displayPrefs.getInt(KEY_FX_CALLS, 0) else 0
        displayPrefs.edit().putString(KEY_FX_DAY, today).putInt(KEY_FX_CALLS, used + calls).apply()
    }

    /** 기록을 저장한 뒤 (과거 날짜 매수 등으로) 새로 필요해진 과거 시세를 받는다. 저장이 화면 데이터에 반영될 때까지 잠깐 기다린다. */
    private fun syncHistorySoon() {
        viewModelScope.launch {
            delay(HISTORY_AFTER_SAVE_MS)
            syncHistory(force = true)
        }
    }

    /**
     * 해외 종목의 매매·배당 날짜 환율을 받아 둔다 (원화 보기의 환차익 계산용). 이미 받은 날짜는 다시 묻지 않는다.
     * 받은 환율은 Firestore 에 저장되어 PC 웹에서도 쓴다.
     */
    private fun ensureTradeFx() {
        val current = data.value ?: return
        if (!_apiStatus.value.hasExim) return
        val usdHoldings = current.holdings.filter { it.currency == Currency.USD }.map { it.id }.toSet()
        val dates = current.records
            .filter { it.holdingId in usdHoldings && (it.type == RecordType.BUY || it.type == RecordType.SELL || it.type == RecordType.DIVIDEND) }
            .map { it.date }
            .distinct()
        if (dates.isEmpty()) return
        viewModelScope.launch { runCatching { marketRepository.ensureFx(dates) } }
    }

    // ---------------------------------------------------------------- API 설정

    private fun readApiStatus(): ApiStatus = ApiStatus(
        kisKeyHint = store.get(SecureStore.KIS_APP_KEY)?.let(::hint),
        kisServiceExpiry = store.get(SecureStore.KIS_SERVICE_EXPIRY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        eximKeyHint = store.get(SecureStore.EXIM_KEY)?.let(::hint),
        linkedAccountNo = store.get(SecureStore.KIS_ACCOUNT_NO),
        linkedProduct = store.get(SecureStore.KIS_ACCOUNT_PRODUCT) ?: "01",
        linkedAccountId = store.get(SecureStore.KIS_LINKED_ACCOUNT_ID),
        syncStart = store.get(SecureStore.KIS_SYNC_START)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        lastSync = store.get(SecureStore.KIS_LAST_SYNC)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    )

    private fun hint(value: String): String = if (value.length <= 4) "••••" else value.take(4) + "••••"

    fun saveKisCredentials(appKey: String, appSecret: String) {
        store.put(SecureStore.KIS_APP_KEY, appKey.trim())
        store.put(SecureStore.KIS_APP_SECRET, appSecret.trim())
        kis.clearToken()
        _apiStatus.value = readApiStatus()
        _message.value = "한국투자증권 API 키를 이 기기에 저장했습니다."
    }

    fun saveKisServiceExpiry(date: LocalDate?) {
        store.put(SecureStore.KIS_SERVICE_EXPIRY, date?.toString())
        _apiStatus.value = readApiStatus()
    }

    fun clearKisCredentials() {
        listOf(
            SecureStore.KIS_APP_KEY, SecureStore.KIS_APP_SECRET, SecureStore.KIS_TOKEN, SecureStore.KIS_TOKEN_EXPIRES_AT,
        ).forEach { store.put(it, null) }
        _apiStatus.value = readApiStatus()
    }

    fun saveEximKey(key: String) {
        store.put(SecureStore.EXIM_KEY, key.trim())
        _apiStatus.value = readApiStatus()
        _message.value = "환율 인증키를 이 기기에 저장했습니다."
    }

    fun clearEximKey() {
        store.put(SecureStore.EXIM_KEY, null)
        _apiStatus.value = readApiStatus()
    }

    fun testKis() {
        viewModelScope.launch {
            _message.value = try {
                val price = kis.domesticPrice("005930")
                "한투 연결 성공: 삼성전자 현재가 ${MoneyFormat.won(price)}"
            } catch (e: ApiException) {
                e.message
            } catch (e: Exception) {
                "한투 연결 실패: 인터넷 연결을 확인하세요."
            }
        }
    }

    fun testExim() {
        viewModelScope.launch {
            _message.value = try {
                var day = LocalDate.now()
                var rate: Double? = null
                repeat(7) {
                    if (rate == null) {
                        rate = exim.usdKrw(day)
                        if (rate == null) day = day.minusDays(1)
                    }
                }
                rate?.let { "환율 연결 성공: $day 기준 1달러 = ${MoneyFormat.decimal(it, 2)}원" } ?: "최근 7일 환율 데이터가 없습니다."
            } catch (e: ApiException) {
                e.message
            } catch (e: Exception) {
                "환율 연결 실패: 인터넷 연결을 확인하세요."
            }
        }
    }

    // ---------------------------------------------------------------- 한투 계좌 연결 (M5)

    fun saveKisLink(accountNo: String, product: String, linkedAccountId: String?, syncStart: LocalDate) {
        val previous = _apiStatus.value
        store.put(SecureStore.KIS_ACCOUNT_NO, accountNo.filter { it.isDigit() })
        store.put(SecureStore.KIS_ACCOUNT_PRODUCT, product.filter { it.isDigit() }.ifBlank { "01" })
        store.put(SecureStore.KIS_LINKED_ACCOUNT_ID, linkedAccountId)
        store.put(SecureStore.KIS_SYNC_START, syncStart.toString())
        if (previous.linkedAccountId != linkedAccountId || previous.linkedAccountNo != accountNo || previous.syncStart != syncStart) {
            store.put(SecureStore.KIS_LAST_SYNC, null)
        }
        _apiStatus.value = readApiStatus()
        _message.value = "연결 계좌 정보를 이 기기에 저장했습니다."
    }

    fun syncKisAccount() {
        val current = data.value ?: return
        val status = _apiStatus.value
        val account = status.linkedAccountId?.let { current.account(it) }
        if (account == null || status.linkedAccountNo.isNullOrBlank() || status.syncStart == null) {
            _message.value = "설정에서 연결 계좌번호·앱 계좌·시작일을 먼저 저장하세요."
            return
        }
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            _message.value = try {
                val result = syncService.sync(account, current.holdings, current.records, status.syncStart, status.lastSync)
                store.put(SecureStore.KIS_LAST_SYNC, LocalDate.now().toString())
                result.currentPrices.forEach { (key, price) -> marketRepository.saveLatest(key, price, persist = false) }
                saveBrokerCash(account.id, result.brokerCashKrw, result.brokerCashUsd)
                buildString {
                    append("한투에서 체결 ${result.importedTrades}건을 불러왔습니다.")
                    if (result.createdHoldings > 0) append(" 새 종목 ${result.createdHoldings}개.")
                    if (result.initialPositions > 0) append(" 초기 보유 ${result.initialPositions}개.")
                    if (result.quantityAdjustments > 0) append(" 잔고 기준 수량 맞춤 ${result.quantityAdjustments}건.")
                    if (result.removedCashAdjustments > 0) {
                        append(" 예전에 자동으로 만든 예수금 조정 ${result.removedCashAdjustments}건을 지웠습니다.")
                    }
                    append("\n예수금도 한투 값으로 맞췄습니다. 입금·출금(투자금)은 직접 기록하세요.")
                    if (result.warnings.isNotEmpty()) append("\n" + result.warnings.joinToString("\n"))
                }
            } catch (e: ApiException) {
                e.message
            } catch (e: Exception) {
                "한투 불러오기 실패: 인터넷 연결을 확인하세요."
            }
            _syncing.value = false
            // 새로 만든 종목·기록이 화면 데이터에 반영된 뒤 나머지 시세·환율을 받는다.
            delay(1_500)
            refreshQuotes(silent = true)
            ensureTradeFx()
            syncHistory(force = true)
        }
    }

    /** 이 기기에 저장된 API 키·연결 정보를 모두 지운다 (탈퇴할 때). */
    fun clearLocalSecrets() {
        store.clearAll()
        _apiStatus.value = readApiStatus()
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun messageShown() {
        _message.value = null
    }

    companion object {
        private const val KEY_DISPLAY_CURRENCY = "holding_currency"
        private const val KEY_HOLDING_SORT = "holding_sort"
        private const val KEY_ALLOCATION = "allocation_selected"
        private const val KEY_ALLOCATION_RING = "allocation_ring"
        private const val KEY_ALLOCATION_USE_CASH = "allocation_use_cash"
        private const val KEY_RECORD_HIDDEN = "record_hidden_"
        private const val KEY_TODAY_HIDDEN_ACCOUNTS = "today_hidden_accounts"
        private const val KEY_TODAY_HIDDEN_KINDS = "today_hidden_kinds"
        private const val KEY_FX_DAY = "fx_backfill_day"
        private const val KEY_FX_CALLS = "fx_backfill_calls"
        /** 과거 환율 채우기에 하루에 쓸 수 있는 수출입은행 호출 수 (한도 1,000회 중 나머지는 최신·매매일 환율용). */
        private const val FX_DAILY_BUDGET = 600
        private const val HISTORY_INTERVAL_MS = 30 * 60 * 1000L
        private const val HISTORY_AFTER_SAVE_MS = 3_000L
        /** 기록·시세가 잇달아 바뀔 때 그래프 계산을 한 번만 하도록 기다리는 시간. */
        private const val CHART_DEBOUNCE_MS = 2_000L
        private const val TAG = "PortfolioViewModel"

        fun factory(uid: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { PortfolioViewModel(this[APPLICATION_KEY]!!, uid) }
        }
    }
}
