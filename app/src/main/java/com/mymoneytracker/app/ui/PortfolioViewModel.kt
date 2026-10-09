package com.mymoneytracker.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.mymoneytracker.app.data.AppSettings
import com.mymoneytracker.app.data.PortfolioRepository
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.portfolio.PortfolioSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 화면들이 공유하는 투자 데이터 전체. */
data class PortfolioData(
    val accounts: List<InvestmentAccount>,
    val holdings: List<Holding>,
    val records: List<Record>,
    val settings: AppSettings,
    val summary: PortfolioSummary,
) {
    val usdKrw: Double? get() = settings.manualUsdKrw

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

/** 로그인한 사용자 한 명의 투자 데이터를 화면들에 공급한다. */
class PortfolioViewModel(repositoryFactory: ((String) -> Unit) -> PortfolioRepository) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val repository = repositoryFactory { _message.value = it }

    /** null 이면 아직 첫 데이터를 받기 전(로딩 중). */
    val data: StateFlow<PortfolioData?> = combine(
        repository.accounts(),
        repository.holdings(),
        repository.records(),
        repository.settings(),
    ) { accounts, holdings, records, settings ->
        PortfolioData(
            accounts = accounts,
            holdings = holdings,
            records = records,
            settings = settings,
            summary = PortfolioCalculator.summarize(accounts, holdings, records, settings.manualUsdKrw),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val summary: StateFlow<PortfolioSummary?> = data.map { it?.summary }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveAccount(account: InvestmentAccount): String = repository.saveAccount(account)

    fun deleteAccount(accountId: String) {
        val current = data.value ?: return
        repository.deleteAccount(accountId, current.holdings, current.records)
    }

    fun saveHolding(holding: Holding): String = repository.saveHolding(holding)

    fun deleteHolding(holdingId: String) {
        val current = data.value ?: return
        repository.deleteHolding(holdingId, current.records)
    }

    fun saveRecord(record: Record) = repository.saveRecord(record)

    fun deleteRecord(recordId: String) = repository.deleteRecord(recordId)

    fun saveManualUsdKrw(rate: Double?) = repository.saveManualUsdKrw(rate)

    fun showMessage(text: String) {
        _message.value = text
    }

    fun messageShown() {
        _message.value = null
    }

    companion object {
        fun factory(uid: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PortfolioViewModel { onError -> PortfolioRepository(FirebaseFirestore.getInstance(), uid, onError) }
            }
        }
    }
}
