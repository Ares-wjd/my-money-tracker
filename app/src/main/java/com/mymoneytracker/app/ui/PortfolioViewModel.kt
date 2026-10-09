package com.mymoneytracker.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.mymoneytracker.app.data.ApiException
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
import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.portfolio.PortfolioSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    fun account(id: String): InvestmentAccount? = accounts.firstOrNull { it.id == id }
    fun holding(id: String): Holding? = holdings.firstOrNull { it.id == id }
    fun record(id: String): Record? = records.firstOrNull { it.id == id }
    fun accountSummary(id: String) = summary.accounts.firstOrNull { it.account.id == id }
    fun holdingsOf(accountId: String): List<Holding> = holdings.filter { it.accountId == accountId }

    /** 계좌의 기록 (이 계좌가 받는 이체 포함), 최신순. */
    fun recordsOf(accountId: String): List<Record> =
        records.filter { it.accountId == accountId || it.toAccountId == accountId }
            .sortedWith(PortfolioCalculator.recordOrder.reversed())

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
    val rangeFactor: Int = 1,
)

data class ChartState(
    val selection: ChartSelection = ChartSelection(),
    val points: List<ChartPoint> = emptyList(),
    val hasEarlier: Boolean = false,
    val loading: Boolean = false,
    val errors: List<String> = emptyList(),
)

/** 로그인한 사용자 한 명의 투자 데이터·시세·그래프·한투 연결을 화면들에 공급한다. */
class PortfolioViewModel(application: Application, uid: String) : AndroidViewModel(application) {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val store = SecureStore(application)
    private val kis = KisClient(store)
    private val exim = EximClient(store)
    private val firestore = FirebaseFirestore.getInstance()
    private val repository = PortfolioRepository(firestore, uid) { _message.value = it }
    private val marketRepository = MarketDataRepository(firestore, uid, kis, exim)
    private val syncService = KisSyncService(kis, repository)

    /** null 이면 아직 첫 데이터를 받기 전(로딩 중). */
    val data: StateFlow<PortfolioData?> = combine(
        repository.accounts(),
        repository.holdings(),
        repository.records(),
        repository.settings(),
        marketRepository.snapshot(),
    ) { accounts, holdings, records, settings, market ->
        val fx = market.latestUsdKrw()?.second ?: settings.manualUsdKrw
        PortfolioData(
            accounts = accounts,
            holdings = holdings,
            records = records,
            settings = settings,
            market = market,
            summary = PortfolioCalculator.summarize(accounts, holdings, records, fx, priceOf = { PriceLookup.current(it, market) }),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val summary: StateFlow<PortfolioSummary?> = data.map { it?.summary }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _apiStatus = MutableStateFlow(readApiStatus())
    val apiStatus: StateFlow<ApiStatus> = _apiStatus.asStateFlow()

    private val _refresh = MutableStateFlow(RefreshState())
    val refresh: StateFlow<RefreshState> = _refresh.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val chartSelection = MutableStateFlow(ChartSelection())
    private val chartLoading = MutableStateFlow(false)
    private val chartErrors = MutableStateFlow<List<String>>(emptyList())
    private var chartJob: Job? = null

    val chart: StateFlow<ChartState> = combine(data, chartSelection, chartLoading, chartErrors) { current, selection, loading, errors ->
        if (current == null) return@combine ChartState(selection, loading = true)
        val today = LocalDate.now()
        val targetRecords = current.records.filter {
            selection.accountId == null || it.accountId == selection.accountId || it.toAccountId == selection.accountId
        }
        val first = targetRecords.minOfOrNull { it.date } ?: return@combine ChartState(selection, loading = loading, errors = errors)
        val dates = ChartCalculator.pointDates(selection.interval, today, first, selection.rangeFactor)
        ChartState(
            selection = selection,
            points = ChartCalculator.series(
                dates, today, current.accounts, current.holdings, current.records, current.market,
                current.settings.manualUsdKrw, selection.accountId,
            ),
            hasEarlier = ChartCalculator.hasEarlier(selection.interval, today, first, selection.rangeFactor),
            loading = loading,
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
            loadChartHistory()
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

    fun saveRecord(record: Record) = repository.saveRecord(record)

    fun deleteRecord(recordId: String) = repository.deleteRecord(recordId)

    fun saveManualUsdKrw(rate: Double?) = repository.saveManualUsdKrw(rate)

    // ---------------------------------------------------------------- 시세

    fun refreshQuotes(silent: Boolean = false) {
        val current = data.value ?: return
        if (_refresh.value.running) return
        _refresh.value = _refresh.value.copy(running = true)
        viewModelScope.launch {
            val held = current.summary.accounts.flatMap { a -> a.holdings.filter { it.position.quantity > 0 }.map { it.holding } }
            val needsFx = current.summary.accounts.any { a ->
                a.cash.usd != 0.0 || a.holdings.any { it.holding.currency == Currency.USD && it.position.quantity > 0 }
            }
            val errors = if (!_apiStatus.value.hasKis && !_apiStatus.value.hasExim && silent) {
                emptyList()
            } else {
                runCatching { marketRepository.refreshLatest(held, needsFx) }.getOrElse { listOf("시세 새로고침 실패") }
            }
            _refresh.value = RefreshState(running = false, lastRefreshedAt = System.currentTimeMillis(), errors = errors)
            if (!silent && errors.isEmpty()) _message.value = "시세와 환율을 새로 받았습니다."
        }
    }

    // ---------------------------------------------------------------- 그래프

    fun selectChartInterval(interval: ChartInterval) {
        chartSelection.value = chartSelection.value.copy(interval = interval, rangeFactor = 1)
        loadChartHistory()
    }

    fun selectChartAccount(accountId: String?) {
        chartSelection.value = chartSelection.value.copy(accountId = accountId)
        loadChartHistory()
    }

    fun showEarlierChart() {
        chartSelection.value = chartSelection.value.copy(rangeFactor = chartSelection.value.rangeFactor + 1)
        loadChartHistory()
    }

    /** 그래프 기간의 과거 종가·환율을 받아 둔다. 받은 값은 캐시되어 그래프가 자동으로 다시 계산된다. */
    private fun loadChartHistory() {
        val current = data.value ?: return
        if (!_apiStatus.value.hasKis && !_apiStatus.value.hasExim) return
        val selection = chartSelection.value
        chartJob?.cancel()
        chartJob = viewModelScope.launch {
            val today = LocalDate.now()
            val targetRecords = current.records.filter {
                selection.accountId == null || it.accountId == selection.accountId || it.toAccountId == selection.accountId
            }
            val first = targetRecords.minOfOrNull { it.date } ?: return@launch
            val dates = ChartCalculator.pointDates(selection.interval, today, first, selection.rangeFactor)
            val holdingIds = targetRecords.mapNotNull { it.holdingId }.toSet()
            val holdings = current.holdings.filter { it.id in holdingIds && MarketKeys.quotable(it) }
            val needsFx = targetRecords.any { it.currency == Currency.USD } ||
                holdings.any { it.currency == Currency.USD }
            chartLoading.value = true
            val errors = mutableListOf<String>()
            runCatching {
                if (holdings.isNotEmpty() && _apiStatus.value.hasKis) {
                    errors += marketRepository.ensurePriceHistory(holdings, dates.first().minusDays(7), today)
                }
                if (needsFx) errors += marketRepository.ensureFx(dates)
            }.onFailure { errors += "과거 시세를 받지 못했습니다." }
            chartErrors.value = errors.distinct()
            chartLoading.value = false
        }
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
                _apiStatus.value = readApiStatus()
                buildString {
                    append("한투에서 체결 ${result.importedTrades}건을 불러왔습니다.")
                    if (result.createdHoldings > 0) append(" 새 종목 ${result.createdHoldings}개.")
                    if (result.initialPositions > 0) append(" 초기 보유 ${result.initialPositions}개.")
                    if (result.quantityAdjustments > 0) append(" 잔고 기준 수량 맞춤 ${result.quantityAdjustments}건.")
                    result.cashAdjusted?.let { append(" 예수금 ${MoneyFormat.signedWon(it)} 맞춤.") }
                    if (result.warnings.isNotEmpty()) append("\n" + result.warnings.joinToString("\n"))
                }
            } catch (e: ApiException) {
                e.message
            } catch (e: Exception) {
                "한투 불러오기 실패: 인터넷 연결을 확인하세요."
            }
            _syncing.value = false
            refreshQuotes(silent = true)
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
        fun factory(uid: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { PortfolioViewModel(this[APPLICATION_KEY]!!, uid) }
        }
    }
}
