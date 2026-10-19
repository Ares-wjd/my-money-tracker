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
import com.mymoneytracker.app.data.ChartRepository
import com.mymoneytracker.app.data.EximClient
import com.mymoneytracker.app.data.KisClient
import com.mymoneytracker.app.data.KisSyncService
import com.mymoneytracker.app.data.MarketDataRepository
import com.mymoneytracker.app.data.PortfolioRepository
import com.mymoneytracker.app.data.SecureStore
import com.mymoneytracker.core.chart.ChartInterval
import com.mymoneytracker.core.chart.StoredChart
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.AllocationTarget
import com.mymoneytracker.core.portfolio.DataCheck
import com.mymoneytracker.core.portfolio.DisplayCurrency
import com.mymoneytracker.core.portfolio.HoldingSort
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.portfolio.PortfolioSummary
import com.mymoneytracker.core.portfolio.TodayChange
import com.mymoneytracker.core.portfolio.TodayChangeCalculator
import com.mymoneytracker.core.portfolio.TodayFilter
import com.mymoneytracker.core.portfolio.TradeImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/**
 * 로그인한 사용자 한 명의 투자 데이터·시세·그래프·한투 연결을 화면들에 공급한다.
 * 보기 설정은 [DisplayPrefs], 그래프·과거 시세는 [ChartSync], API 키는 [ApiSettings] 가 맡고 여기서는 이어 준다.
 * 화면 상태 묶음(PortfolioData 등)은 PortfolioState.kt.
 */
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

    private val api = ApiSettings(store, kis, exim, viewModelScope) { _message.value = it }
    val apiStatus: StateFlow<ApiStatus> = api.status

    private val baseData = combine(repository.accounts(), repository.holdings(), repository.records()) { a, h, r -> Triple(a, h, r) }

    /** null 이면 아직 첫 데이터를 받기 전(로딩 중). */
    val data: StateFlow<PortfolioData?> = combine(
        baseData,
        repository.settings(),
        marketRepository.snapshot(),
    ) { (accounts, holdings, records), settings, market ->
        val fx = market.latestUsdKrw()?.second ?: settings.manualUsdKrw
        val summary = PortfolioCalculator.summarize(
            accounts, holdings, records, fx,
            priceOf = { PriceLookup.current(it, market) },
        )
        PortfolioData(
            accounts = accounts,
            holdings = holdings,
            records = records,
            settings = settings,
            market = market,
            summary = summary,
            checks = DataCheck.check(accounts, holdings, records, summary, market, LocalDate.now()),
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

    // ---------------------------------------------------------------- 보기 설정 (이 기기에만)

    private val prefs = DisplayPrefs(application)

    /** 해외 종목 금액 보기 (원화 / 외화). */
    val displayCurrency: StateFlow<DisplayCurrency> = prefs.displayCurrency
    fun setDisplayCurrency(mode: DisplayCurrency) = prefs.setDisplayCurrency(mode)

    /** 보유 종목 정렬 기준 (평가금 / 총 수익률 / 오늘 수익률). */
    val holdingSort: StateFlow<HoldingSort> = prefs.holdingSort
    fun setHoldingSort(sort: HoldingSort) = prefs.setHoldingSort(sort)

    /** 비중 탭에서 마지막으로 본 설정·도넛 보기, 매수 계산기에 예수금도 쓸지. */
    val selectedAllocation: StateFlow<String?> = prefs.selectedAllocation
    fun selectAllocation(id: String) = prefs.selectAllocation(id)
    val allocationRing: StateFlow<String> = prefs.allocationRing
    fun setAllocationRing(ring: String) = prefs.setAllocationRing(ring)
    val allocationUseCash: StateFlow<Boolean> = prefs.allocationUseCash
    fun setAllocationUseCash(use: Boolean) = prefs.setAllocationUseCash(use)

    /** 계좌별 기록 유형 필터 (뺀 유형). */
    val recordHidden: StateFlow<Map<String, Set<RecordType>>> = prefs.recordHidden
    fun setRecordHidden(accountId: String, hidden: Set<RecordType>) = prefs.setRecordHidden(accountId, hidden)

    /** 오늘 변동 상세 화면의 필터 (뺀 계좌·종류). */
    val todayFilter: StateFlow<TodayFilter> = prefs.todayFilter
    fun setTodayFilter(filter: TodayFilter) = prefs.setTodayFilter(filter)

    // ---------------------------------------------------------------- 비중 설정

    val allocationTargets: StateFlow<List<AllocationTarget>?> = repository.allocationTargets()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveAllocationTarget(target: AllocationTarget): String = repository.saveAllocationTarget(target)

    fun deleteAllocationTarget(id: String) = repository.deleteAllocationTarget(id)

    // ---------------------------------------------------------------- 그래프

    private val charts = ChartSync(viewModelScope, data, apiStatus, ChartRepository(firestore, uid), marketRepository, prefs)

    /** 미리 계산해 저장한 그래프 일별 값 (계좌 id → 값). null 이면 아직 읽기 전. 기간별 수익 계산에도 쓴다. */
    val storedCharts: StateFlow<Map<String, StoredChart>?> = charts.storedCharts
    /** 그래프 일별 값을 계산하는 중 (설정의 [그래프 전체 다시 계산] 표시용). */
    val chartComputing: StateFlow<Boolean> = charts.computing
    val chart: StateFlow<ChartState> = charts.chart

    fun selectChartInterval(interval: ChartInterval) = charts.selectInterval(interval)
    fun selectChartAccount(accountId: String?) = charts.selectAccount(accountId)

    /** 설정의 [그래프 전체 다시 계산]: 모든 계좌의 일별 값을 처음부터 다시 계산한다. */
    fun recomputeCharts() = charts.recomputeAll()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    init {
        // 앱을 열면 첫 데이터가 온 뒤 한 번 시세·환율을 새로 받고, 그래프에 필요한 과거 시세를 채운다.
        viewModelScope.launch {
            data.filterNotNull().first()
            refreshQuotes(silent = true)
            charts.ensureTradeFx()
        }
    }

    // ---------------------------------------------------------------- 기록

    fun saveAccount(account: InvestmentAccount): String = repository.saveAccount(account)

    fun deleteAccount(accountId: String) {
        val current = data.value ?: return
        repository.deleteAccount(accountId, current.holdings, current.records)
        api.forgetLinkedAccount(accountId)
    }

    fun saveHolding(holding: Holding): String = repository.saveHolding(holding)

    fun deleteHolding(holdingId: String) {
        val current = data.value ?: return
        repository.deleteHolding(holdingId, current.records)
    }

    fun saveRecord(record: Record) {
        repository.saveRecord(record)
        charts.syncHistorySoon()
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
        charts.syncHistorySoon()
        return records.size
    }

    /** 표로 일괄 입력 저장 (입출금·배당): 기록을 한꺼번에 저장한다. */
    fun saveImportedRecords(records: List<Record>) {
        if (records.isEmpty()) return
        repository.importTrades(emptyMap(), records)
        _message.value = "${records.size}건을 저장했습니다."
        charts.syncHistorySoon()
    }

    fun deleteRecord(recordId: String) = repository.deleteRecord(recordId)

    fun saveManualUsdKrw(rate: Double?) = repository.saveManualUsdKrw(rate)

    /** 데이터 점검 항목 무시 / 다시 보기 (폰·PC 공유). */
    fun setCheckIgnored(key: String, ignored: Boolean) = repository.setCheckIgnored(key, ignored)

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
        val status = apiStatus.value
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
            charts.syncHistory()
        }
    }

    // ---------------------------------------------------------------- API 설정

    fun saveKisCredentials(appKey: String, appSecret: String) = api.saveKisCredentials(appKey, appSecret)
    fun saveKisServiceExpiry(date: LocalDate?) = api.saveKisServiceExpiry(date)
    fun clearKisCredentials() = api.clearKisCredentials()
    fun saveEximKey(key: String) = api.saveEximKey(key)
    fun clearEximKey() = api.clearEximKey()
    fun testKis() = api.testKis()
    fun testExim() = api.testExim()

    // ---------------------------------------------------------------- 한투 계좌 연결 (M5)

    fun saveKisLink(accountNo: String, product: String, linkedAccountId: String?, syncStart: LocalDate) =
        api.saveKisLink(accountNo, product, linkedAccountId, syncStart)

    fun syncKisAccount() {
        val current = data.value ?: return
        val status = apiStatus.value
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
                api.markSynced()
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
            charts.ensureTradeFx()
            charts.syncHistory(force = true)
        }
    }

    /** 이 기기에 저장된 API 키·연결 정보를 모두 지운다 (탈퇴할 때). */
    fun clearLocalSecrets() {
        api.clearAll()
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
